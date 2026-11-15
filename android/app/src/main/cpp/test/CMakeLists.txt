# SPDX-License-Identifier: GPL-3.0-or-later
#
# Host-side test project for the native DSP/pack/gate code. The Android build
# uses ../CMakeLists.txt; nothing here is compiled into the app.
#
#   cmake -S android/app/src/main/cpp/test -B build-host-tests
#   cmake --build build-host-tests
#   ctest --test-dir build-host-tests --output-on-failure
#
cmake_minimum_required(VERSION 3.16)
project(uae_host_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)

add_executable(dsp_test dsp_test.c ${CPP_DIR}/uae_dsp.c)
add_executable(pack_test pack_test.c ${CPP_DIR}/uae_pack.c)
add_executable(close_race_test close_race_test.c ${CPP_DIR}/uae_gate.c)
add_executable(mixer_dsp_test mixer_dsp_test.c
        ${CPP_DIR}/uae_mixer_dsp.c ${CPP_DIR}/uae_dsp.c)

foreach(t dsp_test pack_test close_race_test mixer_dsp_test)
    target_include_directories(${t} PRIVATE ${CPP_DIR})
    target_link_libraries(${t} PRIVATE m pthread)
endforeach()

enable_testing()
add_test(NAME dsp COMMAND dsp_test)
add_test(NAME pack COMMAND pack_test)
add_test(NAME close_race COMMAND close_race_test)
add_test(NAME mixer_dsp COMMAND mixer_dsp_test)
