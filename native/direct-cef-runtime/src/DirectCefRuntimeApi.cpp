#include "DirectCefRuntime.h"

#include <memory>
#include <cstdio>
#include <string>

// Small C ABI used only by the bounded native lifecycle smoke.  The production
// NeoForge path uses the JNI exports; keeping this probe ABI separate avoids
// coupling the harness to JVM startup while exercising the same runtime class.
extern "C" __declspec(dllexport) void* mcwebui_direct_cef_create(
    const char* url, const char* cache_dir, const char* helper_path, void* parent_window,
    int width, int height, int target_hz) {
  std::fprintf(stderr, "runtime-api: Create enter\n"); std::fflush(stderr);
  auto runtime = DirectCefRuntime::Create(url ? url : "", cache_dir ? cache_dir : "",
                                          helper_path ? helper_path : "", parent_window,
                                          width, height, target_hz);
  std::fprintf(stderr, "runtime-api: Create return %p\n", runtime.get()); std::fflush(stderr);
  return runtime ? runtime.release() : nullptr;
}

extern "C" __declspec(dllexport) void mcwebui_direct_cef_destroy(void* handle) {
  delete static_cast<DirectCefRuntime*>(handle);
}

extern "C" __declspec(dllexport) int mcwebui_direct_cef_request_frame(void* handle) {
  return handle && static_cast<DirectCefRuntime*>(handle)->RequestFrame() ? 1 : 0;
}

extern "C" __declspec(dllexport) const char* mcwebui_direct_cef_diagnostics(void* handle) {
  thread_local std::string json;
  json = handle ? static_cast<DirectCefRuntime*>(handle)->DiagnosticsJson()
                : "{\"ready\":false}";
  return json.c_str();
}
