#include "common.hpp"
#include "include/rice_sequence.hpp"

constexpr uint64_t sequence_length = 10000;

rice_sequence<> encode_with_rice_sequence(std::vector<uint64_t> const& seq) {
    std::cout << "encoding seq with a rice_sequence..." << std::endl;
    rice_sequence<> rs;
    rs.encode(seq.begin(), seq.size());
    std::cout << "rs.size() = " << rs.size() << '\n';
    std::cout << "measured bits/int = " << (8.0 * rs.num_bytes()) / rs.size() << std::endl;
    {
        compact_vector cv;
        cv.build(seq.begin(), seq.size());
        std::cout << "  (for reference: compact vector, measured bits/int = "
                  << (8.0 * cv.num_bytes()) / cv.size() << ")" << std::endl;
    }
    return rs;
}

TEST_CASE("access") {
    uint64_t max_small_value = test::get_random_uint() % 5;
    if (max_small_value == 0) max_small_value = 1;
    uint64_t max_large_value = test::get_random_uint() % 100000;
    if (max_large_value == 0) max_large_value = 100;
    std::cout << "max_small_value = " << max_small_value << std::endl;
    std::cout << "max_large_value = " << max_large_value << std::endl;

    std::vector<uint64_t> seq =
        test::get_skewed_sequence(sequence_length, max_small_value, max_large_value,
                                  0.95  // skew factor
        );

    auto rs = encode_with_rice_sequence(seq);
    std::cout << "checking correctness of access..." << std::endl;
    for (uint64_t i = 0; i != sequence_length; ++i) {
        uint64_t got = rs.access(i);  // get the integer at position i
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("save_load_and_access") {
    uint64_t max_small_value = test::get_random_uint() % 5;
    if (max_small_value == 0) max_small_value = 1;
    uint64_t max_large_value = test::get_random_uint() % 100000;
    if (max_large_value == 0) max_large_value = 100;
    std::cout << "max_small_value = " << max_small_value << std::endl;
    std::cout << "max_large_value = " << max_large_value << std::endl;

    std::vector<uint64_t> seq =
        test::get_skewed_sequence(sequence_length, max_small_value, max_large_value,
                                  0.95  // skew factor
        );

    auto rs = encode_with_rice_sequence(seq);
    const std::string output_filename("rs.bin");
    uint64_t num_saved_bytes = essentials::save(rs, output_filename.c_str());
    std::cout << "num_saved_bytes = " << num_saved_bytes << std::endl;
    rice_sequence<> rs_loaded;
    uint64_t num_loaded_bytes = essentials::load(rs_loaded, output_filename.c_str());
    std::cout << "num_loaded_bytes = " << num_loaded_bytes << std::endl;
    std::remove(output_filename.c_str());
    REQUIRE(num_saved_bytes == num_loaded_bytes);
    std::cout << "checking correctness of access..." << std::endl;
    for (uint64_t i = 0; i != seq.size(); ++i) {
        uint64_t got = rs_loaded.access(i);
        REQUIRE_MESSAGE(got == seq[i], "got " << got << " at position " << i << "/" << seq.size()
                                              << " but expected " << seq[i]);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}
