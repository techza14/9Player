#pragma once

#include <memory>
#include <string>
#include <string_view>

namespace legacy_hash {
class mphf {
 public:
  mphf();
  ~mphf();
  uint64_t operator()(std::string_view key) const;
  void load(const std::string& path);

 private:
  struct phf;
  std::unique_ptr<phf> ptr_;
};
}
