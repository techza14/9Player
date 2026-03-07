#include "common.hpp"
#include "include/bit_vector.hpp"

constexpr uint64_t sequence_length = 10000;

TEST_CASE("bit_vector::builder::get_bits") {
    const uint64_t max_int = test::get_random_uint();
    std::cout << "max_int = " << max_int << std::endl;
    std::vector<uint64_t> seq = test::get_sequence(sequence_length, max_int);
    const uint64_t width = max_int != 0 ? std::ceil(std::log2(max_int + 1)) : 1;
    std::cout << "width = " << width << std::endl;
    bit_vector::builder bv_builder(sequence_length * width);
    for (uint64_t i = 0; i != sequence_length; ++i) {
        bv_builder.set_bits(i * width, seq[i], width);
    }
    bit_vector bv;
    bv_builder.build(bv);
    std::cout << "checking correctness of bit_vector::builder::get_bits..." << std::endl;
    for (uint64_t i = 0; i != sequence_length; ++i) {
        uint64_t got = bv.get_bits(i * width, width);  // get the integer at position i
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, i << "/" << sequence_length << ": got " << got
                                           << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("bit_vector::iterator::operator*") {
    const uint64_t max_int = test::get_random_uint();
    std::cout << "max_int = " << max_int << std::endl;
    constexpr bool all_distinct = true;
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length, max_int, all_distinct);
    const uint64_t num_bits = seq.back();
    bit_vector::builder bv_builder(num_bits + 1);
    for (auto pos : seq) bv_builder.set(pos);
    bit_vector bv;
    bv_builder.build(bv);
    std::cout << "checking correctness of bit_vector::iterator::operator*..." << std::endl;
    bit_vector::iterator it = bv.get_iterator_at(0);
    uint64_t i = 0;
    for (auto pos : seq) {
        while (i != pos) {
            REQUIRE_MESSAGE(*it == false, "required false but got " << *it);
            ++i;
            ++it;
        }
        assert(i == pos);
        REQUIRE_MESSAGE(*it == true, "required true but got " << *it);
        ++i;
        ++it;
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("bit_vector::iterator::skip_to") {
    const uint64_t max_int = test::get_random_uint();
    std::cout << "max_int = " << max_int << std::endl;
    constexpr bool all_distinct = true;
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length, max_int, all_distinct);
    const uint64_t num_bits = seq.back();
    bit_vector::builder bv_builder(num_bits + 1);
    std::vector<bool> vec_bools(num_bits + 1, false);
    for (auto pos : seq) {
        bv_builder.set(pos);
        vec_bools[pos] = true;
    }
    bit_vector bv;
    bv_builder.build(bv);
    std::cout << "checking correctness of bit_vector::iterator::skip_to..." << std::endl;
    bit_vector::iterator it = bv.begin();

    srand(essentials::get_random_seed());
    for (int i = 0; i != 5; ++i) {
        uint64_t pos = rand() % num_bits;
        it.skip_to(pos);
        for (uint64_t j = pos; j != num_bits; ++j, ++it) {
            REQUIRE_MESSAGE(*it == vec_bools[j], "required " << vec_bools[j] << " but got " << *it);
        }
    }

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("bit_vector::iterator::take") {
    const uint64_t max_int = test::get_random_uint();
    std::cout << "max_int = " << max_int << std::endl;
    std::vector<uint64_t> seq = test::get_sequence(sequence_length, max_int);
    const uint64_t width = max_int != 0 ? std::ceil(std::log2(max_int + 1)) : 1;
    std::cout << "width = " << width << std::endl;
    bit_vector::builder bv_builder(sequence_length * width);
    for (uint64_t i = 0; i != sequence_length; ++i) {
        bv_builder.set_bits(i * width, seq[i], width);
    }
    bit_vector bv;
    bv_builder.build(bv);
    std::cout << "checking correctness of bit_vector::iterator::take..." << std::endl;
    for (uint64_t i = 0; i != sequence_length; ++i) {
        bit_vector::iterator it = bv.get_iterator_at(i * width);
        uint64_t got = it.take(width);
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, i << "/" << sequence_length << ": got " << got
                                           << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("bit_vector::iterator::next") {
    const uint64_t width = test::get_random_uint(100) + 1;  // at least 1
    std::cout << "width = " << width << std::endl;
    bit_vector::builder bv_builder(sequence_length * width);
    for (uint64_t i = 0; i != sequence_length; ++i) {
        bv_builder.set(i * width);  // ones are spaced apart by width
    }
    bit_vector bv;
    bv_builder.build(bv);
    std::cout << "checking correctness of bit_vector::iterator::next..." << std::endl;

    bit_vector::iterator it = bv.begin();
    for (uint64_t i = 0; i != sequence_length; ++i) {
        uint64_t got = it.next();
        uint64_t expected = i * width;
        REQUIRE(got + 1 == it.position());
        REQUIRE_MESSAGE(got == expected, i << "/" << sequence_length << ": got " << got
                                           << " but expected " << expected);
    }

    for (uint64_t i = 0; i != sequence_length - 1; ++i) {
        bit_vector::iterator it = bv.get_iterator_at(i * width + 1);
        uint64_t got = it.next();
        uint64_t expected = (i + 1) * width;
        REQUIRE_MESSAGE(got == expected, i << "/" << sequence_length << ": got " << got
                                           << " but expected " << expected);
    }

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("bit_vector::iterator::prev") {
    const uint64_t width = test::get_random_uint(100) + 1;  // at least 1
    std::cout << "width = " << width << std::endl;
    bit_vector::builder bv_builder(sequence_length * width);
    for (uint64_t i = 0; i != sequence_length; ++i) {
        bv_builder.set(i * width);  // ones are spaced apart by width
    }
    bit_vector bv;
    bv_builder.build(bv);
    std::cout << "checking correctness of bit_vector::iterator::prev..." << std::endl;

    bit_vector::iterator it = bv.get_iterator_at(bv.num_bits() - 1);
    uint64_t pos = it.position();
    for (uint64_t i = 0; i != sequence_length; ++i) {
        uint64_t got = it.prev(pos);
        uint64_t expected = ((sequence_length - i) - 1) * width;
        REQUIRE_MESSAGE(got == expected, i << "/" << sequence_length << ": got " << got
                                           << " but expected " << expected);
        pos = got - 1;
    }

    for (uint64_t i = 1; i != sequence_length; ++i) {
        bit_vector::iterator it = bv.get_iterator_at(i * width - 1);
        uint64_t got = it.prev(it.position());
        uint64_t expected = (i - 1) * width;
        REQUIRE_MESSAGE(got == expected, i << "/" << sequence_length << ": got " << got
                                           << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("save_load_and_swap") {
    const uint64_t width = test::get_random_uint(100) + 1;  // at least 1
    std::cout << "width = " << width << std::endl;
    bit_vector::builder bv_builder(sequence_length * width);
    for (uint64_t i = 0; i != sequence_length; ++i) {
        bv_builder.set(i * width);  // ones are spaced apart by width
    }
    bit_vector bv;
    bv_builder.build(bv);
    const std::string output_filename("bv.bin");
    uint64_t num_saved_bytes = essentials::save(bv, output_filename.c_str());
    std::cout << "num_saved_bytes = " << num_saved_bytes << std::endl;
    bit_vector bv_loaded;
    uint64_t num_loaded_bytes = essentials::load(bv_loaded, output_filename.c_str());
    std::cout << "num_loaded_bytes = " << num_loaded_bytes << std::endl;
    std::remove(output_filename.c_str());
    REQUIRE(num_saved_bytes == num_loaded_bytes);
    std::cout << "checking correctness of bit_vector::iterator::prev..." << std::endl;
    bit_vector other;
    bv_loaded.swap(other);
    REQUIRE(other.num_bits() == sequence_length * width);
    for (uint64_t i = 0; i != other.num_bits(); ++i) {
        if (i % width == 0) {
            REQUIRE_MESSAGE(other.get(i) == true, i << "/" << other.num_bits() << ": got "
                                                    << int(other.get(i)) << " but expected 1");
        } else {
            REQUIRE_MESSAGE(other.get(i) == false, i << "/" << other.num_bits() << ": got "
                                                     << int(other.get(i)) << " but expected 0");
        }
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}
