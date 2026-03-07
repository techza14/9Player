#pragma once

#include <bitset>

#include "util.hpp"

namespace bits {

/*

    A version of Elias-Fano that is optimized for random access.
    The sequence is split into blocks of 44 elements and low/high bits
    are dimensioned such that each block fits in one cache line (64 bytes).

    The low parts take 8 bits each. The high part spans 128 bits.
    This allows a universe per block of (128-44)*2^8 = 21,504, i.e.,
    an avg. gap between the elements of ~489.
    (Experimentally, the sequence v_i = i * c, i = 0, 1, 2, ...
     works correctly up to c = 500.)

    Each block is written as follows.
    4 bytes for the high part of the first element in the block, say h_v.
    16 bytes for the high bits, followed by 1 byte per each low part.

    Reference:

    Ragnar Groot Koerkamp. 2025.
    "PtrHash: Minimal Perfect Hashing at RAM Throughput",
    https://arxiv.org/abs/2502.15539.

*/
struct cache_line_elias_fano {
    cache_line_elias_fano() : m_back(0), m_size(0) {}

    template <typename Iterator>
    void encode(Iterator begin, uint64_t n, uint64_t universe = uint64_t(-1)) {
        if (n == 0) return;

        m_size = n;

        if (universe == uint64_t(-1))  // otherwise use the provided universe
        {
            if constexpr (std::is_same_v<typename Iterator::iterator_category,
                                         std::random_access_iterator_tag>) {
                universe = *(begin + (n - 1));
            } else {  // scan
                auto tmp = begin;
                for (uint64_t i = 0; i != n - 1; ++i, ++tmp)
                    ;
                universe = *tmp;
            }
        }

        const uint64_t num_blocks = (n + 44 - 1) / 44;
        const uint64_t num_bytes = num_blocks * 64;
        m_bits.resize(num_bytes);
        std::fill(m_bits.begin(), m_bits.end(), 0);

        uint8_t* high = m_bits.data();
        uint8_t* low = high + 4  // for lower bound high part of block
                       + 16;     // for high bits

        uint64_t lower_bound_high_part = 0;
        uint64_t pos_in_block = 0;
        uint64_t last = 0;
        for (uint64_t i = 0; i != n; ++i, ++begin) {
            uint64_t v = *begin;
            assert(v < (1ULL << 40));

            if (i and v < last) {  // check the order
                std::cerr << "error at " << i << "/" << n << ":\n";
                std::cerr << "last " << last << "\n";
                std::cerr << "current " << v << "\n";
                throw std::runtime_error("sequence is not sorted");
            }

            if (pos_in_block == 0) {
                lower_bound_high_part = v >> 8;
                assert(lower_bound_high_part < (1ULL << 32));
                *reinterpret_cast<uint32_t*>(high) = lower_bound_high_part;
                high += 4;
            }

            uint64_t high_v = (v >> 8) - lower_bound_high_part + pos_in_block;
            uint64_t low_v = v & 255;
            uint64_t word = high_v >> 6;
            if (word >= 2) throw std::runtime_error("the high part must fit within 128 bits");
            uint64_t pos_in_word = high_v & 63;
            uint64_t* W = reinterpret_cast<uint64_t*>(high) + word;
            *W |= uint64_t(1) << pos_in_word;
            *(low + pos_in_block) = low_v;

            ++pos_in_block;
            if (pos_in_block == 44) {
                high += 64 - 4;
                low = high + 4 + 16;
                pos_in_block = 0;
            }

            last = v;
        }

        m_back = last;
    }

    uint64_t access(uint64_t i) const {
        assert(i < size());
        uint64_t block = i / 44;
        uint64_t offset = i % 44;
        uint64_t block_begin = block * 64;
        uint64_t index_low_bits = block_begin + 4 + 16 + offset;
        assert(index_low_bits < m_bits.size());
        uint64_t low = m_bits[index_low_bits];
        uint64_t lower_bound_high_part = *reinterpret_cast<uint32_t const*>(&m_bits[block_begin]);
        uint64_t W1 = *reinterpret_cast<uint64_t const*>(&m_bits[block_begin + 4]);
        uint64_t W2 = *reinterpret_cast<uint64_t const*>(&m_bits[block_begin + 4 + 8]);
        uint64_t high = 0;
        uint64_t pop = util::popcount(W1);
        if (offset < pop) {
            high = util::select_in_word(W1, offset) - offset;
        } else {
            high = 64 + util::select_in_word(W2, offset - pop) - offset;
        }
        return 256 * (high + lower_bound_high_part) + low;
    }

    uint64_t back() const { return m_back; }
    uint64_t size() const { return m_size; }

    uint64_t num_bytes() const {
        return sizeof(m_back) + sizeof(m_size) + essentials::vec_bytes(m_bits);
    }

    void swap(cache_line_elias_fano& other) {
        std::swap(m_back, other.m_back);
        std::swap(m_size, other.m_size);
        m_bits.swap(other.m_bits);
    }

    template <typename Visitor>
    void visit(Visitor& visitor) const {
        visit_impl(visitor, *this);
    }

    template <typename Visitor>
    void visit(Visitor& visitor) {
        visit_impl(visitor, *this);
    }

private:
    uint64_t m_back;
    uint64_t m_size;
    std::vector<uint8_t> m_bits;

    template <typename Visitor, typename T>
    static void visit_impl(Visitor& visitor, T&& t) {
        visitor.visit(t.m_back);
        visitor.visit(t.m_size);
        visitor.visit(t.m_bits);
    }
};

}  // namespace bits