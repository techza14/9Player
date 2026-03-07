#include "common.hpp"
#include "include/endpoints_sequence.hpp"

constexpr uint64_t sequence_length = 10000;

std::vector<uint64_t> get_sequence(const uint64_t sequence_length) {
    std::vector<uint64_t> s = test::get_sequence(sequence_length);
    std::vector<uint64_t> seq;
    seq.reserve(s.size() + 1);
    uint64_t sum = 0;
    for (uint64_t i = 0; i != s.size(); ++i) {
        seq.push_back(sum);
        sum += s[i] + (s[i] == 0);  // +1 so that all values are distinct
    }
    seq.push_back(sum);
    assert(std::adjacent_find(seq.begin(), seq.end()) == seq.end());  // must be all distinct
    return seq;
}

endpoints_sequence<> encode(std::vector<uint64_t> const& seq) {
    endpoints_sequence<> es;
    assert(seq.front() == 0);
    es.encode(seq.begin(), seq.size(), seq.back());
    REQUIRE(es.size() == seq.size());
    std::cout << "es.size() = " << es.size() << '\n';
    std::cout << "es.back() = " << es.back() << '\n';
    std::cout << "measured bits/int = " << (8.0 * es.num_bytes()) / es.size() << std::endl;
    return es;
}

TEST_CASE("access") {
    std::vector<uint64_t> seq = get_sequence(sequence_length);
    auto es = encode(seq);
    std::cout << "checking correctness of random access..." << std::endl;
    for (uint64_t i = 0; i != es.size(); ++i) {
        uint64_t got = es.access(i);  // get the integer at position i
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/" << es.size()
                                                << " but expected " << expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("iterator::next") {
    std::vector<uint64_t> seq = get_sequence(sequence_length);
    auto es = encode(seq);

    std::cout << "checking correctness of iterator..." << std::endl;

    auto it = es.begin();
    for (uint64_t i = 0; i != es.size(); ++i, it.next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/" << es.size()
                                                << " but expected " << expected);
        REQUIRE(it.position() == i);
        REQUIRE(it.has_next() == true);
    }
    REQUIRE(it.position() == es.size());
    REQUIRE(it.has_next() == false);

    uint64_t i = 0;
    it = es.begin();
    while (it.has_next()) {
        uint64_t got = it.value();
        uint64_t expected = seq[i];
        REQUIRE_MESSAGE(got == expected, "got " << got << " at position " << i << "/" << es.size()
                                                << " but expected " << expected);
        REQUIRE(it.position() == i);
        REQUIRE(it.has_next() == true);
        it.next();
        ++i;
    }
    REQUIRE(i == es.size());
    REQUIRE(it.position() == es.size());
    REQUIRE(it.has_next() == false);

    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("next_geq") {
    std::vector<uint64_t> seq = get_sequence(sequence_length);
    auto es = encode(seq);
    // test::print(seq);

    std::cout << "checking correctness of next_geq..." << std::endl;

    for (uint64_t i = 0; i != es.size() - 1; ++i) {
        auto x = seq[i];
        /* since x is in the sequence, next_geq must return (i,x) */

        auto p = es.next_geq(x);
        uint64_t pos = p.pos;
        uint64_t got = p.val;
        uint64_t expected = seq[i];

        // std::cout << "x=" << x << "; pos=" << pos << "; got=" << got << std::endl;

        bool good = (got == expected) && (pos == i);
        REQUIRE_MESSAGE(good, "got " << got << " at position " << pos << "/" << seq.size()
                                     << " but expected " << expected << " at position " << i);
    }
    std::cout << "EVERYTHING OK!" << std::endl;

    std::cout << "checking correctness of next_geq..." << std::endl;
    for (uint64_t i = 0; i != 10000; ++i) {
        uint64_t x = seq[rand() % seq.size()] + (i % 2 == 0 ? 3 : -3);  // get some value
        if (x >= seq.back()) x = seq.back() - 1;  // lower bound must be x < back() for assumption

        auto p = es.next_geq(x);
        uint64_t pos = p.pos;
        uint64_t got = p.val;

        // std::cout << "x=" << x << "; pos=" << pos << "; got=" << got << std::endl;

        auto it = std::lower_bound(seq.begin(), seq.end(), x);
        assert(it != seq.end());
        uint64_t expected = *it;
        uint64_t pos_expected = std::distance(seq.begin(), it);
        bool good = (got == expected) && (pos == pos_expected);
        REQUIRE_MESSAGE(good, "got " << got << " at position " << pos << "/" << seq.size()
                                     << " but expected " << expected << " at position "
                                     << pos_expected);
    }
    std::cout << "EVERYTHING OK!" << std::endl;
}

TEST_CASE("small_next_geq") {
    std::cout << "checking correctness of next_geq over a small sequence..." << std::endl;

    std::vector<uint64_t> seq{0, 3, 5, 6, 9, 13, 23, 31};
    auto es = encode(seq);

    auto p = es.next_geq(0);
    REQUIRE(p.pos == 0);
    REQUIRE(p.val == 0);

    p = es.next_geq(2);
    REQUIRE(p.pos == 1);
    REQUIRE(p.val == 3);

    p = es.next_geq(4);
    REQUIRE(p.pos == 2);
    REQUIRE(p.val == 5);

    p = es.next_geq(6);
    REQUIRE(p.pos == 3);
    REQUIRE(p.val == 6);

    p = es.next_geq(23);
    REQUIRE(p.pos == 6);
    REQUIRE(p.val == 23);

    p = es.next_geq(28);
    REQUIRE(p.pos == 7);
    REQUIRE(p.val == 31);

    p = es.next_geq(30);
    REQUIRE(p.pos == 7);
    REQUIRE(p.val == 31);
}

TEST_CASE("locate") {
    std::vector<uint64_t> seq = get_sequence(sequence_length);
    auto es = encode(seq);
    // test::print(seq);

    std::cout << "checking correctness of locate..." << std::endl;

    for (uint64_t i = 0; i != seq.size() - 1; ++i)  //
    {
        auto x = seq[i];

        /* since x is in the sequence, p.first.pos  must be i */
        auto p = es.locate(x);
        uint64_t lo_pos = p.first.pos;
        uint64_t lo_val = p.first.val;
        uint64_t hi_pos = p.second.pos;
        uint64_t hi_val = p.second.val;

        uint64_t expected_lo_pos = i;
        uint64_t expected_lo_val = seq[i];
        uint64_t expected_hi_pos = i + 1;
        uint64_t expected_hi_val = seq[i + 1];

        // std::cout << "x = " << x << " lo_pos = " << lo_pos << " lo_val = " << lo_val
        //           << " hi_pos = " << hi_pos << " hi_val = " << hi_val << std::endl;

        bool good = (lo_val == expected_lo_val) && (lo_pos == expected_lo_pos);
        REQUIRE_MESSAGE(good, "got " << lo_val << " at position " << lo_pos << "/" << seq.size()
                                     << " but expected " << expected_lo_val << " at position "
                                     << expected_lo_pos);
        good = (hi_val == expected_hi_val) && (hi_pos == expected_hi_pos);
        REQUIRE_MESSAGE(good, "got " << hi_val << " at position " << hi_pos << "/" << seq.size()
                                     << " but expected " << expected_hi_val << " at position "
                                     << expected_hi_pos);
    }
    std::cout << "EVERYTHING OK!" << std::endl;

    std::cout << "checking correctness of locate..." << std::endl;

    for (uint64_t i = 0; i != 10000; ++i)  //
    {
        uint64_t x = seq[rand() % es.size()] + (i % 2 == 0 ? 3 : -3);  // get some value value
        if (x >= seq.back()) x = seq.back() - 1;  // lower bound must be x < back() for assumption

        auto p = es.locate(x);
        uint64_t lo_pos = p.first.pos;
        uint64_t lo_val = p.first.val;
        uint64_t hi_pos = p.second.pos;
        uint64_t hi_val = p.second.val;

        // std::cout << "x = " << x << " lo_pos = " << lo_pos << " lo_val = " << lo_val
        //           << " hi_pos = " << hi_pos << " hi_val = " << hi_val << std::endl;

        auto it = std::upper_bound(seq.begin(), seq.end(), x) - 1;
        uint64_t expected_lo_pos = std::distance(seq.begin(), it);
        assert(expected_lo_pos != es.size() - 1);
        uint64_t expected_lo_val = *it;
        uint64_t expected_hi_pos = expected_lo_pos + 1;
        uint64_t expected_hi_val = *(it + 1);

        bool good = (lo_val == expected_lo_val) && (lo_pos == expected_lo_pos);
        REQUIRE_MESSAGE(good, "got " << lo_val << " at position " << lo_pos << "/" << seq.size()
                                     << " but expected " << expected_lo_val << " at position "
                                     << expected_lo_pos);
        good = (hi_val == expected_hi_val) && (hi_pos == expected_hi_pos);
        REQUIRE_MESSAGE(good, "got " << hi_val << " at position " << hi_pos << "/" << seq.size()
                                     << " but expected " << expected_hi_val << " at position "
                                     << expected_hi_pos);
    }

    std::cout << "EVERYTHING OK!" << std::endl;
}
