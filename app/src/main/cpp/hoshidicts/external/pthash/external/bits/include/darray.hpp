#pragma once

#include "util.hpp"
#include "bit_vector.hpp"

namespace bits {

/*
    The following class implements an index on top of an uncompressed
    bitvector B[0..n) to support select queries.

    The solution implemented here is described in

        Okanohara, Daisuke, and Kunihiko Sadakane. 2007.
        Practical entropy-compressed rank/select dictionary.
        In Proceedings of the 9-th Workshop on Algorithm Engineering
        and Experiments (ALENEX), pages 60-70.

    under the name "darray" (short for "dense" array).

    The bitvector is split into variable-length super-blocks, each
    containing L ones (except for, possibly, the last super-block).
    A super-block is said to be "sparse" if its length is >= L2;
    otherwise it is said "dense". Sparse super-blocks are represented
    verbatim, i.e., the positions of the L ones are coded using 64-bit
    integers. A dense super-block, instead, is sparsified: we keep
    one position every L3 positions. The positions are coded relatively
    to the beginning of each super-block, hence using log2(L2) bits per
    position.

    A select query first checks if the super-block is sparse: if it is,
    then the query is solved in O(1). If the super-block is dense instead,
    the corresponding block is accessed and a linear scan is performed
    for a worst-case cost of O(L2/L3).

    This implementation uses, by default:

    L =  1,024 (block_size)
    L2 = 65,536 (so that each position in a dense block can be coded
                 using 16-bit integers)
    L3 = 32 (subblock_size)
*/

template <                       //
    typename WordGetter,         //
    uint64_t block_size = 1024,  //
    uint64_t subblock_size = 32  //
    >
struct darray {
    darray() : m_positions(0) {}

    void build(bit_vector const& B) {
        std::vector<uint64_t> const& data = B.data();
        std::vector<uint64_t> cur_block_positions;
        std::vector<int64_t> block_inventory;
        std::vector<uint16_t> subblock_inventory;
        std::vector<uint64_t> overflow_positions;

        for (uint64_t word_idx = 0; word_idx < data.size(); ++word_idx) {
            uint64_t cur_pos = word_idx << 6;
            uint64_t cur_word = WordGetter()(data, word_idx);
            uint64_t l;
            while (util::lsbll(cur_word, l)) {
                cur_pos += l;
                cur_word >>= l;
                if (cur_pos >= B.num_bits()) break;

                cur_block_positions.push_back(cur_pos);

                if (cur_block_positions.size() == block_size) {
                    flush_cur_block(cur_block_positions, block_inventory, subblock_inventory,
                                    overflow_positions);
                }

                // can't do >>= l + 1, can be 64
                cur_word >>= 1;
                cur_pos += 1;
                m_positions += 1;
            }
        }
        if (cur_block_positions.size()) {
            flush_cur_block(cur_block_positions, block_inventory, subblock_inventory,
                            overflow_positions);
        }
        m_block_inventory.swap(block_inventory);
        m_subblock_inventory.swap(subblock_inventory);
        m_overflow_positions.swap(overflow_positions);
    }

    /*
        Return the position of the i-th bit set in B,
        for any 0 <= i < num_positions();
    */
    inline uint64_t select(bit_vector const& B, uint64_t i) const {
        assert(i < num_positions());
        uint64_t block = i / block_size;
        int64_t block_pos = m_block_inventory[block];
        if (block_pos < 0) {  // sparse super-block
            uint64_t overflow_pos = uint64_t(-block_pos - 1);
            return m_overflow_positions[overflow_pos + (i & (block_size - 1))];
        }

        uint64_t subblock = i / subblock_size;
        uint64_t start_pos = uint64_t(block_pos) + m_subblock_inventory[subblock];
        uint64_t reminder = i & (subblock_size - 1);
        if (!reminder) return start_pos;

        std::vector<uint64_t> const& data = B.data();
        uint64_t word_idx = start_pos >> 6;
        uint64_t word_shift = start_pos & 63;
        uint64_t word = WordGetter()(data, word_idx) & (uint64_t(-1) << word_shift);
        while (true) {
            uint64_t popcnt = util::popcount(word);
            if (reminder < popcnt) break;
            reminder -= popcnt;
            word = WordGetter()(data, ++word_idx);
        }
        return (word_idx << 6) + util::select_in_word(word, reminder);
    }

    inline uint64_t num_positions() const { return m_positions; }

    uint64_t num_bytes() const {
        return sizeof(m_positions) + essentials::vec_bytes(m_block_inventory) +
               essentials::vec_bytes(m_subblock_inventory) +
               essentials::vec_bytes(m_overflow_positions);
    }

    void swap(darray& other) {
        std::swap(m_positions, other.m_positions);
        m_block_inventory.swap(other.m_block_inventory);
        m_subblock_inventory.swap(other.m_subblock_inventory);
        m_overflow_positions.swap(other.m_overflow_positions);
    }

    template <typename Visitor>
    void visit(Visitor& visitor) const {
        visit_impl(visitor, *this);
    }

    template <typename Visitor>
    void visit(Visitor& visitor) {
        visit_impl(visitor, *this);
    }

protected:
    uint64_t m_positions;
    std::vector<int64_t> m_block_inventory;
    std::vector<uint16_t> m_subblock_inventory;
    std::vector<uint64_t> m_overflow_positions;

    template <typename Visitor, typename T>
    static void visit_impl(Visitor& visitor, T&& t) {
        visitor.visit(t.m_positions);
        visitor.visit(t.m_block_inventory);
        visitor.visit(t.m_subblock_inventory);
        visitor.visit(t.m_overflow_positions);
    }

    static void flush_cur_block(std::vector<uint64_t>& cur_block_positions,
                                std::vector<int64_t>& block_inventory,
                                std::vector<uint16_t>& subblock_inventory,
                                std::vector<uint64_t>& overflow_positions)  //
    {
        if (cur_block_positions.back() - cur_block_positions.front() < (1ULL << 16))  // dense case
        {
            block_inventory.push_back(int64_t(cur_block_positions.front()));
            for (uint64_t i = 0; i < cur_block_positions.size(); i += subblock_size) {
                subblock_inventory.push_back(
                    uint16_t(cur_block_positions[i] - cur_block_positions.front()));
            }
        } else  // sparse case
        {
            block_inventory.push_back(-int64_t(overflow_positions.size()) - 1);
            for (uint64_t i = 0; i < cur_block_positions.size(); ++i) {
                overflow_positions.push_back(cur_block_positions[i]);
            }
            for (uint64_t i = 0; i < cur_block_positions.size(); i += subblock_size) {
                subblock_inventory.push_back(uint16_t(-1));
            }
        }
        cur_block_positions.clear();
    }
};

namespace util {

struct identity_getter {
    uint64_t operator()(std::vector<uint64_t> const& data, uint64_t i) const { return data[i]; }
};

struct negating_getter {
    uint64_t operator()(std::vector<uint64_t> const& data, uint64_t i) const { return ~data[i]; }
};

}  // namespace util

typedef darray<util::identity_getter> darray1;  // take positions of 1s
typedef darray<util::negating_getter> darray0;  // take positions of 0s

}  // namespace bits