#include <iostream>

#include "../include/essentials.hpp"

using namespace essentials;

int main() {
#if defined(__CYGWIN__) || defined(_WIN32) || defined(_WIN64)
#else
    directory dir("..");
    std::cout << "directory name: '" << dir.name() << "'" << std::endl;
    std::cout << "found " << dir.items() << " items" << std::endl;

    for (auto const& fn : dir) {
        if (fn.extension == "cpp") {
            std::cout << "---" << std::endl;
            std::cout << fn.name << std::endl;
            std::cout << fn.fullpath << std::endl;
            std::cout << fn.extension << std::endl;
        }
    }

    if (!create_directory("./foo")) {
        return 1;
    }

    create_directory("./foo");  // must fail

    if (!remove_directory("./foo")) {
        return 1;
    }

    if (!remove_directory("./pippo")) {
        std::cout << "directory may not exist or be not empty" << std::endl;
    }

    if (!remove_directory("./data_structure")) {
        std::cout << "directory may not exist or be not empty" << std::endl;
    }
#endif

    return 0;
}
