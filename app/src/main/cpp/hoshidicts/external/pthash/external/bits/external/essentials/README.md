C++ Essentials
==============

A self-contained, header-only, collection of some essential C++ utilities that I use extensively for data structure design and benchmarking.
Meant to be used in larger projects.

#### NOTE: C++17 is needed to use the library.

The library is used in several other C++ libraries, such as

* [Tongrams](https://github.com/jermp/tongrams), a library for compressed n-gram indexes;
* [PTHash](https://github.com/jermp/pthash), a library implementing efficient minimal and perfect hash functions;
* [Auto-Complete](https://github.com/jermp/autocomplete), a library for fast and effective query auto-completion;
* [Bit-Sliced Indexes](https://github.com/jermp/s_indexes), a library providing fast compressed bitmaps.


Integration
-----------

Integrating Essentials in your own project is very simple: just get the source code
and include the header `include/essentials.hpp` in your code.
No other configurations are needed.

If you use `git`, the easiest way to add Essentials is via `git add submodule` as follows.

	git submodule add https://github.com/jermp/essentials.git

#### CMake

If you are using `CMake`, you can include the project as follows:

    add_subdirectory(path/to/essentials)
    target_link_libraries(YourTarget PRIVATE ESSENTIALS)
    
See also this example [repository](https://github.com/jermp/essentials-cmake-test).

What's inside?
--------------

* Benchmarking facilities, including: messages displaying local time,
configurable timer class, function to prevent code elision by compiler,
simple creation and printing of json documents.
* Functions to serialize-to and load-from disk data structures.
* Functions to compute the number of bytes consumed by data structures.
* Support for creating, removing, and iterate inside directories.

* **Experimental**: transparent support for contiguous memory allocation.


Examples
--------

See the `test` folder for examples.
To compile such examples, just type the following commands from the parent directory.

    cd test
    mkdir build
    cd build
	cmake ..
	make
