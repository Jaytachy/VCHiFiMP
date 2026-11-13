cmake_minimum_required(VERSION 3.22.1)
project(usbdirect C)

add_library(usbdirect SHARED
        usb_audio_engine.c
        jni_bridge.c)

target_include_directories(usbdirect PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(usbdirect PRIVATE ${log-lib})

# Android 15+ / API 35+ requires 16 KB page alignment for native libraries.
target_link_options(usbdirect PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
target_compile_options(usbdirect PRIVATE -Wall -Wextra)
