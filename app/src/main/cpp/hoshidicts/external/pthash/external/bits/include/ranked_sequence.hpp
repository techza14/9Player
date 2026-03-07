#pragma once

#include <unordered_map>
#include <vector>

#include "compact_vector.hpp"

namespace bits {

template <typename Iterator>
std::pair<std::vector<uint64_t>, std::vector<uint64_t>> compute_ranks_and_dictionary(
    Iterator begin, const uint64_t n)  //
{
    // compute frequencies
    std::unordered_map<uint64_t, uint64_t> distinct;
    for (auto it = begin, end = begin + n; it != end; ++it) distinct[*it] += 1;

    std::vector<std::pair<uint64_t, uint64_t>> vec;
    vec.reserve(distinct.size());
    for (auto p : distinct) vec.emplace_back(p.first, p.second);
    std::sort(vec.begin(), vec.end(),
              [](auto const& x, auto const& y) { return x.second > y.second; });
    distinct.clear();

    // assign codewords by non-increasing frequency
    std::vector<uint64_t> dict;
    dict.reserve(distinct.size());
    for (uint64_t i = 0; i != vec.size(); ++i) {
        auto p = vec[i];
        distinct.insert({p.first, i});
        dict.push_back(p.first);
    }

    std::vector<uint64_t> ranks;
    ranks.reserve(n);
    for (auto it = begin, end = begin + n; it != end; ++it) ranks.push_back(distinct[*it]);
    return {ranks, dict};
}

struct ranked_sequence {
    template <typename Iterator>
    void encode(Iterator begin, const uint64_t n) {
        if (n == 0) return;
        auto [ranks, dict] = compute_ranks_and_dictionary(begin, n);
        m_ranks.build(ranks.begin(), ranks.size());
        m_dict.build(dict.begin(), dict.size());
    }

    uint64_t size() const { return m_ranks.size(); }

    uint64_t num_bytes() const { return m_ranks.num_bytes() + m_dict.num_bytes(); }

    uint64_t access(uint64_t i) const {
        uint64_t rank = m_ranks.access(i);
        return m_dict.access(rank);
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
        visitor.visit(t.m_ranks);
        visitor.visit(t.m_dict);
    }

    compact_vector m_ranks;
    compact_vector m_dict;
};

}  // namespace bits