#include "common.hpp"
#include "include/darray.hpp"

constexpr uint64_t num_positions = 10000;

bit_vector encode_with_bit_vector(std::vector<uint64_t> const& seq, bool index_ones) {
    std::cout << "encoding seq with a bit_vector..." << std::endl;
    std::cout << "seq.size() = " << seq.size() << std::endl;
    std::cout << "seq.back() = " << seq.back() << std::endl;
    bit_vector::builder bv_builder(seq.back() + 1, !index_ones);
    for (auto x : seq) {
        bv_builder.set(x, index_ones);            // set bit at position x
        assert(bv_builder.get(x) == index_ones);  // must be set
    }
    bit_vector bv;
    bv_builder.build(bv);
    std::cout << "bv.num_bits() = " << bv.num_bits() << '\n';
    std::cout << "density = " << ((seq.size() + 1) * 100.0) / bv.num_bits() << "%\n";
    std::cout << "measured bits/int = " << (8.0 * bv.num_bytes()) / seq.size() << std::endl;
    return bv;
}

template <typename DArray, bool index_ones>
void run_test(const uint64_t max_int) {
    constexpr bool all_distinct = true;
    std::vector<uint64_t> seq = test::get_sorted_sequence(num_positions, max_int, all_distinct);
    auto bv = encode_with_bit_vector(seq, index_ones);
    DArray select_index;
    select_index.build(bv);
    REQUIRE(select_index.num_positions() == seq.size());
    for (uint64_t i = 0; i != seq.size(); ++i) {
        uint64_t pos = select_index.select(bv, i);  // position of the i-th bit set
        // std::cout << "select(" << i << ") = " << pos << std::endl;
        REQUIRE_MESSAGE(pos == seq[i], "got " << pos << " but expected " << seq[i]);
    }

    const std::string output_filename("darray.bin");
    uint64_t num_saved_bytes = essentials::save(select_index, output_filename.c_str());
    std::cout << "num_saved_bytes = " << num_saved_bytes << std::endl;
    DArray select_index_loaded;
    uint64_t num_loaded_bytes = essentials::load(select_index_loaded, output_filename.c_str());
    std::cout << "num_loaded_bytes = " << num_loaded_bytes << std::endl;
    std::remove(output_filename.c_str());
    REQUIRE(num_saved_bytes == num_loaded_bytes);

    DArray other;
    select_index_loaded.swap(other);
    REQUIRE(select_index.num_positions() == seq.size());
    for (uint64_t i = 0; i != seq.size(); ++i) {
        uint64_t pos = other.select(bv, i);  // position of the i-th bit set
        // std::cout << "select(" << i << ") = " << pos << std::endl;
        REQUIRE_MESSAGE(pos == seq[i], "got " << pos << " but expected " << seq[i]);
    }

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("super_sparse1") { run_test<darray1, true>(32 * 1024); }
TEST_CASE("super_sparse0") { run_test<darray0, false>(32 * 1024); }

TEST_CASE("very_sparse1") { run_test<darray1, true>(1024); }
TEST_CASE("very_sparse0") { run_test<darray0, false>(1024); }

TEST_CASE("sparse1") { run_test<darray1, true>(128); }
TEST_CASE("sparse0") { run_test<darray0, false>(128); }

TEST_CASE("dense1") { run_test<darray1, true>(32); }
TEST_CASE("dense0") { run_test<darray0, false>(32); }

TEST_CASE("very_dense1") { run_test<darray1, true>(8); }
TEST_CASE("very_dense0") { run_test<darray0, false>(8); }

TEST_CASE("super_dense1") { run_test<darray1, true>(2); }
TEST_CASE("super_dense0") { run_test<darray0, false>(2); }
