#pragma once

#include "bit_vector.hpp"
#include "darray.hpp"
#include "compact_vector.hpp"

namespace bits {

template <typename DArray1 = darray1>
struct rice_sequence {
    template <typename Iterator>
    void encode(Iterator begin, const uint64_t n) {
        if (n == 0) return;

        uint64_t l = optimal_parameter_kiely(begin, n);
        bit_vector::builder bvb_high_bits;
        compact_vector::builder cv_builder_low_bits(n, l);

        const uint64_t low_mask = (uint64_t(1) << l) - 1;
        for (uint64_t i = 0; i != n; ++i, ++begin) {
            auto v = *begin;
            if (l > 0) cv_builder_low_bits.set(i, v & low_mask);
            auto unary = v >> l;
            for (uint64_t j = 0; j != unary; ++j) bvb_high_bits.push_back(0);
            bvb_high_bits.push_back(1);
        }

        bvb_high_bits.build(m_high_bits);
        cv_builder_low_bits.build(m_low_bits);
        m_high_bits_d1.build(m_high_bits);
    }

    inline uint64_t access(uint64_t i) const {
        assert(i < size());
        int64_t start = -1;
        if (i) start = m_high_bits_d1.select(m_high_bits, i - 1);
        int64_t end = m_high_bits_d1.select(m_high_bits, i);
        int64_t high = end - start - 1;
        return (high << m_low_bits.width()) | m_low_bits.access(i);
    }

    inline uint64_t size() const { return m_low_bits.size(); }

    uint64_t num_bytes() const {
        return m_high_bits.num_bytes() + m_high_bits_d1.num_bytes() + m_low_bits.num_bytes();
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
    template <typename Visitor, typename T>
    static void visit_impl(Visitor& visitor, T&& t) {
        visitor.visit(t.m_high_bits);
        visitor.visit(t.m_high_bits_d1);
        visitor.visit(t.m_low_bits);
    }

    bit_vector m_high_bits;
    DArray1 m_high_bits_d1;
    compact_vector m_low_bits;

    template <typename Iterator>
    uint64_t optimal_parameter_kiely(Iterator begin, const uint64_t n) {
        /* estimate parameter p from mean of sample */
        uint64_t sum = std::accumulate(begin, begin + n, uint64_t(0));
        double p = n / (static_cast<double>(sum) + n);
        const double gold = (sqrt(5.0) + 1.0) / 2.0;
        // return uint64_t(ceil(log2(-log2(gold) / log2(1 - p))));
        // Eq. (8) from Kiely, "Selecting the Golomb Parameter in Rice Coding", 2004.
        return std::max<int64_t>(0, 1 + floor(log2(log(gold - 1) / log(1 - p))));
    }
};

}  // namespace bits