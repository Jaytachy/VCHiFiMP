cmake_minimum_required(VERSION 3.22.1)
project(usbdirect C)

add_library(samplerate STATIC
        libsamplerate/src/samplerate.c
        libsamplerate/src/src_linear.c
        libsamplerate/src/src_sinc.c
        libsamplerate/src/src_zoh.c)
target_include_directories(samplerate PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/libsamplerate/include
        ${CMAKE_CURRENT_SOURCE_DIR}/libsamplerate/src)
target_compile_definitions(samplerate PRIVATE _GNU_SOURCE HAVE_CONFIG_H=1)
set_target_properties(samplerate PROPERTIES C_STANDARD 99 C_STANDARD_REQUIRED ON)

add_library(usbdirect SHARED
        usb_audio_engine.c
        uae_gate.c
        uae_dsp.c
        uae_mixer_dsp.c
        uae_pack.c
        jni_bridge.c
        resampler.c
        resampler_bridge.c)

set_target_properties(usbdirect PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)

target_include_directories(usbdirect PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(usbdirect PRIVATE ${log-lib} samplerate)

# Android 15+ / API 35+ requires 16 KB page alignment for native libraries.
target_link_options(usbdirect PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
target_compile_options(usbdirect PRIVATE -Wall -Wextra)
