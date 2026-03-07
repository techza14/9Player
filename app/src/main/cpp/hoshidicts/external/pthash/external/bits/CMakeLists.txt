cmake_minimum_required(VERSION 3.5...4.0)
project(BITS)

if (NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE "Release")
endif ()
MESSAGE(STATUS "CMAKE_BUILD_TYPE: " ${CMAKE_BUILD_TYPE})

if (NOT TARGET BITS)
  add_library(BITS INTERFACE)
  target_include_directories(BITS INTERFACE .)
  target_include_directories(BITS INTERFACE ./include)
  target_compile_features(BITS INTERFACE cxx_std_17)

  MESSAGE(STATUS "Compiling for processor: " ${CMAKE_HOST_SYSTEM_PROCESSOR})

  if (UNIX AND (CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "x86_64"))
    MESSAGE(STATUS "Compiling with flags: -march=native -mbmi2 -msse4.2")
    target_compile_options(BITS INTERFACE -march=native)
    target_compile_options(BITS INTERFACE -mbmi2 -msse4.2) # for hardware popcount and pdep
  endif()

  if (UNIX)
    target_compile_options(BITS INTERFACE -ggdb)
    target_compile_options(BITS INTERFACE -Wall -Wextra -Wno-missing-braces -Wno-unknown-attributes -Wno-unused-function)

    if (BITS_USE_SANITIZERS)
      MESSAGE(STATUS "Using sanitizers. Compiling with flags: -fsanitize=address -fno-omit-frame-pointer")
      target_compile_options(BITS INTERFACE -fsanitize=address -fno-omit-frame-pointer)
    endif()

    if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    MESSAGE(STATUS "Compiling with flags: -O0")
      target_compile_options(BITS INTERFACE -O0)
    endif()

  endif()

  add_subdirectory(external/essentials)
  target_link_libraries(BITS INTERFACE ESSENTIALS)

endif()

# Only add tests when compiling BITS itself, not when added as a dependency
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  enable_testing()
  set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
  file(GLOB TEST_SOURCES test/test_*.cpp)
  foreach(TEST_SRC ${TEST_SOURCES})
    get_filename_component (TEST_SRC_NAME ${TEST_SRC} NAME_WE) # without extension
    add_executable(${TEST_SRC_NAME} ${TEST_SRC})
    add_test(${TEST_SRC_NAME} ${TEST_SRC_NAME})
    target_link_libraries(${TEST_SRC_NAME} PRIVATE BITS)
  endforeach(TEST_SRC)
endif()

# Only add benchmarks when compiling BITS itself, not when added as a dependency
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
  file(GLOB PERF_SOURCES perf/perf_*.cpp)
  foreach(PERF_SRC ${PERF_SOURCES})
    get_filename_component (PERF_SRC_NAME ${PERF_SRC} NAME_WE) # without extension
    add_executable(${PERF_SRC_NAME} ${PERF_SRC})
    target_link_libraries(${PERF_SRC_NAME} PRIVATE BITS)
  endforeach(PERF_SRC)
endif()
