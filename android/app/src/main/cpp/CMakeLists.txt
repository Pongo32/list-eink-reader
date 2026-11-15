cmake_minimum_required(VERSION 3.18.1)
project(reader_native C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
add_compile_definitions(USE_CMAKE)

set(ZLIB_FOUND FALSE)
set(PNG_FOUND FALSE)
set(JPEG_FOUND FALSE)
set(JASPER_FOUND FALSE)
set(GHOSTSCRIPT_EXECUTABLE "")
set(MUPDF_FOUND FALSE)
set(DJVU_FOUND FALSE)

set(K2_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/k2pdfopt")
set(K2_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/k2pdfopt")
file(MAKE_DIRECTORY "${K2_CONFIG_DIR}")
configure_file("${K2_SOURCE_DIR}/config.h.in" "${K2_CONFIG_DIR}/config.h")

include_directories(
    "${K2_SOURCE_DIR}"
    "${K2_SOURCE_DIR}/willuslib"
    "${K2_SOURCE_DIR}/k2pdfoptlib"
    "${K2_CONFIG_DIR}"
)

add_subdirectory(k2pdfopt/willuslib)
add_subdirectory(k2pdfopt/k2pdfoptlib)

add_library(k2pdfopt_jni SHARED k2pdfopt_jni.c)
target_link_libraries(k2pdfopt_jni k2pdfoptlib willuslib m)
