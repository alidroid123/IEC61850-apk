cmake_minimum_required(VERSION 3.18.1)
project(sipropln_dfr)

# v1 - Fase 3: IEC 61850 DFR Downloader native build.
#
# Vendors mz-automation/libiec61850 (v1.6, GPLv3 - see libiec61850/COPYING)
# as a client-only static library, then builds a small JNI bridge
# (dfr_jni.c) on top of it that exposes just what the Android app needs:
# connect / disconnect / list directory / download file over MMS.
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

add_subdirectory(libiec61850)

add_library(dfr_jni SHARED dfr_jni.c)

target_include_directories(dfr_jni PRIVATE ${LIB61850_INCLUDE_DIRS})

find_library(log-lib log)

target_link_libraries(dfr_jni
    iec61850
    ${log-lib}
)
