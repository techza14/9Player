#include <jni.h>
#include <utf8.h>

#include <algorithm>
#include <cstdint>
#include <exception>
#include <filesystem>
#include <memory>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "hoshidicts/importer.hpp"
#include "hoshidicts/lookup.hpp"

extern "C" {
const char* mdict_native_import_json(const char* mdx_path, const char* output_dir);
const char* mdict_native_lookup_json(const char* entries_path, const char* query, int max_results,
                                     int scan_length);
void mdict_native_clear_lookup_cache();
void mdict_native_free_string(char* ptr);
}

namespace {

struct LookupContext {
  explicit LookupContext(std::vector<std::string> dictionary_paths)
      : dictionary_paths(std::move(dictionary_paths)), lookup(query, deconjugator) {
    for (const auto& path : this->dictionary_paths) {
      query.add_term_dict(path);
      query.add_freq_dict(path);
      query.add_pitch_dict(path);
    }
  }

  std::vector<std::string> dictionary_paths;
  DictionaryQuery query;
  Deconjugator deconjugator;
  Lookup lookup;
  std::mutex mutex;
};

std::mutex g_context_cache_mutex;
std::unordered_map<std::string, std::weak_ptr<LookupContext>> g_context_cache;

std::string jstring_to_string(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return {};
  std::string out(chars);
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

std::vector<std::string> jstring_array_to_vector(JNIEnv* env, jobjectArray values) {
  std::vector<std::string> out;
  if (values == nullptr) return out;
  const jsize count = env->GetArrayLength(values);
  if (count <= 0) return out;
  out.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    auto* raw = static_cast<jstring>(env->GetObjectArrayElement(values, i));
    out.push_back(jstring_to_string(env, raw));
    if (raw != nullptr) {
      env->DeleteLocalRef(raw);
    }
  }
  return out;
}

void append_json_string(std::ostringstream& out, std::string_view value) {
  out << '"';
  for (unsigned char ch : value) {
    switch (ch) {
      case '\"':
        out << "\\\"";
        break;
      case '\\':
        out << "\\\\";
        break;
      case '\b':
        out << "\\b";
        break;
      case '\f':
        out << "\\f";
        break;
      case '\n':
        out << "\\n";
        break;
      case '\r':
        out << "\\r";
        break;
      case '\t':
        out << "\\t";
        break;
      default:
        if (ch < 0x20) {
          static constexpr const char* HEX = "0123456789abcdef";
          out << "\\u00" << HEX[(ch >> 4) & 0x0F] << HEX[ch & 0x0F];
        } else {
          out << static_cast<char>(ch);
        }
        break;
    }
  }
  out << '"';
}

std::string json_error(std::string_view message) {
  std::ostringstream out;
  out << "{\"success\":false,\"error\":";
  append_json_string(out, message);
  out << "}";
  return out.str();
}

std::string build_context_key(const std::vector<std::string>& dictionary_paths) {
  std::ostringstream out;
  for (size_t i = 0; i < dictionary_paths.size(); ++i) {
    if (i > 0) out << '\n';
    out << dictionary_paths[i];
  }
  return out.str();
}

std::shared_ptr<LookupContext> get_lookup_context(const std::vector<std::string>& dictionary_paths) {
  const std::string key = build_context_key(dictionary_paths);
  if (key.empty()) return nullptr;

  std::lock_guard<std::mutex> lock(g_context_cache_mutex);
  auto it = g_context_cache.find(key);
  if (it != g_context_cache.end()) {
    if (auto reused = it->second.lock()) {
      return reused;
    }
  }

  auto created = std::make_shared<LookupContext>(dictionary_paths);
  g_context_cache[key] = created;
  return created;
}

std::string join_ints(const std::vector<int>& values) {
  if (values.empty()) return {};
  std::ostringstream out;
  for (size_t i = 0; i < values.size(); ++i) {
    if (i > 0) out << ',';
    out << values[i];
  }
  return out.str();
}

std::string join_frequency_display(const std::vector<Frequency>& values) {
  if (values.empty()) return {};
  std::ostringstream out;
  for (size_t i = 0; i < values.size(); ++i) {
    if (i > 0) out << " / ";
    if (!values[i].display_value.empty()) {
      out << values[i].display_value;
    } else {
      out << values[i].value;
    }
  }
  return out.str();
}

std::string frequency_for_dictionary(const TermResult& term, const std::string& dictionary_name) {
  for (const auto& entry : term.frequencies) {
    if (entry.dict_name == dictionary_name) {
      return join_frequency_display(entry.frequencies);
    }
  }
  if (term.frequencies.empty()) return {};
  if (term.frequencies.size() == 1) {
    const auto& single = term.frequencies.front();
    const std::string value = join_frequency_display(single.frequencies);
    if (!single.dict_name.empty() && !value.empty()) {
      return single.dict_name + ": " + value;
    }
    return value;
  }
  std::ostringstream out;
  for (size_t i = 0; i < term.frequencies.size(); ++i) {
    if (i > 0) out << " ; ";
    if (!term.frequencies[i].dict_name.empty()) {
      out << term.frequencies[i].dict_name << ": ";
    }
    out << join_frequency_display(term.frequencies[i].frequencies);
  }
  return out.str();
}

std::string pitch_for_dictionary(const TermResult& term, const std::string& dictionary_name) {
  for (const auto& entry : term.pitches) {
    if (entry.dict_name == dictionary_name) {
      return join_ints(entry.pitch_positions);
    }
  }
  if (term.pitches.empty()) return {};
  if (term.pitches.size() == 1) {
    const auto& single = term.pitches.front();
    const std::string value = join_ints(single.pitch_positions);
    if (!single.dict_name.empty() && !value.empty()) {
      return single.dict_name + ": " + value;
    }
    return value;
  }
  std::ostringstream out;
  for (size_t i = 0; i < term.pitches.size(); ++i) {
    if (i > 0) out << " ; ";
    if (!term.pitches[i].dict_name.empty()) {
      out << term.pitches[i].dict_name << ": ";
    }
    out << join_ints(term.pitches[i].pitch_positions);
  }
  return out.str();
}

int utf8_length(std::string_view value) {
  try {
    return static_cast<int>(utf8::distance(value.begin(), value.end()));
  } catch (...) {
    return static_cast<int>(value.size());
  }
}

std::string build_lookup_json(const std::vector<LookupResult>& lookup_results, int max_results) {
  const int hard_limit = std::max(max_results, 1) * 4;
  int emitted = 0;
  std::ostringstream out;
  out << "{\"results\":[";
  bool first = true;

  for (size_t rank = 0; rank < lookup_results.size() && emitted < hard_limit; ++rank) {
    const auto& item = lookup_results[rank];
    const int matched_length = utf8_length(item.matched);
    const int base_score =
        matched_length * 1000 - item.preprocessor_steps * 20 - static_cast<int>(item.process.size()) * 5 -
        static_cast<int>(rank);

    for (const auto& glossary : item.term.glossaries) {
      if (emitted >= hard_limit) break;
      if (!first) out << ',';
      first = false;

      const std::string frequency = frequency_for_dictionary(item.term, glossary.dict_name);
      const std::string pitch = pitch_for_dictionary(item.term, glossary.dict_name);

      out << '{';
      out << "\"term\":";
      append_json_string(out, item.term.expression);
      out << ",\"reading\":";
      append_json_string(out, item.term.reading);
      out << ",\"dictionary\":";
      append_json_string(out, glossary.dict_name);
      out << ",\"glossary\":";
      append_json_string(out, glossary.glossary);
      out << ",\"frequency\":";
      append_json_string(out, frequency);
      out << ",\"pitch\":";
      append_json_string(out, pitch);
      out << ",\"matchedLength\":" << matched_length;
      out << ",\"score\":" << base_score;
      out << '}';
      emitted += 1;
    }
  }

  out << "]}";
  return out.str();
}

std::string build_import_json(const ImportResult& result, const std::string& output_dir) {
  std::ostringstream out;
  out << '{';
  out << "\"success\":" << (result.success ? "true" : "false");
  out << ",\"title\":";
  append_json_string(out, result.title);
  out << ",\"termCount\":" << result.term_count;
  out << ",\"metaCount\":" << result.meta_count;
  out << ",\"mediaCount\":" << result.media_count;

  std::string dict_path;
  if (result.success && !result.title.empty()) {
    dict_path = (std::filesystem::path(output_dir) / result.title).string();
  }
  out << ",\"dictPath\":";
  append_json_string(out, dict_path);

  out << ",\"errors\":[";
  for (size_t i = 0; i < result.errors.size(); ++i) {
    if (i > 0) out << ',';
    append_json_string(out, result.errors[i]);
  }
  out << "]";
  out << '}';
  return out.str();
}

jstring to_jstring(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_moe_tekuza_m9player_HoshiNativeBridge_nativeImportZip(JNIEnv* env,
                                                            jclass,
                                                            jstring j_zip_path,
                                                            jstring j_output_dir,
                                                            jboolean j_low_ram) {
  try {
    const std::string zip_path = jstring_to_string(env, j_zip_path);
    const std::string output_dir = jstring_to_string(env, j_output_dir);
    if (zip_path.empty() || output_dir.empty()) {
      return to_jstring(env, json_error("invalid import path"));
    }
    const ImportResult result = dictionary_importer::import(zip_path, output_dir, j_low_ram == JNI_TRUE);
    return to_jstring(env, build_import_json(result, output_dir));
  } catch (const std::exception& e) {
    return to_jstring(env, json_error(e.what()));
  } catch (...) {
    return to_jstring(env, json_error("unknown native import error"));
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_moe_tekuza_m9player_HoshiNativeBridge_nativeLookup(JNIEnv* env,
                                                         jclass,
                                                         jobjectArray j_dictionary_paths,
                                                         jstring j_query,
                                                         jint j_max_results,
                                                         jint j_scan_length) {
  try {
    const auto dictionary_paths = jstring_array_to_vector(env, j_dictionary_paths);
    const std::string query = jstring_to_string(env, j_query);
    if (dictionary_paths.empty() || query.empty()) {
      return to_jstring(env, "{\"results\":[]}");
    }

    auto context = get_lookup_context(dictionary_paths);
    if (!context) {
      return to_jstring(env, "{\"results\":[]}");
    }

    const int max_results = std::max(static_cast<int>(j_max_results), 1);
    const size_t scan_length = static_cast<size_t>(std::max(static_cast<int>(j_scan_length), 1));
    std::vector<LookupResult> lookup_results;
    {
      std::lock_guard<std::mutex> lock(context->mutex);
      lookup_results = context->lookup.lookup(query, max_results, scan_length);
    }
    return to_jstring(env, build_lookup_json(lookup_results, max_results));
  } catch (const std::exception& e) {
    return to_jstring(env, json_error(e.what()));
  } catch (...) {
    return to_jstring(env, json_error("unknown native lookup error"));
  }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_tekuza_m9player_HoshiNativeBridge_nativeClearLookupCache(JNIEnv*,
                                                                   jclass) {
  std::lock_guard<std::mutex> lock(g_context_cache_mutex);
  g_context_cache.clear();
}

extern "C" JNIEXPORT jstring JNICALL
Java_moe_tekuza_m9player_MdictNativeBridge_nativeImportMdx(JNIEnv* env,
                                                            jclass,
                                                            jstring j_mdx_path,
                                                            jstring j_output_dir) {
  try {
    const std::string mdx_path = jstring_to_string(env, j_mdx_path);
    const std::string output_dir = jstring_to_string(env, j_output_dir);
    if (mdx_path.empty() || output_dir.empty()) {
      return to_jstring(env, json_error("invalid mdx import path"));
    }
    const char* raw = mdict_native_import_json(mdx_path.c_str(), output_dir.c_str());
    if (raw == nullptr) {
      return to_jstring(env, json_error("mdict native import returned null"));
    }
    std::string payload(raw);
    mdict_native_free_string(const_cast<char*>(raw));
    return to_jstring(env, payload);
  } catch (const std::exception& e) {
    return to_jstring(env, json_error(e.what()));
  } catch (...) {
    return to_jstring(env, json_error("unknown native mdx import error"));
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_moe_tekuza_m9player_MdictNativeBridge_nativeLookup(JNIEnv* env,
                                                        jclass,
                                                        jstring j_entries_path,
                                                        jstring j_query,
                                                        jint j_max_results,
                                                        jint j_scan_length) {
  try {
    const std::string entries_path = jstring_to_string(env, j_entries_path);
    const std::string query = jstring_to_string(env, j_query);
    if (entries_path.empty() || query.empty()) {
      return to_jstring(env, "{\"results\":[]}");
    }
    const char* raw =
        mdict_native_lookup_json(entries_path.c_str(), query.c_str(), static_cast<int>(j_max_results),
                                 static_cast<int>(j_scan_length));
    if (raw == nullptr) {
      return to_jstring(env, json_error("mdict native lookup returned null"));
    }
    std::string payload(raw);
    mdict_native_free_string(const_cast<char*>(raw));
    return to_jstring(env, payload);
  } catch (const std::exception& e) {
    return to_jstring(env, json_error(e.what()));
  } catch (...) {
    return to_jstring(env, json_error("unknown native mdict lookup error"));
  }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_tekuza_m9player_MdictNativeBridge_nativeClearLookupCache(JNIEnv*, jclass) {
  mdict_native_clear_lookup_cache();
}
