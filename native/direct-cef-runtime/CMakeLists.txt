cmake_minimum_required(VERSION 3.21)

project(mcwebui_direct_cef_runtime LANGUAGES CXX)

if(NOT DEFINED CEF_ROOT)
  if(DEFINED ENV{CEF_ROOT})
    set(CEF_ROOT "$ENV{CEF_ROOT}")
  else()
    message(FATAL_ERROR "CEF_ROOT must point at the pinned CEF binary distribution")
  endif()
endif()
get_filename_component(CEF_ROOT "${CEF_ROOT}" ABSOLUTE)
if(NOT EXISTS "${CEF_ROOT}/include/cef_api_hash.h")
  message(FATAL_ERROR "CEF_ROOT does not contain CEF headers: ${CEF_ROOT}")
endif()

if(NOT DEFINED JAVA_HOME AND DEFINED ENV{JAVA_HOME})
  set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
if(NOT JAVA_HOME)
  message(FATAL_ERROR "JAVA_HOME is required to build the JNI runtime")
endif()
get_filename_component(JAVA_HOME "${JAVA_HOME}" ABSOLUTE)
if(NOT EXISTS "${JAVA_HOME}/include/jni.h")
  message(FATAL_ERROR "JAVA_HOME does not contain include/jni.h: ${JAVA_HOME}")
endif()

set(CMAKE_MODULE_PATH "${CEF_ROOT}/cmake")
find_package(CEF REQUIRED)
add_subdirectory("${CEF_LIBCEF_DLL_WRAPPER_PATH}" libcef_dll_wrapper)
ADD_LOGICAL_TARGET("libcef_lib" "${CEF_LIB_DEBUG}" "${CEF_LIB_RELEASE}")
if(MSVC)
  target_compile_options(libcef_dll_wrapper PRIVATE /wd4819)
endif()

set(RUNTIME_SOURCES
  src/DirectCefRuntime.cpp
  src/DirectCefRuntime.h
  src/DirectCefRuntimeJni.cpp
  src/DirectCefRuntimeApi.cpp
)

add_library(mcwebui_direct_cef SHARED ${RUNTIME_SOURCES})
add_dependencies(mcwebui_direct_cef libcef_dll_wrapper)
target_include_directories(mcwebui_direct_cef PRIVATE
  "${CEF_ROOT}" "${CEF_INCLUDE_PATH}"
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  "${JAVA_HOME}/include"
  "${JAVA_HOME}/include/win32"
)
target_compile_features(mcwebui_direct_cef PRIVATE cxx_std_17)
target_compile_definitions(mcwebui_direct_cef PRIVATE
  ${CEF_COMPILER_DEFINES}
  $<$<CONFIG:Debug>:${CEF_COMPILER_DEFINES_DEBUG}>
  $<$<CONFIG:Release>:${CEF_COMPILER_DEFINES_RELEASE}>
)
target_compile_options(mcwebui_direct_cef PRIVATE
  ${CEF_COMPILER_FLAGS} ${CEF_CXX_COMPILER_FLAGS}
  $<$<CONFIG:Debug>:${CEF_COMPILER_FLAGS_DEBUG} ${CEF_CXX_COMPILER_FLAGS_DEBUG}>
  $<$<CONFIG:Release>:${CEF_COMPILER_FLAGS_RELEASE} ${CEF_CXX_COMPILER_FLAGS_RELEASE}>
)
if(MSVC)
  target_compile_options(mcwebui_direct_cef PRIVATE /wd4819)
endif()
target_link_libraries(mcwebui_direct_cef PRIVATE
  libcef_lib libcef_dll_wrapper ${CEF_STANDARD_LIBS}
  d3d11.lib dxgi.lib opengl32.lib gdi32.lib user32.lib
)
set_target_properties(mcwebui_direct_cef PROPERTIES
  OUTPUT_NAME "mcwebui-direct-cef"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
)

add_executable(mcwebui_cef_helper src/DirectCefHelper.cpp)
target_include_directories(mcwebui_cef_helper PRIVATE "${CEF_ROOT}" "${CEF_INCLUDE_PATH}")
target_compile_features(mcwebui_cef_helper PRIVATE cxx_std_17)
target_compile_definitions(mcwebui_cef_helper PRIVATE
  ${CEF_COMPILER_DEFINES}
  $<$<CONFIG:Debug>:${CEF_COMPILER_DEFINES_DEBUG}>
  $<$<CONFIG:Release>:${CEF_COMPILER_DEFINES_RELEASE}>
)
target_compile_options(mcwebui_cef_helper PRIVATE
  ${CEF_COMPILER_FLAGS} ${CEF_CXX_COMPILER_FLAGS}
  $<$<CONFIG:Debug>:${CEF_COMPILER_FLAGS_DEBUG} ${CEF_CXX_COMPILER_FLAGS_DEBUG}>
  $<$<CONFIG:Release>:${CEF_COMPILER_FLAGS_RELEASE} ${CEF_CXX_COMPILER_FLAGS_RELEASE}>
)
if(MSVC)
  target_compile_options(mcwebui_cef_helper PRIVATE /wd4819)
endif()
target_link_libraries(mcwebui_cef_helper PRIVATE libcef_lib libcef_dll_wrapper ${CEF_STANDARD_LIBS} comctl32.lib)
set_target_properties(mcwebui_cef_helper PROPERTIES
  OUTPUT_NAME "mcwebui-cef-helper"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
)

add_executable(mcwebui_direct_cef_smoke src/DirectCefRuntimeSmoke.cpp)
target_include_directories(mcwebui_direct_cef_smoke PRIVATE "${CEF_ROOT}" "${CEF_INCLUDE_PATH}")
target_compile_features(mcwebui_direct_cef_smoke PRIVATE cxx_std_17)
target_compile_definitions(mcwebui_direct_cef_smoke PRIVATE
  ${CEF_COMPILER_DEFINES}
  $<$<CONFIG:Debug>:${CEF_COMPILER_DEFINES_DEBUG}>
  $<$<CONFIG:Release>:${CEF_COMPILER_DEFINES_RELEASE}>
)
target_compile_options(mcwebui_direct_cef_smoke PRIVATE
  ${CEF_COMPILER_FLAGS} ${CEF_CXX_COMPILER_FLAGS}
  $<$<CONFIG:Debug>:${CEF_COMPILER_FLAGS_DEBUG} ${CEF_CXX_COMPILER_FLAGS_DEBUG}>
  $<$<CONFIG:Release>:${CEF_COMPILER_FLAGS_RELEASE} ${CEF_CXX_COMPILER_FLAGS_RELEASE}>
)
if(MSVC)
  target_compile_options(mcwebui_direct_cef_smoke PRIVATE /wd4819)
endif()
if(WIN32)
  # The smoke is a standalone CEF host and must carry the same compatibility
  # manifest as the proof executable so GPU child-process startup is valid.
  target_link_libraries(mcwebui_direct_cef_smoke PRIVATE comctl32.lib)
endif()
set_target_properties(mcwebui_direct_cef_smoke PROPERTIES
  # Keep the target's underscore name because ADD_WINDOWS_MANIFEST resolves
  # the output from the CMake target identifier.
  OUTPUT_NAME "mcwebui_direct_cef_smoke"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/bin"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
)
if(WIN32)
  # Apply after the explicit output directory/name so CEF's helper macro writes
  # to the actual bin path instead of the CMake source root.
  set(CEF_TARGET_OUT_DIR "${CMAKE_BINARY_DIR}/bin")
  ADD_WINDOWS_MANIFEST("${CMAKE_CURRENT_SOURCE_DIR}/win" "mcwebui_direct_cef_smoke" "exe")
endif()

if(WIN32)
  set(CEF_TARGET_OUT_DIR "${CMAKE_BINARY_DIR}/bin")
  # The DLL is loaded by the Minecraft process and does not embed a PE
  # application manifest; the helper receives the CEF-compatible manifest at
  # packaging time by the runner. Keeping ADD_WINDOWS_MANIFEST off here also
  # avoids CMake's DLL resource update bug on Windows.
  COPY_FILES(mcwebui_direct_cef "${CEF_BINARY_FILES}" "${CEF_BINARY_DIR}" "${CEF_TARGET_OUT_DIR}")
  COPY_FILES(mcwebui_direct_cef "${CEF_RESOURCE_FILES}" "${CEF_RESOURCE_DIR}" "${CEF_TARGET_OUT_DIR}")
  COPY_FILES(mcwebui_cef_helper "${CEF_BINARY_FILES}" "${CEF_BINARY_DIR}" "${CEF_TARGET_OUT_DIR}")
  COPY_FILES(mcwebui_cef_helper "${CEF_RESOURCE_FILES}" "${CEF_RESOURCE_DIR}" "${CEF_TARGET_OUT_DIR}")
endif()

message(STATUS "MCWebUI Direct CEF JNI runtime uses CEF_ROOT=${CEF_ROOT}")
message(STATUS "MCWebUI Direct CEF JNI runtime uses JAVA_HOME=${JAVA_HOME}")
