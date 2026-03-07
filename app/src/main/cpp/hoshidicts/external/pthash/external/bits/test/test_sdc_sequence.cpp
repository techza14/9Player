#include "common.hpp"
#include "include/sdc_sequence.hpp"

constexpr uint64_t sequence_length = 10000;

std::vector<uint64_t> get_seq(const uint64_t sequence_length, const uint64_t universe,
                              const uint64_t seed = essentials::get_random_seed())  //
{
    essentials::uniform_int_rng<uint64_t> distr(0, universe - 1, seed);
    std::vector<uint64_t> seq(sequence_length);
    std::generate(seq.begin(), seq.end(), [&]() { return distr.gen(); });
    assert(seq.size() == sequence_length);
    return seq;
}

sdc_sequence<> encode_with_sdc_sequence(std::vector<uint64_t> const& seq) {
    std::cout << "encoding seq with a sdc_sequence..." << std::endl;
    sdc_sequence<> sdc;
    sdc.encode(seq.begin(), seq.size());
    std::cout << "sdc.size() = " << sdc.size() << '\n';
    std::cout << "measured bits/int = " << (8.0 * sdc.num_bytes()) / sdc.size() << std::endl;
    {
        compact_vector cv;
        cv.build(seq.begin(), seq.size());
        std::cout << "  (for reference: compact vector, measured bits/int = "
                  << (8.0 * cv.num_bytes()) / cv.size() << ")" << std::endl;
    }
    return sdc;
}

TEST_CASE("access") {
    uint64_t max_small_value = test::get_random_uint() % 10;
    if (max_small_value == 0) max_small_value = 1;
    uint64_t max_large_value = test::get_random_uint() % 10000;
    if (max_large_value == 0) max_large_value = 100;
    std::cout << "max_small_value = " << max_small_value << std::endl;
    std::cout << "max_large_value = " << max_large_value << std::endl;

    std::vector<uint64_t> seq =
        test::get_skewed_sequence(sequence_length, max_small_value, max_large_value,
                                  0.9  // skew factor
        );
    auto sdc = encode_with_sdc_sequence(seq);
    std::cout << "checking correctness of access..." << std::endl;
    for (uint64_t i = 0; i != sequence_length; ++i) {
        uint64_t got = sdc.access(i);  // get the integer at position i
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("save_load_and_access") {
    uint64_t max_small_value = test::get_random_uint() % 10;
    if (max_small_value == 0) max_small_value = 1;
    uint64_t max_large_value = test::get_random_uint() % 10000;
    if (max_large_value == 0) max_large_value = 100;
    std::cout << "max_small_value = " << max_small_value << std::endl;
    std::cout << "max_large_value = " << max_large_value << std::endl;

    std::vector<uint64_t> seq =
        test::get_skewed_sequence(sequence_length, max_small_value, max_large_value,
                                  0.9  // skew factor
        );

    auto sdc = encode_with_sdc_sequence(seq);
    const std::string output_filename("sdc.bin");
    uint64_t num_saved_bytes = essentials::save(sdc, output_filename.c_str());
    std::cout << "num_saved_bytes = " << num_saved_bytes << std::endl;
    sdc_sequence<> sdc_loaded;
    uint64_t num_loaded_bytes = essentials::load(sdc_loaded, output_filename.c_str());
    std::cout << "num_loaded_bytes = " << num_loaded_bytes << std::endl;
    std::remove(output_filename.c_str());
    REQUIRE(num_saved_bytes == num_loaded_bytes);
    std::cout << "checking correctness of access..." << std::endl;
    for (uint64_t i = 0; i != seq.size(); ++i) {
        uint64_t got = sdc_loaded.access(i);
        REQUIRE_MESSAGE(got == seq[i], "got " << got << " at position " << i << "/" << seq.size()
                                              << " but expected " << seq[i]);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}
