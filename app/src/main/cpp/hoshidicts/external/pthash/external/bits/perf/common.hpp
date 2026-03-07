#pragma once

namespace bits::perf {

const int num_runs = 5;
const uint64_t seed = 0;

/*
    Avoid powers of 2 that can cause cache aliasing issues.
*/
static constexpr uint32_t sequence_lengths[] = {
    251,     316,      398,      501,      630,      794,      1000,    1258,    1584,
    1995,    2511,     3162,     3981,     5011,     6309,     7943,    10000,   12589,
    15848,   19952,    25118,    31622,    39810,    50118,    63095,   79432,   100000,
    125892,  158489,   199526,   251188,   316227,   398107,   501187,  630957,  794328,
    1000000, 1258925,  1584893,  1995262,  2511886,  3162277,  3981071, 5011872, 6309573,
    7943282, 10000000, 12589254, 15848931, 19952623, 25118864, 31622776
    // 39810717, 50118723, 63095734, 79432823, 100000000, 125892541, 158489319, 199526231,
    // 251188643, 316227766, 398107170, 501187233, 630957344, 794328234, 1000000000
};

std::vector<uint64_t> get_queries(const uint64_t num_queries, const uint64_t sequence_length,
                                  const uint64_t seed = essentials::get_random_seed())  //
{
    essentials::uniform_int_rng<uint64_t> distr(0, sequence_length - 1, seed);
    std::vector<uint64_t> queries(num_queries);
    std::generate(queries.begin(), queries.end(), [&]() { return distr.gen(); });
    assert(queries.size() == num_queries);
    return queries;
}

}  // namespace bits::perf