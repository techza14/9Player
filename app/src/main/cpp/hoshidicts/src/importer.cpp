#include "hoshidicts/importer.hpp"

#include <zip.h>
#include <zstd.h>

#include <algorithm>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <future>
#include <limits>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <vector>

#include "hash/hash.hpp"
#include "json/yomitan_parser.hpp"

namespace {
constexpr ssize_t kMaxZipEntries = 20000;
constexpr size_t kMaxIndexBytes = 8u * 1024u * 1024u;
constexpr size_t kMaxStyleBytes = 4u * 1024u * 1024u;
constexpr size_t kMaxBankBytes = 64u * 1024u * 1024u;
constexpr size_t kMaxMediaEntryBytes = 32u * 1024u * 1024u;
constexpr uint64_t kMaxTotalMediaBytes = 256ull * 1024ull * 1024ull;
constexpr size_t kMaxMediaFiles = 10000;
constexpr size_t kMaxZipPathBytes = 512;
constexpr size_t kLowRamWorkerThreads = 3;

struct Files {
  std::vector<int> term_banks;
  std::vector<int> meta_banks;
  std::vector<int> tag_banks;
  std::vector<int> media_files;
};

struct ProcessedFile {
  std::vector<char> data;
  std::unordered_map<std::string, std::vector<uint64_t>> offsets;
  size_t count = 0;
};

struct MediaFile {
  std::string path;
  std::vector<char> blob;
};

std::string safe_dictionary_dir_name(std::string_view raw_title) {
  std::string out;
  out.reserve(raw_title.size());
  for (unsigned char ch : raw_title) {
    if (ch < 0x20 || ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?' ||
        ch == '"' || ch == '<' || ch == '>' || ch == '|') {
      if (out.empty() || out.back() != '_') out.push_back('_');
    } else {
      out.push_back(static_cast<char>(ch));
    }
  }

  while (!out.empty() && (out.front() == '.' || out.front() == ' ' || out.front() == '_')) {
    out.erase(out.begin());
  }
  while (!out.empty() && (out.back() == '.' || out.back() == ' ' || out.back() == '_')) {
    out.pop_back();
  }
  if (out.empty() || out == "." || out == "..") return "Dictionary";
  if (out.size() > 120) out.resize(120);
  return out;
}

bool path_starts_with(const std::filesystem::path& root, const std::filesystem::path& child) {
  auto root_it = root.begin();
  auto child_it = child.begin();
  for (; root_it != root.end(); ++root_it, ++child_it) {
    if (child_it == child.end() || *root_it != *child_it) return false;
  }
  return true;
}

std::filesystem::path safe_dictionary_output_path(const std::string& output_dir,
                                                  std::string_view raw_title) {
  const auto root = std::filesystem::absolute(output_dir).lexically_normal();
  const auto target = (root / safe_dictionary_dir_name(raw_title)).lexically_normal();
  if (!path_starts_with(root, target)) {
    throw std::runtime_error("unsafe dictionary output path");
  }
  return target;
}

void setup_stream_exceptions(std::ofstream& stream) { stream.exceptions(std::ios::failbit | std::ios::badbit); }

bool is_safe_zip_entry_name(std::string_view raw_name) {
  if (raw_name.empty() || raw_name.size() > kMaxZipPathBytes || raw_name.front() == '/') return false;
  if (raw_name.size() >= 2 && std::isalpha(static_cast<unsigned char>(raw_name[0])) && raw_name[1] == ':') {
    return false;
  }
  std::string normalized(raw_name);
  std::replace(normalized.begin(), normalized.end(), '\\', '/');
  for (const auto& component : std::filesystem::path(normalized)) {
    if (component == ".." || component.has_root_path()) return false;
  }
  return true;
}

std::string read_current_entry(zip_t* archive, size_t max_bytes) {
  const ssize_t entry_size = zip_entry_size(archive);
  if (entry_size < 0 || static_cast<size_t>(entry_size) > max_bytes) {
    throw std::runtime_error("zip entry too large");
  }
  void* raw = nullptr;
  size_t size = 0;
  ssize_t bytes_read = zip_entry_read(archive, &raw, &size);
  if (bytes_read < 0 || !raw) {
    if (raw) {
      free(raw);
    }
    return "";
  }
  if (size > max_bytes) {
    free(raw);
    throw std::runtime_error("zip entry exceeded size limit");
  }

  std::unique_ptr<void, decltype(&std::free)> buf(raw, &std::free);
  std::string buffer(static_cast<char*>(buf.get()), size);
  return buffer;
}

std::string read_file_by_index(zip_t* archive, int index, size_t max_bytes = kMaxBankBytes) {
  if (zip_entry_openbyindex(archive, index) != 0) {
    return "";
  }
  try {
    std::string buffer = read_current_entry(archive, max_bytes);
    zip_entry_close(archive);
    return buffer;
  } catch (...) {
    zip_entry_close(archive);
    throw;
  }
}

std::string read_file_by_name(zip_t* archive, const char* name, size_t max_bytes = kMaxBankBytes) {
  if (zip_entry_open(archive, name) != 0) {
    return "";
  }
  try {
    std::string buffer = read_current_entry(archive, max_bytes);
    zip_entry_close(archive);
    return buffer;
  } catch (...) {
    zip_entry_close(archive);
    throw;
  }
}

std::optional<MediaFile> read_media_by_index(zip_t* archive, int index) {
  if (zip_entry_openbyindex(archive, index) != 0) {
    return std::nullopt;
  }
  MediaFile out;
  const char* raw_name = zip_entry_name(archive);
  if (raw_name == nullptr || !is_safe_zip_entry_name(raw_name)) {
    zip_entry_close(archive);
    throw std::runtime_error("unsafe media entry path");
  }
  const ssize_t entry_size = zip_entry_size(archive);
  if (entry_size < 0 || static_cast<size_t>(entry_size) > kMaxMediaEntryBytes) {
    zip_entry_close(archive);
    throw std::runtime_error("media entry too large");
  }
  out.path = raw_name;

  void* raw = nullptr;
  size_t size = 0;
  ssize_t bytes_read = zip_entry_read(archive, &raw, &size);
  zip_entry_close(archive);
  if (bytes_read < 0 || !raw) {
    if (raw) {
      free(raw);
    }
    return std::nullopt;
  }
  if (size > kMaxMediaEntryBytes) {
    free(raw);
    throw std::runtime_error("media entry exceeded size limit");
  }
  std::unique_ptr<void, decltype(&free)> buf(raw, free);
  auto* p = static_cast<std::uint8_t*>(buf.get());
  out.blob.assign(p, p + size);
  return out;
}

Files get_files(zip_t* archive) {
  Files files;
  const ssize_t num_entries = zip_entries_total(archive);
  if (num_entries < 0) {
    return files;
  }
  if (num_entries > kMaxZipEntries) {
    throw std::runtime_error("dictionary zip has too many entries");
  }

  for (int i = 0; i < num_entries; ++i) {
    if (zip_entry_openbyindex(archive, i) != 0) {
      continue;
    }

    if (zip_entry_isdir(archive) == 1) {
      zip_entry_close(archive);
      continue;
    }

    const char* raw_name = zip_entry_name(archive);
    if (raw_name != nullptr) {
      const std::string_view name(raw_name);
      if (!is_safe_zip_entry_name(name)) {
        zip_entry_close(archive);
        throw std::runtime_error("unsafe zip entry path");
      }
      if (name.starts_with("term_bank_")) {
        files.term_banks.push_back(i);
      } else if (name.starts_with("term_meta_bank_")) {
        files.meta_banks.push_back(i);
      } else if (name.starts_with("tag_bank_")) {
        files.tag_banks.push_back(i);
      } else if (!(name == "styles.css" || name == "index.json")) {
        if (files.media_files.size() >= kMaxMediaFiles) {
          zip_entry_close(archive);
          throw std::runtime_error("dictionary zip has too many media files");
        }
        files.media_files.push_back(i);
      }
    }
    zip_entry_close(archive);
  }

  return files;
}

void write_u8(std::vector<char>& out, uint8_t value) { out.push_back(static_cast<char>(value)); }

void write_u16(std::vector<char>& out, uint16_t value) {
  const size_t old_size = out.size();
  out.resize(old_size + sizeof(uint16_t));
  std::memcpy(out.data() + old_size, &value, sizeof(uint16_t));
}

void write_u32(std::vector<char>& out, uint32_t value) {
  const size_t old_size = out.size();
  out.resize(old_size + sizeof(uint32_t));
  std::memcpy(out.data() + old_size, &value, sizeof(uint32_t));
}

void write_u64(std::vector<char>& out, uint64_t value) {
  const size_t old_size = out.size();
  out.resize(old_size + sizeof(uint64_t));
  std::memcpy(out.data() + old_size, &value, sizeof(uint64_t));
}

void write_str(std::vector<char>& out, std::string_view value) {
  if (value.empty()) {
    return;
  }
  const size_t old_size = out.size();
  out.resize(old_size + value.size());
  std::memcpy(out.data() + old_size, value.data(), value.size());
}

void write_bytes(std::vector<char>& out, const void* data, size_t n) {
  const size_t old_size = out.size();
  out.resize(old_size + n);
  std::memcpy(out.data() + old_size, data, n);
}

void merge_offsets(std::unordered_map<std::string, std::vector<uint64_t>>& a,
                   std::unordered_map<std::string, std::vector<uint64_t>>& b, uint64_t write_offset) {
  for (auto& [key, b_offsets] : b) {
    for (auto& offset : b_offsets) {
      offset += write_offset;
    }

    auto it = a.find(key);
    if (it == a.end()) {
      a.emplace(key, std::move(b_offsets));
    } else {
      auto& values = it->second;
      values.insert(values.end(), b_offsets.begin(), b_offsets.end());
    }
  }
}

ProcessedFile process_term_bank(const std::string& content) {
  ProcessedFile processed;
  if (content.empty()) {
    return processed;
  }

  std::vector<Term> out;
  if (!yomitan_parser::parse_term_bank(content, out)) {
    return processed;
  }

  std::vector<char> compressed;
  ZSTD_CCtx* cctx = ZSTD_createCCtx();
  if (!cctx) {
    return processed;
  }

  for (auto& term : out) {
    const std::string_view glossary = term.glossary.str;
    const size_t bound = ZSTD_compressBound(glossary.size());
    compressed.resize(bound);
    const size_t compressed_size =
        ZSTD_compressCCtx(cctx, compressed.data(), bound, glossary.data(), glossary.size(), 0);
    if (ZSTD_isError(compressed_size)) {
      ZSTD_freeCCtx(cctx);
      throw std::runtime_error("failed to compress glossary");
    }

    uint64_t offset = processed.data.size();
    std::string_view expr = term.expression;
    std::string_view reading = term.reading.empty() ? expr : term.reading;
    std::string_view blob{compressed.data(), compressed_size};
    std::string_view definition_tags = term.definition_tags.value_or("");

    write_u8(processed.data, 0);
    write_u16(processed.data, expr.size());
    write_str(processed.data, expr);
    write_u16(processed.data, reading.size());
    write_str(processed.data, reading);
    write_u32(processed.data, blob.size());
    write_str(processed.data, blob);
    write_u8(processed.data, definition_tags.size());
    write_str(processed.data, definition_tags);
    write_u8(processed.data, term.rules.size());
    write_str(processed.data, term.rules);
    write_u8(processed.data, term.term_tags.size());
    write_str(processed.data, term.term_tags);

    processed.offsets[std::string(expr)].push_back(offset);
    if (reading != expr) {
      processed.offsets[std::string(reading)].push_back(offset);
    }
    processed.count++;
  }
  ZSTD_freeCCtx(cctx);

  return processed;
}

ProcessedFile process_meta_bank(const std::string& content) {
  ProcessedFile processed;
  if (content.empty()) {
    return processed;
  }

  std::vector<Meta> out;
  if (!yomitan_parser::parse_meta_bank(content, out)) {
    return processed;
  }

  for (auto& meta : out) {
    uint64_t offset = processed.data.size();
    std::string_view expr = meta.expression;
    std::string_view mode = meta.mode;
    std::string_view data = meta.data.str;

    write_u8(processed.data, 1);
    write_u16(processed.data, expr.size());
    write_str(processed.data, expr);
    write_u8(processed.data, mode.size());
    write_str(processed.data, mode);
    write_u32(processed.data, data.size());
    write_str(processed.data, data);

    processed.offsets[std::string(expr)].push_back(offset);
    processed.count++;
  }

  return processed;
}

void write_terms(std::ofstream& file, std::unordered_map<std::string, std::vector<uint64_t>>& offsets, zip_t* archive,
                 const std::vector<int>& files, uint64_t& write_offset, ImportResult& result, bool low_ram) {
  if (files.empty()) {
    return;
  }

  size_t max_threads = low_ram ? kLowRamWorkerThreads : std::max<size_t>(2, static_cast<const unsigned long>(std::thread::hardware_concurrency() * 2));
  std::deque<std::future<ProcessedFile>> threads;
  auto write_processed = [&](ProcessedFile&& processed) {
    if (processed.data.empty()) {
      return;
    }
    file.write(processed.data.data(), static_cast<std::streamsize>(processed.data.size()));
    merge_offsets(offsets, processed.offsets, write_offset);
    write_offset += processed.data.size();
    result.term_count += processed.count;
  };

  for (int file_index : files) {
    std::string content = read_file_by_index(archive, file_index);
    threads.push_back(
        std::async(std::launch::async, [content = std::move(content)]() { return process_term_bank(content); }));

    if (threads.size() == max_threads) {
      write_processed(threads.front().get());
      threads.pop_front();
    }
  }

  while (!threads.empty()) {
    write_processed(threads.front().get());
    threads.pop_front();
  }
}

void write_meta(std::ofstream& file, std::unordered_map<std::string, std::vector<uint64_t>>& offsets, zip_t* archive,
                const std::vector<int>& files, uint64_t& write_offset, ImportResult& result, bool low_ram) {
  if (files.empty()) {
    return;
  }

  size_t max_threads = low_ram ? kLowRamWorkerThreads : std::max<size_t>(2, static_cast<const unsigned long>(std::thread::hardware_concurrency() * 2));
  std::deque<std::future<ProcessedFile>> threads;
  auto write_processed = [&](ProcessedFile&& processed) {
    if (processed.data.empty()) {
      return;
    }
    file.write(processed.data.data(), static_cast<std::streamsize>(processed.data.size()));
    merge_offsets(offsets, processed.offsets, write_offset);
    write_offset += processed.data.size();
    result.meta_count += processed.count;
  };

  for (int file_index : files) {
    std::string content = read_file_by_index(archive, file_index);
    threads.push_back(
        std::async(std::launch::async, [content = std::move(content)]() { return process_meta_bank(content); }));

    if (threads.size() == max_threads) {
      write_processed(threads.front().get());
      threads.pop_front();
    }
  }

  while (!threads.empty()) {
    write_processed(threads.front().get());
    threads.pop_front();
  }
}

void write_offset_index(std::ostream& file, std::unordered_map<std::string, std::vector<uint64_t>>& offsets,
                        uint64_t& write_offset, std::vector<std::string_view>& keys,
                        std::vector<uint64_t>& key_offsets) {
  std::vector<char> offset_buf;
  for (auto& [key, offs] : offsets) {
    keys.push_back(key);
    key_offsets.push_back(write_offset);

    write_u32(offset_buf, offs.size());
    write_bytes(offset_buf, offs.data(), offs.size() * sizeof(uint64_t));

    write_offset += sizeof(uint32_t) + offs.size() * sizeof(uint64_t);
  }
  file.write(offset_buf.data(), static_cast<std::streamsize>(offset_buf.size()));
}

void write_media(const std::string& path, zip_t* archive, const std::vector<int>& files, ImportResult& result) {
  if (files.empty()) {
    return;
  }

  std::ofstream blobs(path + "/media.bin", std::ios::binary);
  std::ofstream index(path + "/media_index.bin", std::ios::binary);
  setup_stream_exceptions(blobs);
  setup_stream_exceptions(index);

  uint64_t write_offset = 0;
  std::vector<char> index_buf;
  for (int file_index : files) {
    auto media = read_media_by_index(archive, file_index);
    if (!media.has_value()) {
      continue;
    }

    const auto blob_size = media->blob.size();
    if (media->path.size() > std::numeric_limits<uint16_t>::max()) {
      throw std::runtime_error("media path too long");
    }
    if (blob_size > std::numeric_limits<uint32_t>::max()) {
      throw std::runtime_error("media entry too large");
    }
    if (write_offset > kMaxTotalMediaBytes || blob_size > kMaxTotalMediaBytes - write_offset) {
      throw std::runtime_error("dictionary media total size exceeded");
    }
    blobs.write(media->blob.data(), static_cast<std::streamsize>(blob_size));

    write_u16(index_buf, static_cast<uint16_t>(media->path.size()));
    write_str(index_buf, media->path);
    write_u64(index_buf, write_offset);
    write_u32(index_buf, static_cast<uint32_t>(blob_size));

    write_offset += blob_size;
    result.media_count++;
  }
  index.write(index_buf.data(), static_cast<std::streamsize>(index_buf.size()));
}
}

ImportResult dictionary_importer::import(const std::string& zip_path, const std::string& output_dir, bool low_ram) {
  ImportResult result;
  zip_t* archive = nullptr;
  try {
    archive = zip_open(zip_path.c_str(), 0, 'r');
    if (!archive) {
      throw std::runtime_error("failed to open zip");
    }

    std::string index_content = read_file_by_name(archive, "index.json", kMaxIndexBytes);
    if (index_content.empty()) {
      throw std::runtime_error("could not find or read index.json");
    }

    Index index;
    if (!yomitan_parser::parse_index(index_content, index)) {
      throw std::runtime_error("failed to parse index.json");
    }

    result.title = index.title;

    std::filesystem::path dict_path = safe_dictionary_output_path(output_dir, result.title);
    result.dict_path = dict_path.string();
    std::string path = dict_path.string();
    std::filesystem::create_directories(dict_path);

    if (glz::write_file_json(index, path + "/info.json", std::string{})) {
      throw std::runtime_error("failed to write info.json");
    }

    std::string styles = read_file_by_name(archive, "styles.css", kMaxStyleBytes);
    if (!styles.empty()) {
      std::ofstream styles_file(path + "/styles.css", std::ios::binary);
      setup_stream_exceptions(styles_file);
      styles_file.write(styles.data(), static_cast<std::streamsize>(styles.size()));
    }

    const Files files = get_files(archive);
    std::ofstream blobs(path + "/blobs.bin", std::ios::binary);
    setup_stream_exceptions(blobs);
    std::unordered_map<std::string, std::vector<uint64_t>> offsets;
    uint64_t write_offset = 0;
    write_terms(blobs, offsets, archive, files.term_banks, write_offset, result, low_ram);
    write_meta(blobs, offsets, archive, files.meta_banks, write_offset, result, low_ram);
    if (offsets.empty()) {
      throw std::runtime_error("empty dictionary");
    }

    std::vector<std::string_view> keys;
    std::vector<uint64_t> key_offsets;
    write_offset_index(blobs, offsets, write_offset, keys, key_offsets);

    hash::mphf phf;
    phf.build(keys);
    phf.save(path + "/hash.mph");

    std::vector<uint64_t> offset_hash_table(keys.size());
    for (size_t i = 0; i < keys.size(); i++) {
      auto& key = keys[i];
      offset_hash_table[phf(key)] = key_offsets[i];
    }
    std::ofstream offs(path + "/offsets.bin", std::ios::binary);
    setup_stream_exceptions(offs);
    offs.write(reinterpret_cast<const char*>(offset_hash_table.data()),
               static_cast<std::streamsize>(offset_hash_table.size() * sizeof(uint64_t)));

    write_media(path, archive, files.media_files, result);

    result.success = true;
  } catch (const std::exception& e) {
    result.success = false;
    result.errors.emplace_back(e.what());
  }

  if (archive) {
    zip_close(archive);
  }

  if (!result.success && !result.dict_path.empty()) {
    const auto root = std::filesystem::absolute(output_dir).lexically_normal();
    const auto failed_path = std::filesystem::absolute(result.dict_path).lexically_normal();
    if (path_starts_with(root, failed_path)) {
      std::filesystem::remove_all(failed_path);
    }
  }

  return result;
}
