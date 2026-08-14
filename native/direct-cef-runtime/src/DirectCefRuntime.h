#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>

#include <windows.h>

#include <d3d11.h>
#include <d3d11_1.h>
#include <wrl/client.h>

#include "include/cef_app.h"
#include "include/cef_browser.h"
#include "include/cef_client.h"
#include "include/cef_render_handler.h"
#include "include/cef_version.h"
#include "include/wrapper/cef_message_router.h"

class RuntimeRenderHandler;
class RuntimeClient;
class RuntimeBridgeHandler;

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
  bool RefreshGlContext();
  bool RequestFrame();
  bool SetFocus(bool focused);
  bool SetMouseButtons(uint32_t buttons);
  bool SendMouseMove(int x, int y, uint32_t modifiers, bool leave);
  bool SendMouseButton(int x, int y, uint32_t modifiers, int button, bool up, int count);
  bool SendMouseWheel(int x, int y, uint32_t modifiers, int delta_x, int delta_y);
  bool SendKey(uint32_t message, uintptr_t wparam, intptr_t lparam);
  bool SendText(const std::u16string& text);

  struct BridgeQuery {
    std::uint64_t id = 0;
    std::string request;
  };
  bool PollBridgeQuery(BridgeQuery* query);
  bool CompleteBridgeQuery(std::uint64_t id, const std::string& response,
                           int error_code = 0, const std::string& error_message = {});
  bool DeliverBridgeMessage(std::uint64_t navigation_epoch,
                            const std::string& encoded_message);
  std::uint64_t BridgeNavigationEpoch() const { return bridge_navigation_epoch_.load(); }

  // Called on Minecraft's render thread while its WGL context is current.
  bool BeginRenderFrame();
  void EndRenderFrame();
  // Called by the target immediately after its draw call succeeds while the
  // render lease is active. Keeping this separate from BeginRenderFrame lets
  // diagnostics distinguish a locked texture from one actually submitted by
  // Minecraft's renderer.
  bool MarkFrameDrawn();
  unsigned TextureId() const;
  const char* AlphaMode() const { return "PREMULTIPLIED"; }
  // Minecraft's GUI projection is top-left-oriented. The standalone proof has
  // its own bottom-left presentation transform and keeps that policy separate.
  bool YFlipped() const { return false; }
  std::string DiagnosticsJson() const;
  bool Ready() const { return ready_.load(); }

 private:
  friend class RuntimeRenderHandler;
  friend class RuntimeClient;
  friend class RuntimeBridgeHandler;
  DirectCefRuntime(std::string url, std::string cache_dir, std::string helper_path,
                   void* parent_window, int width, int height, int target_hz);
  bool Initialize();
  void CloseBrowser();
  void EnsureD3DDevice();
  bool EnsureGlInterop();
  bool RebindGlContextIfNeeded();
  bool RegisterSlot(int index);
  void UnregisterGlObjects();
  void OnBrowserCreated(CefRefPtr<CefBrowser> browser);
  void OnBrowserClosed();
  void OnAcceleratedPaint(void* handle, unsigned format);
  bool OnBridgeQuery(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                     std::int64_t cef_query_id, const std::string& request,
                     CefRefPtr<CefMessageRouterBrowserSide::Callback> callback);
  void OnBridgeQueryCanceled(std::int64_t cef_query_id);
  void OnBridgeNavigationStart(const std::string& url);
  void OnBridgeBootstrapInstalled(CefRefPtr<CefBrowser> browser);
  bool IsTrustedBridgeUrl(const std::string& url) const;

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
  std::string trusted_url_key_;
  HWND parent_window_ = nullptr;
  std::atomic<int> width_;
  std::atomic<int> height_;
  int target_hz_;
  HWND host_window_ = nullptr;
  std::atomic<bool> ready_{false};
  std::atomic<bool> closing_{false};
  std::atomic<std::uint64_t> accelerated_callbacks_{0};
  std::atomic<std::uint64_t> published_generations_{0};
  std::atomic<std::uint64_t> frame_requests_{0};
  std::atomic<std::uint64_t> copy_failures_{0};
  std::atomic<std::uint64_t> gl_lock_failures_{0};
  std::atomic<std::uint64_t> gl_context_rebinds_{0};
  std::atomic<bool> visible_{true};
  std::atomic<bool> interop_device_open_{false};
  std::atomic<bool> interop_supported_{false};
  std::atomic<std::uint64_t> registration_failures_{0};
  std::atomic<std::uint64_t> render_begin_attempts_{0};
  std::atomic<std::uint64_t> render_begin_successes_{0};
  std::atomic<std::uint64_t> render_ends_{0};
  std::atomic<std::uint64_t> interop_locks_{0};
  std::atomic<std::uint64_t> interop_unlocks_{0};
  std::atomic<std::uint64_t> interop_lock_failures_{0};
  std::atomic<std::uint64_t> interop_unlock_failures_{0};
  std::atomic<std::uint64_t> registered_slots_{0};
  // drawn_generations and repeated_draws are mutually exclusive: a successful
  // target draw increments the former only for a new producer generation,
  // and the latter when it redraws the retained generation.
  std::atomic<std::uint64_t> drawn_generations_{0};
  std::atomic<std::uint64_t> repeated_draws_{0};
  std::atomic<std::uint64_t> dropped_producer_frames_{0};
  std::atomic<std::uint64_t> resize_count_{0};
  std::atomic<std::uint64_t> context_refresh_count_{0};
  std::atomic<std::uint64_t> current_texture_generation_{0};
  std::atomic<bool> marker_gl_context_ready_{false};
  std::atomic<bool> marker_interop_ready_{false};
  std::atomic<bool> marker_mailbox_registered_{false};
  std::atomic<bool> marker_first_lease_{false};
  std::atomic<bool> marker_first_draw_{false};
  std::atomic<std::uint64_t> bridge_navigation_epoch_{0};
  std::atomic<std::uint64_t> bridge_queries_received_{0};
  std::atomic<std::uint64_t> bridge_handshakes_completed_{0};
  std::atomic<uint32_t> mouse_buttons_{0};
  std::atomic<bool> gl_context_refresh_requested_{false};
  mutable std::mutex mutex_;
  mutable std::mutex d3d_mutex_;
  mutable std::mutex bridge_mutex_;
  std::condition_variable browser_cv_;
  CefRefPtr<CefBrowser> browser_;
  CefRefPtr<CefClient> client_;
  struct PendingBridgeQuery {
    std::int64_t cef_query_id = 0;
    std::string request;
    CefRefPtr<CefMessageRouterBrowserSide::Callback> callback;
  };
  std::deque<std::uint64_t> bridge_query_queue_;
  std::unordered_map<std::uint64_t, PendingBridgeQuery> bridge_queries_;
  std::deque<std::string> queued_bridge_messages_;
  std::uint64_t next_bridge_query_id_ = 1;
  bool bridge_bootstrap_installed_ = false;
  std::string bridge_last_error_;
  Microsoft::WRL::ComPtr<ID3D11Device> d3d_device_;
  Microsoft::WRL::ComPtr<ID3D11Device1> d3d_device1_;
  Microsoft::WRL::ComPtr<ID3D11DeviceContext> d3d_context_;
  std::array<Slot, 3> slots_{};
  Microsoft::WRL::ComPtr<ID3D11Texture2D> pending_texture_;
  std::uint64_t pending_generation_ = 0;
  int latest_slot_ = -1;
  int render_slot_ = -1;
  std::uint64_t next_generation_ = 0;
  unsigned mailbox_width_ = 0;
  unsigned mailbox_height_ = 0;
  DXGI_FORMAT mailbox_format_ = DXGI_FORMAT_UNKNOWN;
  bool resize_pending_ = false;
  bool browser_created_ = false;
  bool browser_close_drained_ = false;
  bool shutdown_complete_ = false;
  HANDLE interop_device_ = nullptr;
  bool render_locked_ = false;
  bool render_draw_marked_ = false;
  std::uint64_t render_generation_ = 0;
  std::uint64_t last_drawn_generation_ = 0;
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
  HGLRC interop_gl_context_ = nullptr;
  HDC interop_gl_dc_ = nullptr;
  bool OwnerThread() const { return std::this_thread::get_id() == owner_thread_; }
};
