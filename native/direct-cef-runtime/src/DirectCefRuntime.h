#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include <windows.h>

#include <d3d11.h>
#include <d3d11_1.h>
#include <wrl/client.h>

#include "include/cef_app.h"
#include "include/cef_browser.h"
#include "include/cef_client.h"
#include "include/cef_render_handler.h"
#include "include/cef_version.h"

class RuntimeRenderHandler;
class RuntimeClient;

class DirectCefRuntime {
 public:
  static std::unique_ptr<DirectCefRuntime> Create(std::string url, std::string cache_dir,
                                                   std::string helper_path, void* parent_window,
                                                   int width, int height, int target_hz);
  ~DirectCefRuntime();
  DirectCefRuntime(const DirectCefRuntime&) = delete;
  DirectCefRuntime& operator=(const DirectCefRuntime&) = delete;

  bool Resize(int width, int height);
  bool SetVisible(bool visible);
  bool RequestFrame();
  bool SetFocus(bool focused);
  bool SendMouseMove(int x, int y, uint32_t modifiers, bool leave);
  bool SendMouseButton(int x, int y, uint32_t modifiers, int button, bool up, int count);
  bool SendMouseWheel(int x, int y, uint32_t modifiers, int delta_x, int delta_y);
  bool SendKey(uint32_t message, uintptr_t wparam, intptr_t lparam);
  bool SendText(const std::u16string& text);

  // Called on Minecraft's render thread while its WGL context is current.
  bool BeginRenderFrame();
  void EndRenderFrame();
  unsigned TextureId() const;
  const char* AlphaMode() const { return "PREMULTIPLIED"; }
  bool YFlipped() const { return true; }
  std::string DiagnosticsJson() const;
  bool Ready() const { return ready_.load(); }

 private:
  friend class RuntimeRenderHandler;
  friend class RuntimeClient;
  DirectCefRuntime(std::string url, std::string cache_dir, std::string helper_path,
                   void* parent_window, int width, int height, int target_hz);
  bool Initialize();
  void CloseBrowser();
  void EnsureD3DDevice();
  bool EnsureGlInterop();
  bool RegisterSlot(int index);
  void UnregisterGlObjects();
  void OnBrowserCreated(CefRefPtr<CefBrowser> browser);
  void OnBrowserClosed();
  void OnAcceleratedPaint(void* handle, unsigned format);

  struct Slot {
    Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;
    HANDLE gl_object = nullptr;
    unsigned gl_name = 0;
    std::uint64_t generation = 0;
    bool locked = false;
    bool writing = false;
  };

  std::string url_;
  std::string cache_dir_;
  std::string helper_path_;
  HWND parent_window_ = nullptr;
  int width_;
  int height_;
  int target_hz_;
  HWND host_window_ = nullptr;
  std::atomic<bool> ready_{false};
  std::atomic<bool> closing_{false};
  std::atomic<std::uint64_t> accelerated_callbacks_{0};
  std::atomic<std::uint64_t> published_generations_{0};
  std::atomic<std::uint64_t> frame_requests_{0};
  std::atomic<std::uint64_t> copy_failures_{0};
  std::atomic<std::uint64_t> gl_lock_failures_{0};
  mutable std::mutex mutex_;
  mutable std::mutex d3d_mutex_;
  std::condition_variable browser_cv_;
  CefRefPtr<CefBrowser> browser_;
  CefRefPtr<CefClient> client_;
  Microsoft::WRL::ComPtr<ID3D11Device> d3d_device_;
  Microsoft::WRL::ComPtr<ID3D11Device1> d3d_device1_;
  Microsoft::WRL::ComPtr<ID3D11DeviceContext> d3d_context_;
  std::array<Slot, 3> slots_{};
  int latest_slot_ = -1;
  int render_slot_ = -1;
  std::uint64_t next_generation_ = 0;
  unsigned mailbox_width_ = 0;
  unsigned mailbox_height_ = 0;
  DXGI_FORMAT mailbox_format_ = DXGI_FORMAT_UNKNOWN;
  bool resize_pending_ = false;
  bool browser_created_ = false;
  bool shutdown_complete_ = false;
  HANDLE interop_device_ = nullptr;
  bool interop_supported_ = false;
  bool render_locked_ = false;
  unsigned texture_id_ = 0;
  std::string last_error_;
  std::thread::id owner_thread_;
  bool cef_initialized_ = false;

  using WglDXOpenDeviceNV = HANDLE(WINAPI*)(void*);
  using WglDXCloseDeviceNV = BOOL(WINAPI*)(HANDLE);
  using WglDXRegisterObjectNV = HANDLE(WINAPI*)(HANDLE, void*, unsigned int,
                                                unsigned int, unsigned int);
  using WglDXUnregisterObjectNV = BOOL(WINAPI*)(HANDLE, HANDLE);
  using WglDXObjectAccessNV = BOOL(WINAPI*)(HANDLE, unsigned int);
  using WglDXLockObjectsNV = BOOL(WINAPI*)(HANDLE, int, HANDLE*);
  using WglDXUnlockObjectsNV = BOOL(WINAPI*)(HANDLE, int, HANDLE*);
  WglDXOpenDeviceNV wgl_dx_open_device_ = nullptr;
  WglDXCloseDeviceNV wgl_dx_close_device_ = nullptr;
  WglDXRegisterObjectNV wgl_dx_register_object_ = nullptr;
  WglDXUnregisterObjectNV wgl_dx_unregister_object_ = nullptr;
  WglDXObjectAccessNV wgl_dx_object_access_ = nullptr;
  WglDXLockObjectsNV wgl_dx_lock_objects_ = nullptr;
  WglDXUnlockObjectsNV wgl_dx_unlock_objects_ = nullptr;
  bool gl_capability_checked_ = false;
  bool interop_device_open_ = false;
  bool OwnerThread() const { return std::this_thread::get_id() == owner_thread_; }
};
