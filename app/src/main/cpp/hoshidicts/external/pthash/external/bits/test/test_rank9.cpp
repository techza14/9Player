#include "common.hpp"
#include "include/rank9.hpp"

constexpr uint64_t num_positions = 10000;

std::pair<bit_vector, rank9> encode_with_ranked_bit_vector(std::vector<uint64_t> const& seq) {
    std::cout << "encoding seq with a ranked_bit_vector..." << std::endl;
    std::cout << "seq.size() = " << seq.size() << std::endl;
    std::cout << "seq.back() = " << seq.back() << std::endl;
    bit_vector::builder bv_builder(seq.back() + 1);
    for (auto x : seq) {
        bv_builder.set(x);          // set bit at position x
        assert(bv_builder.get(x));  // must be set
    }
    bit_vector bv;
    bv_builder.build(bv);
    rank9 rank_index;
    rank_index.build(bv);
    std::cout << "bv.num_bits() = " << bv.num_bits() << '\n';
    std::cout << "rank_index.num_ones() = " << rank_index.num_ones() << '\n';
    std::cout << "density = " << ((seq.size() + 1) * 100.0) / bv.num_bits() << "%\n";
    std::cout << "bit_vector measured bits/int = " << (8.0 * bv.num_bytes()) / seq.size()
              << std::endl;
    std::cout << "rank_index measured bits/int = " << (8.0 * rank_index.num_bytes()) / seq.size()
              << std::endl;
    return {bv, rank_index};
}

void run_test(const uint64_t max_int) {
    constexpr bool all_distinct = true;
    std::vector<uint64_t> seq = test::get_sorted_sequence(num_positions, max_int, all_distinct);
    auto [B, rank_index] = encode_with_ranked_bit_vector(seq);
    REQUIRE(rank_index.num_ones() == seq.size());
    REQUIRE(rank_index.rank1(B, 0) == 0);
    for (uint64_t i = 0; i != seq.size(); ++i) {
        uint64_t j = seq[i];
        uint64_t num_ones = rank_index.rank1(B, j);   // number of 1s in B[0..j)
        uint64_t num_zeros = rank_index.rank0(B, j);  // number of 0s in B[0..j)
        REQUIRE(num_ones + num_zeros == j);
        // std::cout << "rank1(" << j << ") = " << num_ones << std::endl;
        // std::cout << "rank0(" << j << ") = " << num_zeros << std::endl;
        REQUIRE_MESSAGE(num_ones == i, "got " << num_ones << " but expected " << i);
        REQUIRE_MESSAGE(num_zeros == j - i, "got " << num_ones << " but expected " << j - i);
    }

    const std::string output_filename("r9.bin");
    uint64_t num_saved_bytes = essentials::save(rank_index, output_filename.c_str());
    std::cout << "num_saved_bytes = " << num_saved_bytes << std::endl;
    rank9 rank_index_loaded;
    uint64_t num_loaded_bytes = essentials::load(rank_index_loaded, output_filename.c_str());
    std::cout << "num_loaded_bytes = " << num_loaded_bytes << std::endl;
    std::remove(output_filename.c_str());
    REQUIRE(num_saved_bytes == num_loaded_bytes);

    rank9 other;
    rank_index_loaded.swap(other);
    REQUIRE(other.num_ones() == seq.size());
    REQUIRE(other.rank1(B, 0) == 0);
    for (uint64_t i = 0; i != seq.size(); ++i) {
        uint64_t j = seq[i];
        uint64_t num_ones = other.rank1(B, j);   // number of 1s in B[0..j)
        uint64_t num_zeros = other.rank0(B, j);  // number of 0s in B[0..j)
        REQUIRE(num_ones + num_zeros == j);
        // std::cout << "rank1(" << j << ") = " << num_ones << std::endl;
        // std::cout << "rank0(" << j << ") = " << num_zeros << std::endl;
        REQUIRE_MESSAGE(num_ones == i, "got " << num_ones << " but expected " << i);
        REQUIRE_MESSAGE(num_zeros == j - i, "got " << num_ones << " but expected " << j - i);
    }

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("super_sparse") { run_test(32 * 1024); }
TEST_CASE("very_sparse") { run_test(1024); }
TEST_CASE("sparse") { run_test(128); }
TEST_CASE("dense") { run_test(32); }
TEST_CASE("very_dense") { run_test(8); }
TEST_CASE("super_dense") { run_test(2); }
