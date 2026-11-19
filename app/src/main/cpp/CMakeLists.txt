cmake_minimum_required(VERSION 3.18)
project(quasar_native C CXX)

add_library(quasar_native SHARED
        quasar_native.cpp)

target_compile_features(quasar_native PRIVATE cxx_std_17)
target_compile_options(quasar_native PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(quasar_native PRIVATE android log m)

# The launcher is a standalone PIE executable. It is built by build.sh with
# the NDK clang driver and packaged as an asset; CMake intentionally does not
# install it into the APK's lib directory.
set(QUASAR_LAUNCHER_SOURCE ${CMAKE_CURRENT_SOURCE_DIR}/quasar_launcher.c)