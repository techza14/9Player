#include <iostream>

#include "../include/essentials.hpp"

using namespace essentials;

int main() {
    json_lines jl;

    jl.add("sum", "13");
    double avg = 325.7;
    jl.add("average", avg);

    // print current line
    jl.print_line();

    jl.new_line();
    jl.add("foo", avg);
    jl.add("bar", 8894);
    jl.add("baz", "value");

    std::string str("hi there");
    jl.add("bit", str.c_str());

    // print all lines
    jl.print();

    return 0;
}
