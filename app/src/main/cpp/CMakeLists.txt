cmake_minimum_required(VERSION 3.22.1)

project(tset_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HOSHIDICTS_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/hoshidicts)

file(READ ${HOSHIDICTS_ROOT}/external/kanji-processor/src/full_list.json KANJI_MAPPING_JSON)
configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/kanji_mapping_data.hpp.in
    ${CMAKE_CURRENT_BINARY_DIR}/generated/kanji_mapping_data.hpp
    @ONLY
)

set(CMAKE_DISABLE_TESTING ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_SHARED_LIB OFF CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_GZIP OFF CACHE BOOL "" FORCE)
set(UTF8PROC_INSTALL OFF CACHE BOOL "" FORCE)

add_subdirectory(${HOSHIDICTS_ROOT}/external/glaze ${CMAKE_BINARY_DIR}/hoshi_glaze)
add_subdirectory(${HOSHIDICTS_ROOT}/external/pthash ${CMAKE_BINARY_DIR}/hoshi_pthash)
add_subdirectory(${HOSHIDICTS_ROOT}/external/zstd ${CMAKE_BINARY_DIR}/hoshi_zstd)
add_subdirectory(${HOSHIDICTS_ROOT}/external/unordered_dense ${CMAKE_BINARY_DIR}/hoshi_unordered_dense)
add_subdirectory(${HOSHIDICTS_ROOT}/external/libdeflate ${CMAKE_BINARY_DIR}/hoshi_libdeflate)
add_subdirectory(${HOSHIDICTS_ROOT}/external/utf8proc ${CMAKE_BINARY_DIR}/hoshi_utf8proc)

add_library(hoshidicts_core STATIC
    ${HOSHIDICTS_ROOT}/src/hash/hash.cpp
    ${HOSHIDICTS_ROOT}/src/hash/bloom.cpp
    ${HOSHIDICTS_ROOT}/src/hash/legacy_hash.cpp
    ${HOSHIDICTS_ROOT}/src/importer.cpp
    ${HOSHIDICTS_ROOT}/src/memory/memory.cpp
    ${HOSHIDICTS_ROOT}/src/zip/zip.cpp
    ${HOSHIDICTS_ROOT}/src/json/yomitan_parser.cpp
    ${HOSHIDICTS_ROOT}/src/text_processor/text_processor.cpp
    ${HOSHIDICTS_ROOT}/src/deinflector.cpp
    ${HOSHIDICTS_ROOT}/src/query.cpp
    ${HOSHIDICTS_ROOT}/src/lookup.cpp
)

target_include_directories(hoshidicts_core PUBLIC
    ${HOSHIDICTS_ROOT}/include
    ${HOSHIDICTS_ROOT}/src
    ${HOSHIDICTS_ROOT}/external/utfcpp/source
    ${HOSHIDICTS_ROOT}/external/xxHash
    ${CMAKE_CURRENT_BINARY_DIR}/generated
)

target_link_libraries(hoshidicts_core PRIVATE
    glaze::glaze
    PTHASH
    libzstd_static
    libdeflate_static
    unordered_dense
    utf8proc::utf8proc
)

add_library(tset_native SHARED
    hoshidicts_jni.cpp
)

target_link_libraries(tset_native PRIVATE
    hoshidicts_core
)
