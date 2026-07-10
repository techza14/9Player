cmake_minimum_required(VERSION 3.22.1)

project(tset_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HOSHIDICTS_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/hoshidicts)

set(CMAKE_DISABLE_TESTING ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZIP_STATIC_PIC ON CACHE BOOL "" FORCE)

add_subdirectory(${HOSHIDICTS_ROOT}/external/zip ${CMAKE_BINARY_DIR}/hoshi_zip)
add_subdirectory(${HOSHIDICTS_ROOT}/external/glaze ${CMAKE_BINARY_DIR}/hoshi_glaze)
add_subdirectory(${HOSHIDICTS_ROOT}/external/pthash ${CMAKE_BINARY_DIR}/hoshi_pthash)
add_subdirectory(${HOSHIDICTS_ROOT}/external/zstd ${CMAKE_BINARY_DIR}/hoshi_zstd)

target_compile_definitions(zip PRIVATE MINIZ_DISABLE_ZIP_READER_CRC32_CHECKS)

add_library(hoshidicts_core STATIC
    ${HOSHIDICTS_ROOT}/src/hash/hash.cpp
    ${HOSHIDICTS_ROOT}/src/importer.cpp
    ${HOSHIDICTS_ROOT}/src/json/yomitan_parser.cpp
    ${HOSHIDICTS_ROOT}/src/text_processor/text_processor.cpp
    ${HOSHIDICTS_ROOT}/src/deconjugator/deconjugator.cpp
    ${HOSHIDICTS_ROOT}/src/query.cpp
    ${HOSHIDICTS_ROOT}/src/lookup.cpp
)

target_include_directories(hoshidicts_core PUBLIC
    ${HOSHIDICTS_ROOT}/include
    ${HOSHIDICTS_ROOT}/src
    ${HOSHIDICTS_ROOT}/external/utfcpp/source
)

target_link_libraries(hoshidicts_core PRIVATE
    PTHASH
    zip
    glaze::glaze
    libzstd_static
)

add_library(tset_native SHARED
    hoshidicts_jni.cpp
)

target_link_libraries(tset_native PRIVATE
    hoshidicts_core
)
