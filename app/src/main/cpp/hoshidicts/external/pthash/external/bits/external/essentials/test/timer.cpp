#include <iostream>

#include "../include/essentials.hpp"

using namespace essentials;

std::vector<uint64_t> random_sequence(uint64_t n, uint64_t u) {
    std::vector<uint64_t> vec;
    vec.reserve(n);
    uniform_int_rng<uint64_t> r(0, u);
    for (uint64_t i = 0; i != n; ++i) {
        vec.push_back(r.gen());
    }
    return vec;
}

int main() {
    std::cout << "max resident set size: " << essentials::maxrss_in_bytes() << " bytes\n";

    timer_type t;

    const uint64_t runs = 50;
    const uint64_t m = 1000000;
    const uint64_t n = 10000000;
    const uint64_t u = 100000000;
    auto sequence = random_sequence(n, u);
    auto queries = random_sequence(m, n);
    double avg = 0.0;

    for (uint64_t run = 0; run != runs; ++run) {
        t.start();
        for (auto i : queries) {
            do_not_optimize_away(sequence[i]);
        }
        t.stop();
    }

    t.discard_min();
    t.discard_max();
    avg = t.average();

    std::cout << "\tMean per run: " << avg / duration_type::period::ratio::den << " [sec]\n";
    std::cout << "\tMean per query: " << avg / m << " [musec]";
    std::cout << std::endl;

    // reset and peform another experiment
    t.reset();
    assert(t.runs() == 0);

    for (uint64_t run = 0; run != runs / 10; ++run) {
        t.start();
        for (auto i : queries) {
            do_not_optimize_away(sequence[i]);
        }
        t.stop();
    }

    t.discard_min();
    t.discard_max();
    avg = t.average();

    std::cout << "\tMean per run: " << avg / duration_type::period::ratio::den << " [sec]\n";
    std::cout << "\tMean per query: " << avg / m << " [musec]";
    std::cout << std::endl;

    std::cout << "max resident set size: " << essentials::maxrss_in_bytes() << " bytes\n";

    return 0;
}
