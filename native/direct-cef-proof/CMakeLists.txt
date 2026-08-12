cmake_minimum_required(VERSION 3.21)

project(mcwebui_direct_cef_proof LANGUAGES CXX)

if(NOT DEFINED CEF_ROOT)
  if(DEFINED ENV{CEF_ROOT})
    set(CEF_ROOT "$ENV{CEF_ROOT}")
  else()
    message(FATAL_ERROR "CEF_ROOT must point at the exact CEF 116/5845 binary distribution")
  endif()
endif()
get_filename_component(CEF_ROOT "${CEF_ROOT}" ABSOLUTE)
if(NOT EXISTS "${CEF_ROOT}/include/cef_api_hash.h")
  message(FATAL_ERROR "CEF_ROOT does not contain the expected CEF headers: ${CEF_ROOT}")
endif()
set(CMAKE_MODULE_PATH "${CEF_ROOT}/cmake")
find_package(CEF REQUIRED)

# Use the wrapper and configuration helpers shipped by the selected CEF SDK.
add_subdirectory("${CEF_LIBCEF_DLL_WRAPPER_PATH}" libcef_dll_wrapper)
ADD_LOGICAL_TARGET("libcef_lib" "${CEF_LIB_DEBUG}" "${CEF_LIB_RELEASE}")

set(PROOF_SOURCES
  src/main.cpp
  src/ProofApp.cpp
  src/ProofApp.h
  src/ProofClient.cpp
  src/ProofClient.h
  src/ProofRenderHandler.cpp
  src/ProofRenderHandler.h
  src/ProofMetrics.cpp
  src/ProofMetrics.h
)

add_executable(mcwebui_direct_cef_proof ${PROOF_SOURCES})
add_dependencies(mcwebui_direct_cef_proof libcef_dll_wrapper)
target_include_directories(mcwebui_direct_cef_proof PRIVATE
  "${CEF_ROOT}"
  "${CEF_INCLUDE_PATH}"
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
)
target_compile_features(mcwebui_direct_cef_proof PRIVATE cxx_std_17)
target_compile_definitions(mcwebui_direct_cef_proof PRIVATE
  ${CEF_COMPILER_DEFINES}
  $<$<CONFIG:Debug>:${CEF_COMPILER_DEFINES_DEBUG}>
  $<$<CONFIG:Release>:${CEF_COMPILER_DEFINES_RELEASE}>
)
target_compile_options(mcwebui_direct_cef_proof PRIVATE
  ${CEF_COMPILER_FLAGS} ${CEF_CXX_COMPILER_FLAGS}
  $<$<CONFIG:Debug>:${CEF_COMPILER_FLAGS_DEBUG} ${CEF_CXX_COMPILER_FLAGS_DEBUG}>
  $<$<CONFIG:Release>:${CEF_COMPILER_FLAGS_RELEASE} ${CEF_CXX_COMPILER_FLAGS_RELEASE}>
)
target_link_libraries(mcwebui_direct_cef_proof PRIVATE
  libcef_lib libcef_dll_wrapper ${CEF_STANDARD_LIBS}
)
if(WIN32)
  target_link_libraries(mcwebui_direct_cef_proof PRIVATE d3d11.lib)
endif()
SET_EXECUTABLE_TARGET_PROPERTIES(mcwebui_direct_cef_proof)

if(WIN32)
  # The runtime files are intentionally copied only to the ignored build tree.
  # CEF SDK/runtime binaries must never be committed to MCWebUI.
  COPY_FILES(mcwebui_direct_cef_proof "${CEF_BINARY_FILES}" "${CEF_BINARY_DIR}" "${CEF_TARGET_OUT_DIR}")
  COPY_FILES(mcwebui_direct_cef_proof "${CEF_RESOURCE_FILES}" "${CEF_RESOURCE_DIR}" "${CEF_TARGET_OUT_DIR}")
endif()

set_target_properties(mcwebui_direct_cef_proof PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
)

message(STATUS "MCWebUI Direct CEF proof uses CEF_ROOT=${CEF_ROOT}")
message(STATUS "Modes: BACKEND_DEFAULT, EXTERNAL_BEGIN_FRAME, optional accelerated OSR probe")
