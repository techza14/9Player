#pragma once

#include <vector>

#include "bit_vector.hpp"
#include "darray.hpp"
#include "compact_vector.hpp"

namespace bits {

/*
    This class encodes with Elias-Fano a sequence with the
    following properties:
    - all elements are distinct;
    - the first element is 0;
    - when doing `next_geq(x)`, x is always in [0,U) where
      U is the last element of the sequence.

    This is the case when the sequence represents a list of
    bin sizes in a cumulative way, assuming that each bin is
    non empty and we ask what is the bin that comprises element x.
    For example: imagine we have a set of strings that we concatenate
    together and we keep the list of string boundaries.
    If the have 4 strings of length 3, 10, 7, 5 respectively,
    then we encode the sequence S = [0, 3, 13, 20, U=25]

    0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25
    x     x                       x                    x              x

    where U = 3+10+7+5, and length of i-th string is S[i+1]-S[i],
    for i = 0..3.
    Given the offset x of a character, we ask what is the string that
    comprises that offset. In this case, we have that 0 <= x < U.

    The goal of this class is to support fast next_geq queries at the price
    of some space increase compared to an `elias_fano` sequence, so:
    - The low bits are always 8.
    - We keep an array of H of size ceil(U/2^8), where H[i] indicates the
      position of the i-th 0 in the high bit array. Each H[i] is written
      in 1+log(n) bits because the high bit array has length <= 2n.
*/

template <typename DArray1 = darray1>
struct endpoints_sequence {
    endpoints_sequence() : m_back(0) {}

    template <typename Iterator>
    void encode(Iterator begin, uint64_t n, uint64_t universe) {
        if (n == 0) return;

        const uint64_t num_high_bits = n + (universe >> 8) + 1;
        bit_vector::builder bvb_high_bits(num_high_bits);
        m_low_bits.reserve(n);

        compact_vector::builder cv_builder((universe + 256 - 1) / 256,  // ceil(U/2^8)
                                           util::ceil_log2_uint64(num_high_bits));

        assert(*begin == 0);
        uint64_t prev_pos = 0;
        for (uint64_t i = 0; i != n; ++i, ++begin) {
            auto v = *begin;
            m_low_bits.push_back(v & 255);
            uint64_t high_part = v >> 8;
            uint64_t pos = high_part + i;
            bvb_high_bits.set(pos, 1);
            for (uint64_t j = prev_pos + 1; j < pos; ++j) cv_builder.push_back(j);
            prev_pos = pos;
            m_back = v;
        }
        cv_builder.push_back(prev_pos + 1);

        bvb_high_bits.build(m_high_bits);
        m_high_bits_d1.build(m_high_bits);
        cv_builder.build(m_high_bits_d0);

        // for (uint64_t i = 0; i != m_high_bits.num_bits(); ++i) {
        //     std::cout << m_high_bits.get(i) << ' ';
        // }
        // std::cout << std::endl;
        // auto it = m_high_bits_d0.begin();
        // for (uint64_t i = 0; i != m_high_bits_d0.size(); ++i) {
        //     std::cout << i << " : " << *it << '\n';
        //     ++it;
        // }
    }

    struct iterator {
        iterator() : m_ptr(nullptr), m_pos(0), m_val(0) {}

        iterator(endpoints_sequence const* ptr, uint64_t pos = 0)
            : m_ptr(ptr)
            , m_pos(pos)
            , m_val(0)  //
        {
            if (!has_next() or m_ptr->m_high_bits_d1.num_positions() == 0) return;
            uint64_t begin = m_ptr->m_high_bits_d1.select(m_ptr->m_high_bits, m_pos);
            m_high_bits_it = m_ptr->m_high_bits.get_iterator_at(begin);
            m_low_bits_it = m_ptr->m_low_bits.begin() + m_pos;
            read_next_value();
        }

        iterator(endpoints_sequence const* ptr, uint64_t pos, uint64_t pos_in_high_bits /* hint */)
            : m_ptr(ptr)
            , m_pos(pos)
            , m_val(0)  //
        {
            /*
                These two assertions are valid because this constructor is only used
                in `next_geq`.
            */
            assert(has_next());
            assert(pos_in_high_bits == 0 || m_ptr->m_high_bits.get(pos_in_high_bits) == 0);

            m_high_bits_it = m_ptr->m_high_bits.get_iterator_at(pos_in_high_bits);
            m_low_bits_it = m_ptr->m_low_bits.begin() + m_pos;
            read_next_value();
        }

        bool has_next() const { return m_pos < m_ptr->size(); }
        bool has_prev() const { return m_pos > 0; }
        uint64_t value() const { return m_val; }
        uint64_t operator*() const { return value(); }
        uint64_t position() const { return m_pos; }

        void next() {
            ++m_pos;
            if (!has_next()) return;
            read_next_value();
        }
        void operator++() { next(); }

        /*
            Return the value before the current position.
        */
        uint64_t prev_value() {
            assert(m_pos > 0);
            uint64_t pos = m_pos - 1;
            /*
                `read_next_value()` sets the state ahead of 1 position,
                hence must go back by 2 positions to get previous value.
            */
            assert(m_high_bits_it.position() >= 2);
            uint64_t high = m_high_bits_it.prev(m_high_bits_it.position() - 2);
            assert(high == m_ptr->m_high_bits_d1.select(m_ptr->m_high_bits, pos));
            uint64_t low = *(m_low_bits_it - 2);
            return ((high - pos) << 8) | low;
        }

    private:
        endpoints_sequence const* m_ptr;
        uint64_t m_pos;
        uint64_t m_val;
        bit_vector::iterator m_high_bits_it;
        std::vector<uint8_t>::const_iterator m_low_bits_it;

        void read_next_value() {
            assert(m_pos < m_ptr->size());
            uint64_t high = m_high_bits_it.next();
            assert(high == m_ptr->m_high_bits_d1.select(m_ptr->m_high_bits, m_pos));
            uint64_t low = *m_low_bits_it;
            m_val = ((high - m_pos) << 8) | low;
            ++m_low_bits_it;
        }
    };

    iterator get_iterator_at(uint64_t pos) const { return iterator(this, pos); }
    iterator begin() const { return get_iterator_at(0); }

    uint64_t access(uint64_t i) const {
        assert(i < size());
        return ((m_high_bits_d1.select(m_high_bits, i) - i) << 8) | m_low_bits[i];
    }

    struct return_value {
        uint64_t pos;
        uint64_t val;
    };

    /*
        Return [position,value] of the smallest element that is >= x.
    */
    return_value next_geq(const uint64_t x) const { return next_geq_helper(x).first; }

    /*
        Determine integers lo and hi, with lo < hi, such that lo <= x < hi, where:
        - lo is the largest value that is <= x,
        - hi is the smallest value that is > x.
        Return the tuple [lo_pos, lo, hi_pos, hi].
    */
    std::pair<return_value, return_value> locate(const uint64_t x) const {
        auto [lo, it] = next_geq_helper(x);
        return_value hi{lo.pos, lo.val};

        if (lo.val > x) {
            assert(lo.pos > 0);
            lo.pos -= 1;
            lo.val = it.prev_value();
        } else {
            hi.pos += 1;
            hi.val = (it.next(), it.value());
            assert(it.position() == hi.pos);
            assert(hi.pos < size());
        }

        return {lo, hi};
    }

    uint64_t back() const { return m_back; }
    uint64_t size() const { return m_low_bits.size(); }

    uint64_t num_bytes() const {
        return sizeof(m_back) + m_high_bits.num_bytes() + m_high_bits_d1.num_bytes() +
               m_high_bits_d0.num_bytes() + essentials::vec_bytes(m_low_bits);
    }

    void swap(endpoints_sequence& other) {
        std::swap(m_back, other.m_back);
        m_high_bits.swap(other.m_high_bits);
        m_high_bits_d1.swap(other.m_high_bits_d1);
        m_high_bits_d0.swap(other.m_high_bits_d0);
        m_low_bits.swap(other.m_low_bits);
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
    bit_vector m_high_bits;
    DArray1 m_high_bits_d1;
    compact_vector m_high_bits_d0;
    std::vector<uint8_t> m_low_bits;

    template <typename Visitor, typename T>
    static void visit_impl(Visitor& visitor, T&& t) {
        visitor.visit(t.m_back);
        visitor.visit(t.m_high_bits);
        visitor.visit(t.m_high_bits_d1);
        visitor.visit(t.m_high_bits_d0);
        visitor.visit(t.m_low_bits);
    }

    std::pair<return_value, iterator> next_geq_helper(const uint64_t x) const  //
    {
        assert(x < back());

        uint64_t h_x = x >> 8;
        uint64_t p = 0;
        uint64_t begin = 0;
        if (h_x > 0) {
            p = m_high_bits_d0.access(h_x - 1);
            begin = p - h_x + 1;
        }
        assert(begin < size());

        /*
            `begin` is the position of the elements that have high part >= h_x
            and `p` is the position in `m_high_bits` of the (h_x-1)-th 0,
            so it is passed to the iterator as a hint to recover the high part
            of the element at position `begin` without doing a select_1.
        */

        auto it = iterator(this, begin, p /* position in high_bits hint */);
        uint64_t pos = begin;
        uint64_t val = it.value();
        while (val < x) {
            ++pos;
            it.next();
            val = it.value();
        }
        /* now pos is the position of the element that is >= x */
        assert(val >= x);
        assert(pos < size());
        assert(val == access(pos));
        assert(it.position() == pos);

        return {{pos, val}, it};
    }
};

}  // namespace bits