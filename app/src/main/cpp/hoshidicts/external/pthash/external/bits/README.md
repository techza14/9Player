Bits: Succinct utilities to handle bits
---------------------------------------

A C++ collection of basic data structures, including:

- bitvectors with rank/select queries;
- compact vectors (vectors of fixed-size integers);
- Elias-Fano sequences;
- integer codes (unary, binary, gamma, delta, Rice).

### Inclusion in other projects

Including `bits` in your own project is very simple: just get the source code
and include the relevant headers in your code.
No other configurations are needed.

If you use `git`, the easiest way to add `bits` is via `git add submodule` as follows.

	git submodule add --recursive https://github.com/jermp/bits.git

#### CMake

If you are using `CMake`, you can include the project as follows:

    add_subdirectory(path/to/bits)
    target_link_libraries(YourTarget INTERFACE BITS)

### Tests

Tests are written using [doctest](https://github.com/doctest/doctest). To run the tests, first compile the tests, with

	git clone --recursive https://github.com/jermp/bits.git
	cd bits
	mkdir build
	cd build
	cmake .. -DCMAKE_BUILD_TYPE=Debug
	make

and then run `make test`.

To run a specific test, use the tool `-tc` of doctest to specify the test case name. For example:

	./test_elias_fano -tc=access