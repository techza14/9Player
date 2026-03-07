#include "common.hpp"
#include "include/cache_line_elias_fano.hpp"
#include "include/elias_fano.hpp"

constexpr uint64_t sequence_length = 10000;

cache_line_elias_fano encode_with_cache_line_elias_fano(std::vector<uint64_t> const& seq) {
    std::cout << "encoding seq with cache_line_elias_fano..." << std::endl;
    cache_line_elias_fano ef;
    ef.encode(seq.begin(), seq.size());
    REQUIRE(ef.size() == seq.size());
    std::cout << "ef.size() = " << ef.size() << '\n';
    std::cout << "ef.back() = " << ef.back() << '\n';
    std::cout << "measured bits/int = " << (8.0 * ef.num_bytes()) / ef.size() << std::endl;
    {
        elias_fano<false, false> ef;
        ef.encode(seq.begin(), seq.size());
        std::cout << "(for reference: classic ef measured bits/int = "
                  << (8.0 * ef.num_bytes()) / ef.size() << ")" << std::endl;
    }
    return ef;
}

TEST_CASE("access") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length, 500);
    // std::vector<uint64_t> seq;
    // seq.reserve(sequence_length);
    // for (uint64_t i = 0; i != sequence_length; ++i) { seq.push_back(i * 500); }
    auto ef = encode_with_cache_line_elias_fano(seq);
    std::cout << "checking correctness of random access..." << std::endl;
    for (uint64_t i = 0; i != sequence_length; ++i) {
        uint64_t got = ef.access(i);  // get the integer at position i
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("save_load_and_swap") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length, 500);
    cache_line_elias_fano ef = encode_with_cache_line_elias_fano(seq);
    const std::string output_filename("ef.bin");
    uint64_t num_saved_bytes = essentials::save(ef, output_filename.c_str());
    std::cout << "num_saved_bytes = " << num_saved_bytes << std::endl;
    cache_line_elias_fano ef_loaded;
    uint64_t num_loaded_bytes = essentials::load(ef_loaded, output_filename.c_str());
    std::cout << "num_loaded_bytes = " << num_loaded_bytes << std::endl;
    std::remove(output_filename.c_str());
    REQUIRE(num_saved_bytes == num_loaded_bytes);
    cache_line_elias_fano other;
    ef_loaded.swap(other);
    for (uint64_t i = 0; i != sequence_length; ++i) {
        uint64_t got = other.access(i);
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}
