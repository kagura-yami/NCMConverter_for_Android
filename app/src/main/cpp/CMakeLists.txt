# NCM Decrypt Native Library
# 
# This CMakeLists.txt configures the build for libncmdecrypt.so
# which provides AES decryption, RC4 key expansion, and audio extraction.

cmake_minimum_required(VERSION 3.22.1)

project("ncmdecrypt")

# C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define the native library
add_library(
    ncmdecrypt
    SHARED
    jni_bridge.cpp
    ncm_decrypt.cpp
)

# Find required libraries
find_library(
    log-lib
    log
)

# Link libraries
target_link_libraries(
    ncmdecrypt
    ${log-lib}
)
