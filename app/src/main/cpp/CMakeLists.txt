cmake_minimum_required(VERSION 3.22.1)
project(aether_jni C)

# libaether.so is produced by cargo-ndk (see scripts/build-core.sh) and dropped
# into src/main/jniLibs/<abi>/. It is imported here rather than built, so the
# Rust toolchain never has to be visible to the Android build.
set(AETHER_LIB ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libaether.so)

if(NOT EXISTS ${AETHER_LIB})
    message(FATAL_ERROR
        "libaether.so is missing for ${ANDROID_ABI}.\n"
        "Run scripts/build-core.sh first, or build without -PaetherNative to use the mock core.")
endif()

add_library(aether SHARED IMPORTED)
# Rust cdylibs carry no SONAME. Without IMPORTED_NO_SONAME CMake links the
# library by its absolute path, the linker records that path in DT_NEEDED, and
# the shim then fails to load on a device. With it, CMake links with
# -laether and DT_NEEDED is the plain libaether.so the apk ships.
set_target_properties(aether PROPERTIES
    IMPORTED_LOCATION ${AETHER_LIB}
    IMPORTED_NO_SONAME TRUE)

add_library(aether_jni SHARED aether_jni.c)
target_link_libraries(aether_jni aether log)
