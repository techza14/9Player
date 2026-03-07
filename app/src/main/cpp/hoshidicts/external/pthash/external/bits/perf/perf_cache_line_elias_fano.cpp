#include "test/common.hpp"
#include "perf/common.hpp"
#include "include/cache_line_elias_fano.hpp"

cache_line_elias_fano encode_with_cache_line_elias_fano(std::vector<uint64_t> const& seq) {
    std::cout << "encoding seq with cache_line_elias_fano..." << std::endl;
    cache_line_elias_fano ef;
    ef.encode(seq.begin(), seq.size());
    REQUIRE(ef.size() == seq.size());
    std::cout << "measured bits/int = " << (8.0 * ef.num_bytes()) / ef.size() << std::endl;
    return ef;
}

TEST_CASE("access_dense") {
    essentials::timer_type t;

    std::stringstream ss_avg_ns_per_query;
    std::stringstream ss_sequence_lengths;
    ss_avg_ns_per_query << "\"avg_ns_per_query\":[";
    ss_sequence_lengths << "\"sequence_lengths\":[";

    const uint64_t num_points = sizeof(perf::sequence_lengths) / sizeof(perf::sequence_lengths[0]);
    assert(num_points > 0);

    for (uint64_t i = 0; i != num_points; ++i)  //
    {
        const uint64_t sequence_length = perf::sequence_lengths[i];
        assert(sequence_length > 0);
        std::vector<uint64_t> seq =
            test::get_uniform_sorted_sequence(sequence_length, 3.0 * sequence_length, perf::seed);
        // test::print(seq);
        uint64_t num_queries = std::max<uint64_t>(0.1 * sequence_length, 100000);
        std::vector<uint64_t> queries = perf::get_queries(num_queries, sequence_length, perf::seed);
        // test::print(queries);
        t.reset();
        auto ef = encode_with_cache_line_elias_fano(seq);
        t.start();
        for (int run = 0; run != perf::num_runs; ++run) {
            for (auto x : queries) {
                uint64_t val = ef.access(x);
                essentials::do_not_optimize_away(val);
            }
        }
        t.stop();
        double avg_ns_per_query = t.elapsed() / (perf::num_runs * num_queries) * 1000.0;
        std::cout << "  total elapsed time = " << t.elapsed() << std::endl;
        std::cout << "  CLEF(n=" << sequence_length << ") access = " << avg_ns_per_query
                  << " [ns/query]" << std::endl;

        ss_sequence_lengths << sequence_length;
        ss_avg_ns_per_query << avg_ns_per_query;
        if (i != num_points - 1) {
            ss_avg_ns_per_query << ',';
            ss_sequence_lengths << ',';
        }
    }

    ss_avg_ns_per_query << ']';
    ss_sequence_lengths << ']';
    std::string json("{\"query\":\"cache_line_elias_fano::access\", \"seed\":" +
                     std::to_string(perf::seed) + ", ");
    json += ss_sequence_lengths.str();
    json.push_back(',');
    json.push_back(' ');
    json += ss_avg_ns_per_query.str();
    json.push_back('}');
    std::cerr << json << std::endl;
}