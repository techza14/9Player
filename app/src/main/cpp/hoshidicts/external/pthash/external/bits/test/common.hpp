#pragma once

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "external/doctest/doctest/doctest.h"

#include <iostream>
#include <vector>
#include <random>

#include "essentials.hpp"

namespace bits::test {

/*
    Generate [sequence_length] random integers, distributed as a poisson RV with a random mean.
*/
std::vector<uint64_t> get_sequence(const uint64_t sequence_length,                       //
                                   const uint64_t max_int = 1000,                        //
                                   const uint64_t seed = essentials::get_random_seed())  //
{
    std::cout << "seed = " << seed << std::endl;
    srand(seed);
    double mean = rand() % (max_int + 1);
    std::mt19937 rng(seed);
    std::poisson_distribution<uint64_t> distr(mean);
    std::vector<uint64_t> seq(sequence_length);
    std::generate(seq.begin(), seq.end(), [&]() { return distr(rng) % (max_int + 1); });
    assert(seq.size() == sequence_length);
    return seq;
}

/*
    Generate a sorted sequence of [sequence_length] random integers,
    distributed as a poisson RV with a random mean.
*/
std::vector<uint64_t> get_sorted_sequence(const uint64_t sequence_length,                       //
                                          const uint64_t max_int = 1000,                        //
                                          const bool all_distinct = false,                      //
                                          const uint64_t seed = essentials::get_random_seed())  //
{
    srand(seed);
    double mean = rand() % (max_int + 1);
    std::mt19937 rng(seed);
    std::poisson_distribution<uint64_t> distr(mean);
    uint64_t universe = 0;
    std::vector<uint64_t> seq(sequence_length);
    std::generate(seq.begin(), seq.end(), [&]() {
        uint64_t val = distr(rng) % (max_int + 1);
        universe += val + all_distinct;
        return universe;
    });
    assert(seq.size() == sequence_length);
    assert(seq.back() == universe);
    assert(std::is_sorted(seq.begin(), seq.end()));
    return seq;
}

std::vector<uint64_t> get_uniform_sorted_sequence(
    const uint64_t sequence_length, const uint64_t universe,
    const uint64_t seed = essentials::get_random_seed())  //
{
    essentials::uniform_int_rng<uint64_t> distr(0, universe - 1, seed);
    std::vector<uint64_t> seq(sequence_length);
    std::generate(seq.begin(), seq.end(), [&]() { return distr.gen(); });
    assert(seq.size() == sequence_length);
    std::sort(seq.begin(), seq.end());
    return seq;
}

uint64_t get_random_uint(const uint64_t max_int = 1000,
                         const uint64_t seed = essentials::get_random_seed())  //
{
    srand(seed);
    return rand() % max_int + 1;  // at least 1
}

std::vector<uint64_t> get_skewed_sequence(const uint64_t sequence_length,
                                          const uint64_t max_small_value,
                                          const uint64_t max_large_value,
                                          const double skew_factor,  // 0 <= skew_factor <= 1
                                          const uint64_t seed = essentials::get_random_seed())  //
{
    std::vector<uint64_t> seq;
    seq.reserve(sequence_length);

    std::mt19937 gen(seed);
    std::uniform_int_distribution<uint64_t> small_value_dist(0, max_small_value - 1);
    std::uniform_int_distribution<uint64_t> large_value_dist(max_small_value, max_large_value - 1);
    std::uniform_real_distribution<float> float_dist(0.0f, 1.0f);

    for (uint64_t i = 0; i != sequence_length; ++i) {
        float r = float_dist(gen);
        if (r < skew_factor) {
            seq.push_back(small_value_dist(gen));
        } else {
            seq.push_back(large_value_dist(gen));
        }
    }

    return seq;
}

template <typename T>
void print(std::vector<T> const& v) {
    std::cout << '[';
    for (uint64_t i = 0; i != v.size(); ++i) {
        auto const& x = v[i];
        std::cout << x;
        if (i != v.size() - 1) std::cout << ',';
    }
    std::cout << ']';
    std::cout << std::endl;
}

}  // namespace bits::test

using namespace bits;
