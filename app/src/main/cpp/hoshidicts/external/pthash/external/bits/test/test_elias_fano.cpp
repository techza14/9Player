#include "common.hpp"
#include "include/elias_fano.hpp"

constexpr uint64_t sequence_length = 10000;

template <bool index_zeros, bool encode_prefix_sum>
elias_fano<index_zeros, encode_prefix_sum> encode_with_elias_fano(
    std::vector<uint64_t> const& seq) {
    std::cout << "encoding seq with elias_fano<index_zeros=" << index_zeros
              << ", encode_prefix_sum=" << encode_prefix_sum << ">..." << std::endl;
    elias_fano<index_zeros, encode_prefix_sum> ef;
    ef.encode(seq.begin(), seq.size()  //
                                       // ,
                                       // seq.back() + 10000000 /* provide a custom universe */
    );
    REQUIRE(ef.size() == seq.size() + encode_prefix_sum);
    std::cout << "ef.size() = " << ef.size() << '\n';
    std::cout << "ef.back() = " << ef.back() << '\n';
    std::cout << "measured bits/int = " << (8.0 * ef.num_bytes()) / ef.size() << std::endl;
    return ef;
}

TEST_CASE("access") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);
    std::cout << "checking correctness of random access..." << std::endl;
    for (uint64_t i = 0; i != sequence_length; ++i) {
        uint64_t got = ef.access(i);  // get the integer at position i
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("iterator::next") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);

    std::cout << "checking correctness of iterator..." << std::endl;

    auto it = ef.begin();
    for (uint64_t i = 0; i != sequence_length; ++i, it.next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
        REQUIRE(it.position() == i);
        REQUIRE(it.has_next() == true);
    }
    REQUIRE(it.position() == sequence_length);
    REQUIRE(it.has_next() == false);

    uint64_t i = 0;
    it = ef.begin();
    while (it.has_next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
        REQUIRE(it.position() == i);
        REQUIRE(it.has_next() == true);
        it.next();
        ++i;
    }
    REQUIRE(i == sequence_length);
    REQUIRE(it.position() == sequence_length);
    REQUIRE(it.has_next() == false);

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("get_iterator_at with iterator::next") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);

    std::cout << "checking correctness of iterator..." << std::endl;

    uint64_t pos = test::get_random_uint(sequence_length);
    std::cout << "pos = " << pos << std::endl;
    assert(pos <= sequence_length);
    auto it = ef.get_iterator_at(pos);
    for (uint64_t i = pos; i != sequence_length; ++i, it.next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
        REQUIRE(it.position() == i);
        REQUIRE(it.has_next() == true);
    }
    REQUIRE(it.position() == sequence_length);
    REQUIRE(it.has_next() == false);

    uint64_t i = pos;
    it = ef.get_iterator_at(pos);
    while (it.has_next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
        REQUIRE(it.position() == i);
        REQUIRE(it.has_next() == true);
        it.next();
        ++i;
    }
    REQUIRE(i == sequence_length);
    REQUIRE(it.position() == sequence_length);
    REQUIRE(it.has_next() == false);

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("get_iterator_at with mix of iterator::next and iterator::prev_value") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);

    std::cout << "checking correctness of iterator..." << std::endl;

    uint64_t pos = test::get_random_uint(sequence_length);
    if (pos == 0) pos = 1;
    std::cout << "pos = " << pos << std::endl;
    assert(pos <= sequence_length);
    auto it = ef.get_iterator_at(pos);
    for (uint64_t i = pos; i != sequence_length; ++i, it.next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
        got = it.prev_value();
        expected = seq[i - 1];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i - 1 << "/"
                                                << sequence_length << " but expected " << expected);
    }

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("next_geq") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);
    std::cout << "checking correctness of next_geq..." << std::endl;
    uint64_t i = 0;
    for (auto x : seq) {
        /* since x is in the sequence, next_geq must return (i,x) */

        auto p = ef.next_geq(x);
        uint64_t pos = p.pos;
        uint64_t got = p.val;
        uint64_t expected = seq[i];

        // std::cout << "x=" << x << "; pos=" << pos << "; got=" << got << std::endl;

        /* if we have some repeated values, next_neq will return the leftmost occurrence */
        if (i > 0 and seq[i] == seq[i - 1]) {
            ++i;
            continue;
        }

        bool good = (got == expected) && (pos == i);
        REQUIRE_MESSAGE(good, "got " << got << " at position " << pos << "/" << sequence_length
                                     << " but expected " << expected << " at position " << i);
        ++i;
    }
    std::cout << "EVERYTHING OK!" << std::endl;

    std::cout << "checking correctness of next_geq..." << std::endl;
    for (i = 0; i != 10000; ++i) {
        uint64_t x = seq[rand() % sequence_length] + (i % 2 == 0 ? 3 : -3);  // get some value

        // auto [pos, got] = ef.next_geq(x); // can't use structural binding with doctest...
        auto p = ef.next_geq(x);
        uint64_t pos = p.pos;
        uint64_t got = p.val;

        // std::cout << "x=" << x << "; pos=" << pos << "; got=" << got << std::endl;
        auto it = std::lower_bound(seq.begin(), seq.end(), x);
        if (it != seq.end()) {
            uint64_t expected = *it;
            uint64_t pos_expected = std::distance(seq.begin(), it);
            bool good = (got == expected) && (pos == pos_expected);
            REQUIRE_MESSAGE(good, "got " << got << " at position " << pos << "/" << sequence_length
                                         << " but expected " << expected << " at position "
                                         << pos_expected);
        } else {
            assert(x > ef.back());
            uint64_t expected = ef.back();
            bool good = (got == expected) && (pos == seq.size() - 1);
            REQUIRE_MESSAGE(good, "got " << got << " at position " << pos << "/" << sequence_length
                                         << " but expected " << expected << " at position "
                                         << seq.size() - 1);
        }
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("small_next_geq") {
    std::vector<uint64_t> seq{1, 1, 1, 3, 3, 5, 6, 6, 6, 9, 13, 23, 23, 23};
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);

    auto p = ef.next_geq(1);
    uint64_t pos = p.pos;
    uint64_t got = p.val;
    REQUIRE(pos == 0);  // leftmost
    REQUIRE(got == 1);

    p = ef.next_geq(3);
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == 3);  // leftmost
    REQUIRE(got == 3);

    p = ef.next_geq(6);
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == 6);  // leftmost
    REQUIRE(got == 6);

    p = ef.next_geq(23);
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == 11);  // leftmost
    REQUIRE(got == 23);

    p = ef.next_geq(100);  // saturate
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == seq.size() - 1);
    REQUIRE(got == seq.back());
}

TEST_CASE("small_prev_leq") {
    std::vector<uint64_t> seq{1, 1, 1, 3, 3, 5, 6, 6, 6, 9, 13, 23, 23, 23};
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);

    auto p = ef.prev_leq(0);
    uint64_t pos = p.pos;
    uint64_t got = p.val;
    REQUIRE(pos == uint64_t(-1));  // undefined since 0 < 1
    REQUIRE(got == uint64_t(-1));

    p = ef.prev_leq(1);
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == 2);  // rightmost
    REQUIRE(got == 1);

    p = ef.prev_leq(3);
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == 4);  // rightmost
    REQUIRE(got == 3);

    p = ef.prev_leq(6);
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == 8);  // rightmost
    REQUIRE(got == 6);

    p = ef.prev_leq(23);
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == seq.size() - 1);  // rightmost
    REQUIRE(got == 23);

    p = ef.prev_leq(100);  // saturate
    pos = p.pos;
    got = p.val;
    REQUIRE(pos == seq.size() - 1);
    REQUIRE(got == 23);
}

TEST_CASE("prev_leq") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);
    std::cout << "checking correctness of prev_leq..." << std::endl;
    uint64_t i = 0;
    for (auto x : seq) {
        /* since x is in the sequence, prev_leq must return i */
        auto p = ef.prev_leq(x);
        uint64_t pos = p.pos;
        uint64_t got = p.val;
        uint64_t expected = seq[i];

        // std::cout << "x=" << x << "; pos=" << pos << "; got=" << got << std::endl;

        /* if we have some repeated values, prev_leq will return the rightmost occurrence */
        if (pos > 0 and seq[pos] == seq[pos - 1]) {
            i = pos + 1;
            continue;
        }
        bool good = (got == expected) && (pos == i);
        REQUIRE_MESSAGE(good, "got " << got << " at position " << pos << "/" << sequence_length
                                     << " but expected " << expected << " at position " << i);
        ++i;
    }
    std::cout << "EVERYTHING OK!" << std::endl;

    std::cout << "checking correctness of prev_leq..." << std::endl;
    const uint64_t front = ef.access(0);
    for (i = 0; i != 10000; ++i) {
        uint64_t x = seq[rand() % sequence_length] + (i % 2 == 0 ? 3 : -3);  // get some value
        auto p = ef.prev_leq(x);
        uint64_t pos = p.pos;
        uint64_t got = p.val;
        if (x < front) {
            REQUIRE_MESSAGE(pos == uint64_t(-1),
                            "expected pos " << uint64_t(-1) << " but got pos = " << pos);
            continue;
        }
        // std::cout << "x=" << x << "; pos=" << pos << "; got=" << got << std::endl;
        auto it = std::upper_bound(seq.begin(), seq.end(), x) - 1;
        uint64_t expected = *it;
        uint64_t pos_expected = std::distance(seq.begin(), it);
        bool good = (got == expected) && (pos == pos_expected);
        REQUIRE_MESSAGE(good, "got " << got << " at position " << pos << "/" << sequence_length
                                     << " but expected " << expected << " at position "
                                     << pos_expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("small_locate") {
    std::vector<uint64_t> seq{1, 1, 1, 3, 3, 5, 6, 6, 6, 9, 13, 23, 23, 23};
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);

    auto p = ef.locate(0);
    REQUIRE(p.first.pos == uint64_t(-1));  // undefined since 0 < 1
    REQUIRE(p.first.val == uint64_t(-1));
    REQUIRE(p.second.pos == 0);
    REQUIRE(p.second.val == 1);

    p = ef.locate(1);
    REQUIRE(p.first.pos == 2);  // rightmost
    REQUIRE(p.first.val == 1);
    REQUIRE(p.second.pos == 3);  // leftmost
    REQUIRE(p.second.val == 3);

    p = ef.locate(3);
    REQUIRE(p.first.pos == 4);  // rightmost
    REQUIRE(p.first.val == 3);
    REQUIRE(p.second.pos == 5);
    REQUIRE(p.second.val == 5);

    p = ef.locate(6);
    REQUIRE(p.first.pos == 8);  // rightmost
    REQUIRE(p.first.val == 6);
    REQUIRE(p.second.pos == 9);
    REQUIRE(p.second.val == 9);

    p = ef.locate(23);
    REQUIRE(p.first.pos == seq.size() - 1);  // rightmost
    REQUIRE(p.first.val == 23);
    REQUIRE(p.second.pos == uint64_t(-1));  // undefined since there is no integer that is > back()
    REQUIRE(p.second.val == uint64_t(-1));

    p = ef.locate(100);  // saturate
    REQUIRE(p.first.pos == seq.size() - 1);
    REQUIRE(p.first.val == 23);
    REQUIRE(p.second.pos == uint64_t(-1));  // undefined since there is no integer that is > back()
    REQUIRE(p.second.val == uint64_t(-1));
}

TEST_CASE("locate") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);

    std::cout << "checking correctness of locate..." << std::endl;

    uint64_t i = 0;
    for (auto x : seq)  //
    {
        /* since x is in the sequence, p.first.pos  must be i */
        auto p = ef.locate(x);
        uint64_t lo_pos = p.first.pos;
        uint64_t lo_val = p.first.val;
        uint64_t hi_pos = p.second.pos;
        uint64_t hi_val = p.second.val;
        uint64_t expected_lo_pos = i;
        uint64_t expected_lo_val = seq[i];
        uint64_t expected_hi_pos = uint64_t(-1);
        uint64_t expected_hi_val = uint64_t(-1);
        if (expected_lo_pos != ef.size() - 1) {
            expected_hi_pos = i + 1;
            expected_hi_val = seq[i + 1];
        }

        /* if we have some repeated values, lo_pos will be the rightmost occurrence */
        if (lo_pos > 0 and seq[lo_pos] == seq[lo_pos - 1]) {
            i = lo_pos + 1;
            continue;
        }

        bool good = (lo_val == expected_lo_val) && (lo_pos == expected_lo_pos);
        REQUIRE_MESSAGE(good, "got " << lo_val << " at position " << lo_pos << "/"
                                     << sequence_length << " but expected " << expected_lo_val
                                     << " at position " << expected_lo_pos);
        good = (hi_val == expected_hi_val) && (hi_pos == expected_hi_pos);
        REQUIRE_MESSAGE(good, "got " << hi_val << " at position " << hi_pos << "/"
                                     << sequence_length << " but expected " << expected_hi_val
                                     << " at position " << expected_hi_pos);

        ++i;
    }
    std::cout << "EVERYTHING OK!" << std::endl;

    std::cout << "checking correctness of locate..." << std::endl;

    const uint64_t front = ef.access(0);
    for (i = 0; i != 10000; ++i)  //
    {
        const uint64_t x = seq[rand() % sequence_length] + (i % 2 == 0 ? 3 : -3);  // get some value
        auto p = ef.locate(x);
        uint64_t lo_pos = p.first.pos;
        uint64_t lo_val = p.first.val;
        uint64_t hi_pos = p.second.pos;
        uint64_t hi_val = p.second.val;

        uint64_t expected_lo_pos = uint64_t(-1);
        uint64_t expected_lo_val = uint64_t(-1);
        uint64_t expected_hi_pos = uint64_t(-1);
        uint64_t expected_hi_val = uint64_t(-1);

        if (x < front) {
            expected_hi_pos = 0;
            expected_hi_val = front;
        } else {
            auto it = std::upper_bound(seq.begin(), seq.end(), x) - 1;
            expected_lo_pos = std::distance(seq.begin(), it);
            expected_lo_val = *it;
            if (expected_lo_pos != ef.size() - 1) {
                expected_hi_pos = expected_lo_pos + 1;
                expected_hi_val = *(it + 1);
            }
        }

        bool good = (lo_val == expected_lo_val) && (lo_pos == expected_lo_pos);
        REQUIRE_MESSAGE(good, "got " << lo_val << " at position " << lo_pos << "/"
                                     << sequence_length << " but expected " << expected_lo_val
                                     << " at position " << expected_lo_pos);
        good = (hi_val == expected_hi_val) && (hi_pos == expected_hi_pos);
        REQUIRE_MESSAGE(good, "got " << hi_val << " at position " << hi_pos << "/"
                                     << sequence_length << " but expected " << expected_hi_val
                                     << " at position " << expected_hi_pos);
    }

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("diff") {
    std::vector<uint64_t> seq = test::get_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = true;
    auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);
    assert(ef.size() == seq.size() + 1);
    std::cout << "ef.size() = " << ef.size() << '\n';
    std::cout << "ef.back() = " << ef.back() << '\n';
    std::cout << "measured bits/int = " << (8.0 * ef.num_bytes()) / ef.size() << std::endl;
    std::cout << "checking correctness of diff..." << std::endl;
    uint64_t i = 0;
    for (auto x : seq) {
        uint64_t got = ef.diff(i);  // get the integer at position i
        uint64_t expected = x;
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
        ++i;
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("save_load_and_swap") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    using ef_type = elias_fano<index_zeros, encode_prefix_sum>;
    ef_type ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);
    const std::string output_filename("ef.bin");
    uint64_t num_saved_bytes = essentials::save(ef, output_filename.c_str());
    std::cout << "num_saved_bytes = " << num_saved_bytes << std::endl;
    ef_type ef_loaded;
    uint64_t num_loaded_bytes = essentials::load(ef_loaded, output_filename.c_str());
    std::cout << "num_loaded_bytes = " << num_loaded_bytes << std::endl;
    std::remove(output_filename.c_str());
    REQUIRE(num_saved_bytes == num_loaded_bytes);
    std::cout << "checking correctness of iterator..." << std::endl;
    ef_type other;
    ef_loaded.swap(other);
    auto it = other.begin();
    for (uint64_t i = 0; i != sequence_length; ++i, it.next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("build_from_compact_vector_iterator") {
    std::vector<uint64_t> seq = test::get_sorted_sequence(sequence_length);
    constexpr bool index_zeros = true;
    constexpr bool encode_prefix_sum = false;
    std::cout << "encoding them with elias_fano<index_zeros=" << index_zeros
              << ", encode_prefix_sum=" << encode_prefix_sum << ">..." << std::endl;
    elias_fano<index_zeros, encode_prefix_sum> ef;
    {
        compact_vector cv;
        const uint64_t max = seq.back();
        uint64_t width = max == 0 ? 1 : std::ceil(std::log2(max + 1));
        cv.build(seq.begin(), seq.size(), width);
        ef.encode(cv.begin(), cv.size());
        assert(ef.size() == seq.size());
        std::cout << "cv.width() = " << cv.width() << '\n';
        std::cout << "ef.size() = " << ef.size() << '\n';
        std::cout << "ef.back() = " << ef.back() << '\n';
        std::cout << "measured bits/int = " << (8.0 * ef.num_bytes()) / ef.size() << std::endl;
    }
    auto it = ef.begin();
    for (uint64_t i = 0; i != sequence_length; ++i, it.next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/"
                                                << sequence_length << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}
