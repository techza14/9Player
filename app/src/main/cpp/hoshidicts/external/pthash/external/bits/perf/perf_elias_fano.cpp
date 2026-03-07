#include "test/common.hpp"
#include "perf/common.hpp"
#include "include/elias_fano.hpp"

#include <sstream>

template <bool index_zeros, bool encode_prefix_sum>
elias_fano<index_zeros, encode_prefix_sum>                //
encode_with_elias_fano(std::vector<uint64_t> const& seq)  //
{
    std::cout << "encoding seq with elias_fano<false,false>..." << std::endl;
    elias_fano<index_zeros, encode_prefix_sum> ef;
    ef.encode(seq.begin(), seq.size());
    REQUIRE(ef.size() == seq.size() + encode_prefix_sum);
    std::cout << "measured bits/int = " << (8.0 * ef.num_bytes()) / ef.size() << std::endl;
    return ef;
}

template <bool index_zeros, bool encode_prefix_sum, typename Func>
void perf_func(std::string const& op,                                 //
               double const avg_gap_seq, double const avg_gap_query,  //
               Func f)                                                //
{
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
        std::vector<uint64_t> seq = test::get_uniform_sorted_sequence(
            sequence_length, avg_gap_seq * sequence_length, perf::seed);
        // test::print(seq);
        uint64_t num_queries = std::max<uint64_t>(0.1 * sequence_length, 100000);
        std::vector<uint64_t> queries =
            perf::get_queries(num_queries, avg_gap_query * sequence_length, perf::seed);
        // test::print(queries);
        t.reset();
        auto ef = encode_with_elias_fano<index_zeros, encode_prefix_sum>(seq);
        t.start();
        for (int run = 0; run != perf::num_runs; ++run) {
            for (auto x : queries) {
                auto ret = f(ef, x);
                essentials::do_not_optimize_away(&ret);
            }
        }
        t.stop();
        double avg_ns_per_query = t.elapsed() / (perf::num_runs * num_queries) * 1000.0;
        std::cout << "  total elapsed time = " << t.elapsed() << std::endl;
        std::cout << "  EF(n=" << sequence_length << ") " << op << " = " << avg_ns_per_query
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
    std::string json("{\"query\":\"elias_fano::" + op +
                     "\", \"seed\":" + std::to_string(perf::seed) + ", ");
    json += ss_sequence_lengths.str();
    json.push_back(',');
    json.push_back(' ');
    json += ss_avg_ns_per_query.str();
    json.push_back('}');
    std::cerr << json << std::endl;
}

TEST_CASE("access_dense")  //
{
    perf_func<true, false>("access_dense", 3.0, 1,
                           [](auto const& ef, const uint64_t x) { return ef.access(x); });
}
TEST_CASE("access_sparse")  //
{
    perf_func<true, false>("access_sparse", 3000.0, 1,
                           [](auto const& ef, const uint64_t x) { return ef.access(x); });
}

TEST_CASE("diff_dense")  //
{
    perf_func<false, true>("diff_dense", 3.0, 1,
                           [](auto const& ef, const uint64_t x) { return ef.diff(x); });
}
TEST_CASE("diff_sparse")  //
{
    perf_func<false, true>("diff_sparse", 3000.0, 1,
                           [](auto const& ef, const uint64_t x) { return ef.diff(x); });
}

TEST_CASE("next_geq_dense")  //
{
    perf_func<true, false>("next_geq_dense", 3.0, 3.1,
                           [](auto const& ef, const uint64_t x) { return ef.next_geq(x); });
}
TEST_CASE("next_geq_sparse")  //
{
    perf_func<true, false>("next_geq_sparse", 3000.0, 3001.0,
                           [](auto const& ef, const uint64_t x) { return ef.next_geq(x); });
}

TEST_CASE("prev_leq_dense")  //
{
    perf_func<true, false>("prev_leq_dense", 3.0, 3.1,
                           [](auto const& ef, const uint64_t x) { return ef.prev_leq(x); });
}
TEST_CASE("prev_leq_sparse")  //
{
    perf_func<true, false>("prev_leq_sparse", 3000.0, 3001.0,
                           [](auto const& ef, const uint64_t x) { return ef.prev_leq(x); });
}

TEST_CASE("locate_dense")  //
{
    perf_func<true, false>("locate_dense", 3.0, 3.1,
                           [](auto const& ef, const uint64_t x) { return ef.locate(x); });
}
TEST_CASE("locate_sparse")  //
{
    perf_func<true, false>("locate_sparse", 3000.0, 3001.0,
                           [](auto const& ef, const uint64_t x) { return ef.locate(x); });
}
