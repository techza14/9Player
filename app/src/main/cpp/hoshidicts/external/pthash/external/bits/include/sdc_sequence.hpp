#pragma once

#include "elias_fano.hpp"

namespace bits {

template <typename DArray1 = darray1>
struct sdc_sequence {
    sdc_sequence() : m_size(0) {}

    template <typename Iterator>
    void encode(Iterator begin, const uint64_t n) {
        if (n == 0) return;
        m_size = n;
        auto start = begin;
        uint64_t bits = 0;
        for (uint64_t i = 0; i < n; ++i, ++start) bits += std::floor(std::log2(*start + 1));
        bit_vector::builder bvb(bits);
        std::vector<uint64_t> lengths;
        lengths.reserve(n + 1);
        uint64_t pos = 0;
        for (uint64_t i = 0; i < n; ++i, ++begin) {
            auto v = *begin;
            uint64_t len = std::floor(std::log2(v + 1));
            assert(len <= 64);
            uint64_t cw = v + 1 - (uint64_t(1) << len);
            if (len > 0) bvb.set_bits(pos, cw, len);
            lengths.push_back(pos);
            pos += len;
        }
        assert(pos == bits);
        lengths.push_back(pos);
        bvb.build(m_codewords);
        m_index.encode(lengths.begin(), lengths.size());
    }

    inline uint64_t access(uint64_t i) const {
        assert(i < size());
        uint64_t pos = m_index.access(i);
        uint64_t len = m_index.access(i + 1) - pos;
        assert(len < 64);
        uint64_t cw = m_codewords.get_bits(pos, len);
        uint64_t value = cw + (uint64_t(1) << len) - 1;
        return value;
    }

    uint64_t size() const { return m_size; }

    uint64_t num_bytes() const {
        return sizeof(m_size) + m_codewords.num_bytes() + m_index.num_bytes();
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
        visitor.visit(t.m_size);
        visitor.visit(t.m_codewords);
        visitor.visit(t.m_index);
    }

    uint64_t m_size;
    bit_vector m_codewords;
    elias_fano<false, false, DArray1> m_index;
};

}  // namespace bits