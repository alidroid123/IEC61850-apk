cmake_minimum_required(VERSION 3.18.1)
project(sipropln_dfr)

# 16 KB page-size alignment for every shared library this build produces (libdfr_jni.so, and
# incidentally libiec61850.so - see the comment above add_subdirectory(libiec61850) below).
# Without this, ELF LOAD segments end up 4 KB-aligned, which devices with a 16 KB memory page
# size (Pixel 8+, and increasingly required for Play Store submissions) refuse to mmap: the app
# doesn't crash at install time, it crashes the moment System.loadLibrary("dfr_jni") runs
# (UnsatisfiedLinkError: "program alignment (4096) cannot be smaller than system page size
# (16384)"). NDK r28+ defaults to this already; setting it explicitly here means the fix doesn't
# depend on whichever NDK version happens to be installed.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# v1 - Fase 3: IEC 61850 DFR Downloader native build.
#
# Vendors mz-automation/libiec61850 (v1.6, GPLv3 - see libiec61850/COPYING)
# as a client-only static library, then builds a small JNI bridge
# (dfr_jni.c) on top of it that exposes just what the Android app needs:
# connect / disconnect / list directory / download file over MMS. A separate
# libunrar_jni.so (bottom of this file) adds .rar extraction.
#
# GOOSE, Sampled Values (L2 raw-ethernet) and TLS are intentionally
# disabled: they need CAP_NET_RAW / mbedtls which add complexity this
# feature (COMTRADE/DFR file download) does not need. Re-enable later
# here if a future feature needs GOOSE trip-event triggering.

# ---- libiec61850 build configuration (mirrors CMakeLists options) ----
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON_BINDINGS OFF CACHE BOOL "" FORCE)
set(CONFIG_INCLUDE_GOOSE_SUPPORT OFF CACHE BOOL "" FORCE)
set(CONFIG_IEC61850_L2_GOOSE OFF CACHE BOOL "" FORCE)
set(CONFIG_IEC61850_L2_SMV OFF CACHE BOOL "" FORCE)
set(CONFIG_IEC61850_R_GOOSE OFF CACHE BOOL "" FORCE)
set(CONFIG_IEC61850_R_SMV OFF CACHE BOOL "" FORCE)
set(CONFIG_IEC61850_SNTP_CLIENT OFF CACHE BOOL "" FORCE)
set(CONFIG_MMS_SINGLE_THREADED ON CACHE BOOL "" FORCE)
set(DEBUG OFF CACHE BOOL "" FORCE)

# Public headers are only propagated within libiec61850's own CMake scope
# (it uses plain include_directories(), not target_include_directories()),
# so we mirror the same paths here for dfr_jni.c to consume.
set(LIB61850_INCLUDE_DIRS
    libiec61850/hal/inc
    libiec61850/src/common/inc
    libiec61850/src/hal/inc
    libiec61850/src/iec61850/inc
    libiec61850/src/mms/inc
    libiec61850/src/logging
    libiec61850/config
    libiec61850/src/mms/iso_mms/asn1c
)

include_directories(${LIB61850_INCLUDE_DIRS})

# libiec61850/src/CMakeLists.txt defines both a STATIC "iec61850" target (the one actually linked
# into dfr_jni below) and a SHARED "iec61850-shared" target. CMake's default "all" target builds
# everything in an added subdirectory, so libiec61850.so ends up built and AGP packages every .so
# it finds - it ships in the APK even though nothing ever calls System.loadLibrary("iec61850").
# Harmless (never loaded, just extra APK size) and left as-is rather than patching the vendored
# CMakeLists.txt to exclude it - see the "source vendored ... not changed" note in the README.
add_subdirectory(libiec61850)

add_library(dfr_jni SHARED dfr_jni.c)

target_include_directories(dfr_jni PRIVATE ${LIB61850_INCLUDE_DIRS})

find_library(log-lib log)

target_link_libraries(dfr_jni
    iec61850
    ${log-lib}
)

# .rar support for opening DFR archives (RarExtractor.java -> unrar_jni.cpp -> unrar_extract.cpp,
# which has no JNI so it can also be built and tested off-device): RARLAB's UnRAR
# 7.3.1, vendored unchanged in unrar/ (freeware licence - see unrar/license.txt), built like its
# makefile's "lib" target. Only the files that makefile compiles are listed; the remaining .cpp
# files in unrar/ are #included by these.
set(UNRAR_SOURCES
    rar strlist strfn pathfn smallfn global file filefn filcreat archive arcread unicode system
    crypt crc rawread encname resource match timefn rdwrfn consio options errhnd rarvm
    secpassword rijndael getbits sha1 sha256 blake2s hash extinfo extract volume list find unpack
    headers threadpool rs16 cmddata ui largepage filestr scantree dll qopen
)
list(TRANSFORM UNRAR_SOURCES PREPEND unrar/)
list(TRANSFORM UNRAR_SOURCES APPEND .cpp)
set_source_files_properties(${UNRAR_SOURCES} PROPERTIES
    COMPILE_OPTIONS "-w;-include;${CMAKE_CURRENT_SOURCE_DIR}/unrar_android_compat.h")

add_library(unrar_jni SHARED unrar_jni.cpp unrar_extract.cpp ${UNRAR_SOURCES})
set_target_properties(unrar_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_definitions(unrar_jni PRIVATE RARDLL RAR_SMP _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE)
target_compile_options(unrar_jni PRIVATE
    -fexceptions -frtti -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(unrar_jni PRIVATE -Wl,--gc-sections)
