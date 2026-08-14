#include <windows.h>

#include <chrono>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <filesystem>
#include <thread>
#include <string>

using CreateFn = void* (__cdecl*)(const char*, const char*, const char*, void*, int, int, int);
using DestroyFn = void (__cdecl*)(void*);
using RequestFn = int (__cdecl*)(void*);
using DiagnosticsFn = const char* (__cdecl*)(void*);
using PollBridgeFn = const char* (__cdecl*)(void*, std::uint64_t*);
using CompleteBridgeFn = int (__cdecl*)(void*, std::uint64_t, const char*);

namespace {
std::string RequestId(const std::string& request) {
  const std::string marker = "\"id\":\"";
  const auto begin = request.find(marker);
  if (begin == std::string::npos) return "smoke";
  const auto value_begin = begin + marker.size();
  const auto end = request.find('"', value_begin);
  return end == std::string::npos ? "smoke" : request.substr(value_begin, end - value_begin);
}

std::string SmokeBridgeResponse(const std::string& request) {
  if (request.find("\"type\":\"handshake\"") != std::string::npos) {
    return "{\"version\":1,\"type\":\"handshake\",\"runtime\":\"mcwebui-smoke\",\"capabilities\":[\"HANDSHAKE\",\"RPC\",\"STATE\",\"EVENTS\"]}";
  }
  if (request.find("\"type\":\"request\"") != std::string::npos) {
    return "{\"version\":1,\"type\":\"response\",\"id\":\"" + RequestId(request) +
           "\",\"success\":true,\"payload\":{}}";
  }
  return "{}";
}
}

int main(int argc, char** argv) {
  std::fprintf(stderr, "smoke: start argc=%d\n", argc);
  std::fflush(stderr);
  if (argc < 5) {
    std::fprintf(stderr, "usage: smoke <runtime-bin> <url> <cache-dir> <helper> [duration-ms]\n");
    return 2;
  }
  const auto dll = std::filesystem::path(argv[1]) / "mcwebui-direct-cef.dll";
  HMODULE module = LoadLibraryW(dll.wstring().c_str());
  std::fprintf(stderr, "smoke: loaded module=%p err=%lu\n", module, module ? 0UL : GetLastError());
  std::fflush(stderr);
  if (!module) {
    std::fprintf(stderr, "LoadLibrary failed: %lu\n", GetLastError());
    return 3;
  }
  auto create = reinterpret_cast<CreateFn>(GetProcAddress(module, "mcwebui_direct_cef_create"));
  auto destroy = reinterpret_cast<DestroyFn>(GetProcAddress(module, "mcwebui_direct_cef_destroy"));
  auto request = reinterpret_cast<RequestFn>(GetProcAddress(module, "mcwebui_direct_cef_request_frame"));
  auto diagnostics = reinterpret_cast<DiagnosticsFn>(GetProcAddress(module, "mcwebui_direct_cef_diagnostics"));
  auto poll_bridge = reinterpret_cast<PollBridgeFn>(GetProcAddress(module, "mcwebui_direct_cef_poll_bridge"));
  auto complete_bridge = reinterpret_cast<CompleteBridgeFn>(GetProcAddress(module, "mcwebui_direct_cef_complete_bridge"));
  if (!create || !destroy || !request || !diagnostics || !poll_bridge || !complete_bridge) {
    std::fprintf(stderr, "runtime C ABI exports missing\n");
    FreeLibrary(module);
    return 4;
  }
  const int duration = argc >= 6 ? std::max(1, std::atoi(argv[5])) : 1500;
  std::fprintf(stderr, "smoke: creating runtime\n");
  std::fflush(stderr);
  void* runtime = create(argv[2], argv[3], argv[4], nullptr, 640, 360, 60);
  std::fprintf(stderr, "smoke: created runtime=%p\n", runtime);
  std::fflush(stderr);
  if (!runtime) {
    std::fprintf(stderr, "create failed\n");
    FreeLibrary(module);
    return 5;
  }
  request(runtime);
  std::fprintf(stderr, "smoke: requested frame\n");
  std::fflush(stderr);
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(duration);
  while (std::chrono::steady_clock::now() < deadline) {
    std::uint64_t query_id = 0;
    while (const char* query = poll_bridge(runtime, &query_id)) {
      const std::string response = SmokeBridgeResponse(query);
      complete_bridge(runtime, query_id, response.c_str());
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  std::printf("%s\n", diagnostics(runtime));
  std::fflush(stdout);
  std::fprintf(stderr, "smoke: destroying\n");
  std::fflush(stderr);
  destroy(runtime);
  std::fprintf(stderr, "smoke: destroyed\n");
  std::fflush(stderr);
  // Do not unload libcef while Chromium's process-global shutdown callbacks may
  // still be draining. The runtime destructor already completed CefShutdown;
  // the OS safely releases the host DLL when this short-lived probe exits.
  return 0;
}
