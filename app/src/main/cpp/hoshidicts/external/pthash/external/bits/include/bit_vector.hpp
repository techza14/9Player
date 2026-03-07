#pragma once

#include <cstddef>
#include <vector>

#include "essentials.hpp"
#include "util.hpp"

namespace bits {

struct bit_vector  //
{
    struct builder  //
    {
        builder() { clear(); }

        builder(uint64_t num_bits, bool init = 0) { resize(num_bits, init); }

        void clear() {
            m_num_bits = 0;
            m_data.clear();
            m_cur_word = nullptr;
        }

        void fill(bool init = 0) { std::fill(m_data.begin(), m_data.end(), uint64_t(-init)); }

        void resize(uint64_t num_bits, bool init = 0) {
            m_num_bits = num_bits;
            m_data.resize(essentials::words_for<uint64_t>(num_bits), uint64_t(-init));
            if (num_bits) {
                m_cur_word = &m_data.back();
                if (init && (num_bits & 63)) {
                    *m_cur_word >>= 64 - (num_bits & 63);  // clear padding bits
                }
            }
        }

        void reserve(uint64_t num_bits) {
            m_data.reserve(essentials::words_for<uint64_t>(num_bits));
        }

        void build(bit_vector& bv) {
            bv.m_num_bits = m_num_bits;
            bv.m_data.swap(m_data);
            builder().swap(*this);
        }

        void swap(builder& other) {
            std::swap(m_num_bits, other.m_num_bits);
            std::swap(m_cur_word, other.m_cur_word);
            m_data.swap(other.m_data);
        }

        void push_back(bool b) {
            uint64_t pos_in_word = m_num_bits & 63;
            if (pos_in_word == 0) {
                m_data.push_back(0);
                m_cur_word = &m_data.back();
            }
            *m_cur_word |= (uint64_t)b << pos_in_word;
            ++m_num_bits;
        }

        void set(uint64_t pos, bool b = true) {
            assert(pos < num_bits());
            uint64_t word = pos >> 6;
            uint64_t pos_in_word = pos & 63;
            m_data[word] &= ~(uint64_t(1) << pos_in_word);
            m_data[word] |= uint64_t(b) << pos_in_word;
        }

        uint64_t get(uint64_t pos) const {
            assert(pos < num_bits());
            uint64_t word = pos >> 6;
            uint64_t pos_in_word = pos & 63;
            return m_data[word] >> pos_in_word & uint64_t(1);
        }

        void set_bits(uint64_t pos, uint64_t bits, uint64_t len) {
            assert(pos + len <= num_bits());
            // check there are no spurious bits
            assert(len == 64 || (bits >> len) == 0);
            if (!len) return;
            uint64_t mask = (len == 64) ? uint64_t(-1) : ((uint64_t(1) << len) - 1);
            uint64_t word = pos >> 6;
            uint64_t pos_in_word = pos & 63;

            m_data[word] &= ~(mask << pos_in_word);
            m_data[word] |= bits << pos_in_word;

            uint64_t stored = 64 - pos_in_word;
            if (stored < len) {
                m_data[word + 1] &= ~(mask >> stored);
                m_data[word + 1] |= bits >> stored;
            }
        }

        void append_bits(uint64_t bits, uint64_t len) {
            // check there are no spurious bits
            assert(len <= 64);
            assert(len == 64 || (bits >> len) == 0);
            if (!len) return;
            uint64_t pos_in_word = m_num_bits & 63;
            m_num_bits += len;
            if (pos_in_word == 0) {
                m_data.push_back(bits);
            } else {
                *m_cur_word |= bits << pos_in_word;
                if (len > 64 - pos_in_word) m_data.push_back(bits >> (64 - pos_in_word));
            }
            m_cur_word = &m_data.back();
        }

        uint64_t get_word64(uint64_t pos) const {
            assert(pos < num_bits());
            uint64_t block = pos >> 6;
            uint64_t shift = pos & 63;
            uint64_t word = m_data[block] >> shift;
            if (shift && block + 1 < m_data.size()) word |= m_data[block + 1] << (64 - shift);
            return word;
        }

        void append(builder const& rhs) {
            if (!rhs.num_bits()) return;

            uint64_t pos = m_data.size();
            uint64_t shift = num_bits() & 63;
            m_num_bits = num_bits() + rhs.num_bits();
            m_data.resize(essentials::words_for<uint64_t>(m_num_bits));

            if (shift == 0) {  // word-aligned, easy case
                std::copy(rhs.m_data.begin(), rhs.m_data.end(), m_data.begin() + ptrdiff_t(pos));
            } else {
                uint64_t* cur_word = &m_data.front() + pos - 1;
                for (uint64_t i = 0; i < rhs.m_data.size() - 1; ++i) {
                    uint64_t w = rhs.m_data[i];
                    *cur_word |= w << shift;
                    *++cur_word = w >> (64 - shift);
                }
                *cur_word |= rhs.m_data.back() << shift;
                if (cur_word < &m_data.back()) *++cur_word = rhs.m_data.back() >> (64 - shift);
            }
            m_cur_word = &m_data.back();
        }

        uint64_t num_bits() const { return m_num_bits; }

        std::vector<uint64_t>& data() { return m_data; }
        std::vector<uint64_t> const& data() const { return m_data; }

    private:
        uint64_t m_num_bits;
        uint64_t* m_cur_word;
        std::vector<uint64_t> m_data;
    };

    bit_vector() : m_num_bits(0) {}

    uint64_t get(uint64_t pos) const {
        assert(pos < num_bits());
        uint64_t block = pos >> 6;
        uint64_t shift = pos & 63;
        return m_data[block] >> shift & uint64_t(1);
    }

    uint64_t get_bits(uint64_t pos, uint64_t len) const {
        assert(pos + len <= num_bits());
        if (!len) return 0;
        uint64_t block = pos >> 6;
        uint64_t shift = pos & 63;
        uint64_t mask = -(len == 64) | ((1ULL << len) - 1);
        if (shift + len <= 64) {
            return m_data[block] >> shift & mask;
        } else {
            return (m_data[block] >> shift) | (m_data[block + 1] << (64 - shift) & mask);
        }
    }

    // fast and unsafe version: it retrieves at least 56 bits
    uint64_t get_word56(uint64_t pos) const {
        const char* base_ptr = reinterpret_cast<const char*>(m_data.data());
        return *(reinterpret_cast<uint64_t const*>(base_ptr + (pos >> 3))) >> (pos & 7);
    }

    // pad with zeros if extension further size is needed
    uint64_t get_word64(uint64_t pos) const {
        assert(pos < num_bits());
        uint64_t block = pos >> 6;
        uint64_t shift = pos & 63;
        uint64_t word = m_data[block] >> shift;
        if (shift && block + 1 < m_data.size()) word |= m_data[block + 1] << (64 - shift);
        return word;
    }

    struct iterator {
        iterator() : m_data(nullptr), m_num_64bit_words(0), m_pos(0), m_buf(0), m_avail(0) {}

        iterator(uint64_t const* data, uint64_t num_64bit_words, uint64_t pos = 0)
            : m_data(data)                        //
            , m_num_64bit_words(num_64bit_words)  //
            , m_pos(pos)                          //
        {
            skip_to(m_pos);
        }

        iterator(bit_vector const* bv, uint64_t pos = 0)
            : m_data((bv->data()).data())             //
            , m_num_64bit_words((bv->data()).size())  //
            , m_pos(pos)                              //
        {
            skip_to(m_pos);
        }

        void skip_to(uint64_t pos) {
            assert(pos < 64 * m_num_64bit_words);
            m_pos = pos;
            fill_buf();
        }

        /*
            Return the bit at current position.
        */
        bool operator*() const {
            uint64_t word = m_pos >> 6;
            uint64_t pos_in_word = m_pos & 63;
            assert(word < m_num_64bit_words);
            return m_data[word] >> pos_in_word & uint64_t(1);
        }

        void operator++() { m_pos++; }

        /*
            Return the next l bits from the current position and advance by l bits.
        */
        uint64_t take(uint64_t l) {
            assert(l <= 64);
            if (m_avail < l) fill_buf();
            uint64_t val;
            if (l != 64) {
                val = m_buf & ((uint64_t(1) << l) - 1);
                m_buf >>= l;
            } else {
                val = m_buf;
            }
            m_avail -= l;
            m_pos += l;
            return val;
        }

        /*
            Return the position, say p, of the next 1 from current position
            and move to position p+1.
        */
        uint64_t next() {
            skip_zeros();
            assert(position() > 0);
            return position() - 1;
        }

        /*
            Return the position of the previous 1 from position pos.
            If the bit at position pos is already a 1, this returns pos.
        */
        uint64_t prev(uint64_t pos) {
            uint64_t block = pos >> 6;
            uint64_t shift = 64 - (pos & 63) - 1;
            uint64_t word = m_data[block];
            word = (word << shift) >> shift;
            uint64_t ret;
            while (!util::msbll(word, ret)) {
                assert(block > 0);
                word = m_data[--block];
            }
            ret += block << 6;
            return ret;
        }

        /*
            Skip all zeros from the current position and
            return the number of skipped zeros.
        */
        uint64_t skip_zeros() {
            uint64_t zeros = 0;
            while (m_buf == 0) {
                m_pos += m_avail;
                zeros += m_avail;
                fill_buf();
            }
            uint64_t l = util::lsbll(m_buf);
            m_buf >>= l;
            m_buf >>= 1;
            m_avail -= l + 1;
            m_pos += l + 1;
            return zeros + l;
        }

        uint64_t position() const { return m_pos; }

    private:
        uint64_t const* m_data;
        uint64_t m_num_64bit_words;
        uint64_t m_pos;
        uint64_t m_buf;
        uint64_t m_avail;

        /*
            Get 64 bits from current position.
        */
        void fill_buf() {
            uint64_t block = m_pos >> 6;
            uint64_t shift = m_pos & 63;
            m_buf = m_data[block] >> shift;
            if (shift &&
                block + 1 < m_num_64bit_words) {  // next 64 bits span two consecutive words
                m_buf |= m_data[block + 1] << (64 - shift);
            }
            m_avail = 64;
        }
    };

    iterator get_iterator_at(uint64_t pos) const { return iterator(this, pos); }
    iterator begin() const { return get_iterator_at(0); }

    uint64_t num_bits() const { return m_num_bits; }
    std::vector<uint64_t> const& data() const { return m_data; }

    uint64_t num_bytes() const { return sizeof(m_num_bits) + essentials::vec_bytes(m_data); }

    void swap(bit_vector& other) {
        std::swap(m_num_bits, other.m_num_bits);
        m_data.swap(other.m_data);
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
    uint64_t m_num_bits;
    std::vector<uint64_t> m_data;

    template <typename Visitor, typename T>
    static void visit_impl(Visitor& visitor, T&& t) {
        visitor.visit(t.m_num_bits);
        visitor.visit(t.m_data);
    }
};

}  // namespace bits