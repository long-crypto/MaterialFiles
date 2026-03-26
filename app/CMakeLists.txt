cmake_minimum_required(VERSION 3.6)

project(MaterialFiles C CXX)

include(${CMAKE_CURRENT_LIST_DIR}/src/main/third_party/7zip_sources.cmake)

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY})

add_library(sevenzip SHARED
        src/main/jni/sevenzip_bridge.cpp
        ${SEVENZIP_SOURCES})
target_compile_definitions(sevenzip
        PRIVATE
        Z7_ST
        Z7_EXTERNAL_CODECS
        _REENTRANT
        _FILE_OFFSET_BITS=64
        _LARGEFILE_SOURCE)
target_include_directories(sevenzip
        PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src/main/third_party/7zip)
set_target_properties(sevenzip
        PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED YES
        CXX_EXTENSIONS NO)
target_link_libraries(sevenzip
        PRIVATE
        ${LOG_LIBRARY})
