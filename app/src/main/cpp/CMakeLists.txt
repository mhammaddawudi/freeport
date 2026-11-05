cmake_minimum_required(VERSION 3.22.1)
project(warp_jni C)

# libwarp.so is produced by cargo-ndk (see scripts/build-core.sh) and dropped
# into src/main/jniLibs/<abi>/. It is imported here rather than built, so the
# Rust toolchain never has to be visible to the Android build.
set(WARP_LIB ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwarp.so)

if(NOT EXISTS ${WARP_LIB})
    message(FATAL_ERROR
        "libwarp.so is missing for ${ANDROID_ABI}.\n"
        "Run scripts/build-core.sh first, or build without -PwarpNative to use the mock core.")
endif()

add_library(warp SHARED IMPORTED)
# Rust cdylibs carry no SONAME. Without IMPORTED_NO_SONAME CMake links the
# library by its absolute path, the linker records that path in DT_NEEDED, and
# the shim then fails to load on a device. With it, CMake links with
# -lwarp and DT_NEEDED is the plain libwarp.so the apk ships.
set_target_properties(warp PROPERTIES
    IMPORTED_LOCATION ${WARP_LIB}
    IMPORTED_NO_SONAME TRUE)

add_library(warp_jni SHARED warp_jni.c)
target_link_libraries(warp_jni warp log)
