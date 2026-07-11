#pragma once

#include <cstdint>
#include <cstddef>
#include <optional>
#include <string>
#include <vector>

#include "../memory/memory.hpp"

struct ZipEntry {
  std::string name;
  uint16_t compression_method;
  uint32_t compressed_size;
  uint32_t uncompressed_size;
  size_t data_offset;
};

struct Zip {
  static constexpr size_t kMaxZipEntries = 20000;
  static constexpr size_t kMaxIndexBytes = 8u * 1024u * 1024u;
  static constexpr size_t kMaxStyleBytes = 4u * 1024u * 1024u;
  static constexpr size_t kMaxBankBytes = 64u * 1024u * 1024u;
  static constexpr size_t kMaxMediaEntryBytes = 32u * 1024u * 1024u;
  static constexpr uint64_t kMaxTotalMediaBytes = 256ull * 1024ull * 1024ull;
  static constexpr size_t kMaxMediaFiles = 10000;
  static constexpr size_t kMaxZipPathBytes = 512;

  memory::mapped_file file;
  std::vector<ZipEntry> entries;

  ~Zip();
  bool open(const std::string& path);
  int find(const std::string& name) const;
  std::string read(int index, size_t max_bytes = kMaxBankBytes) const;

  struct MediaResult {
    std::string path;
    std::vector<char> blob;
  };

  std::optional<MediaResult> read_media(int index) const;

 private:
  bool parse_central_directory();
};
