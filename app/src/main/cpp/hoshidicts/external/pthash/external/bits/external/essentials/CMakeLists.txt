cmake_minimum_required(VERSION 3.10...4.0)

project(ESSENTIALS)
set(PROJECT_VENDOR "Giulio Ermanno Pibiri")
set(PROJECT_URL "https://github.com/jermp/essentials")
set(PROJECT_DESCRIPTION "Essential C++ utilities.")

if(NOT TARGET ESSENTIALS)
    add_library(ESSENTIALS INTERFACE)
    target_include_directories(ESSENTIALS INTERFACE include)
endif()
