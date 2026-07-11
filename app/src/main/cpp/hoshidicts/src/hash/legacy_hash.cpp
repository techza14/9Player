#include "legacy_hash.hpp"

#include <pthash.hpp>

namespace legacy_hash {
struct xxhash64_sv {
  using hash_type = pthash::hash64;
  static pthash::hash64 hash(std::string_view value, uint64_t seed) {
    return pthash::hash64{XXH64(value.data(), value.size(), seed)};
  }
};

struct mphf::phf {
  pthash::single_phf<xxhash64_sv, pthash::skew_bucketer, pthash::compact, true> value;
};

mphf::mphf() : ptr_(std::make_unique<phf>()) {}
mphf::~mphf() = default;
uint64_t mphf::operator()(std::string_view key) const { return ptr_->value(key); }
void mphf::load(const std::string& path) { essentials::load(ptr_->value, path.c_str()); }
}
