#pragma once

#include "bit_vector.hpp"

namespace bits {

/*
    The following class implements an index on top of an uncompressed
    bitvector B[0..n) to support rank queries.

    The first constant-time solution was described by Jacobson in his
    Ph.D. thesis

        Guy Joseph Jacobson. 1988. Succinct static data structures.
        Ph.D. Dissertation. Carnegie Mellon University

    but a practical implementation is due to Vigna

        Sebastiano Vigna. 2008. Broadword implementation of rank/select queries.
        In Proceedings of the 7th International Workshop on Experimental
        and Efficient Algorithms (WEA). Springer, pages 154–168.

    The bitvector B is split into blocks of 512 bits.
    A first level stores the cumulative sum of the population count of each
    block. Each cumulative sum is stored in a 64-bit integer, hence the
    redundancy of the first level is 64/512 = 1/8, or 12.5%.
    A second level stores the cumulative sum of the population count of each
    64-bit word within a block. To answer a rank(i) query, we need to know the
    cumulative population count "to the left" of the index i, hence we
    just need to keep the cumulative counts for 512/64-1=7 blocks (the cumulative
    count to the left of the first block is always 0). Now, the maximum
    cumulative sum is clearly 64*7=448 which fits into 9 bits, thus we
    can represent the array of 7 cumulative counts using just 7*9 = 63 bits,
    i.e. with a single 64-bit integer. The redundancy of the second level
    is then 64/512 = 1/8 bits. The two levels are stored interleaved in a
    single array of 64-bit integers.

    Summing up the redundancy of the two levels, the total redundancy is
    therefore 1/4 bits for each of the original bits of B, or 25%.

    A rank query is aswered in O(1) by accessing two population
    counts and calculating one in a 64-bit word.
*/

struct rank9 {
    rank9() {}

    void build(bit_vector const& B) {
        std::vector<uint64_t> const& data = B.data();
        std::vector<uint64_t> block_rank_pairs;
        uint64_t next_rank = 0;
        uint64_t cur_subrank = 0;
        uint64_t subranks = 0;
        block_rank_pairs.push_back(0);
        for (uint64_t i = 0; i < data.size(); ++i) {
            uint64_t word_pop = util::popcount(data[i]);
            uint64_t shift = i % block_size;
            if (shift) {
                subranks <<= 9;
                subranks |= cur_subrank;
            }
            next_rank += word_pop;
            cur_subrank += word_pop;

            if (shift == block_size - 1) {
                block_rank_pairs.push_back(subranks);
                block_rank_pairs.push_back(next_rank);
                subranks = 0;
                cur_subrank = 0;
            }
        }
        uint64_t left = block_size - data.size() % block_size;
        for (uint64_t i = 0; i < left; ++i) {
            subranks <<= 9;
            subranks |= cur_subrank;
        }
        block_rank_pairs.push_back(subranks);

        if (data.size() % block_size) {
            block_rank_pairs.push_back(next_rank);
            block_rank_pairs.push_back(0);
        }

        m_block_rank_pairs.swap(block_rank_pairs);
    }

    inline uint64_t num_ones() const { return *(m_block_rank_pairs.end() - 2); }

    /*
        Return the number of ones in B[0..i).
    */
    inline uint64_t rank1(bit_vector const& B, uint64_t i) const {
        assert(i <= B.num_bits());
        if (i == B.num_bits()) return num_ones();
        uint64_t sub_block = i >> 6;
        uint64_t r = sub_block_rank(sub_block);
        uint64_t sub_left = i % 64;
        if (sub_left) {
            std::vector<uint64_t> const& data = B.data();
            r += util::popcount(data[sub_block] << (64 - sub_left));
        }
        return r;
    }

    /*
        Return the number of zeros in B[0..i).
    */
    inline uint64_t rank0(bit_vector const& B, uint64_t i) const {
        assert(i <= B.num_bits());
        return i - rank1(B, i);
    }

    uint64_t num_bytes() const { return essentials::vec_bytes(m_block_rank_pairs); }

    void swap(rank9& other) { m_block_rank_pairs.swap(other.m_block_rank_pairs); }

    template <typename Visitor>
    void visit(Visitor& visitor) {
        visit_impl(visitor, *this);
    }

    template <typename Visitor>
    void visit(Visitor& visitor) const {
        visit_impl(visitor, *this);
    }

private:
    template <typename Visitor, typename T>
    static void visit_impl(Visitor& visitor, T&& t) {
        visitor.visit(t.m_block_rank_pairs);
    }

    inline uint64_t block_rank(uint64_t block) const { return m_block_rank_pairs[block * 2]; }

    inline uint64_t sub_block_rank(uint64_t sub_block) const {
        uint64_t r = 0;
        uint64_t block = sub_block / block_size;
        r += block_rank(block);
        uint64_t left = sub_block % block_size;
        r += sub_block_ranks(block) >> ((7 - left) * 9) & 0x1FF;
        return r;
    }

    inline uint64_t sub_block_ranks(uint64_t block) const {
        return m_block_rank_pairs[block * 2 + 1];
    }

    static const uint64_t block_size = 8;  // in 64bit words
    std::vector<uint64_t> m_block_rank_pairs;
};

}  // namespace bits