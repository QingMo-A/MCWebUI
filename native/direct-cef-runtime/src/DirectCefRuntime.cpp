#include "DirectCefRuntime.h"

#include <algorithm>
#include <filesystem>
#include <sstream>
#include <thread>

#include <GL/gl.h>
#include <dxgi.h>

#include "include/cef_command_line.h"
#include "include/cef_context_menu_handler.h"
#include "include/cef_life_span_handler.h"
#include "include/cef_load_handler.h"
#include "include/cef_task.h"
#include "include/base/cef_callback.h"
#include "include/internal/cef_types_win.h"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/base/cef_bind.h"

namespace {
using WglDXOpenDeviceNV = HANDLE(WINAPI*)(void*);
using WglDXCloseDeviceNV = BOOL(WINAPI*)(HANDLE);
using WglDXRegisterObjectNV = HANDLE(WINAPI*)(HANDLE, void*, unsigned int, unsigned int, unsigned int);
using WglDXUnregisterObjectNV = BOOL(WINAPI*)(HANDLE, HANDLE);
using WglDXObjectAccessNV = BOOL(WINAPI*)(HANDLE, unsigned int);
using WglDXLockObjectsNV = BOOL(WINAPI*)(HANDLE, int, HANDLE*);
using WglDXUnlockObjectsNV = BOOL(WINAPI*)(HANDLE, int, HANDLE*);
constexpr unsigned kWglAccessReadOnly = 0;

WglDXOpenDeviceNV g_open_device = nullptr;
WglDXCloseDeviceNV g_close_device = nullptr;
WglDXRegisterObjectNV g_register_object = nullptr;
WglDXUnregisterObjectNV g_unregister_object = nullptr;
WglDXObjectAccessNV g_object_access = nullptr;
WglDXLockObjectsNV g_lock_objects = nullptr;
WglDXUnlockObjectsNV g_unlock_objects = nullptr;

}

class RuntimeRenderHandler final : public CefRenderHandler {
 public:
  explicit RuntimeRenderHandler(DirectCefRuntime* owner) : owner_(owner) {}
  void GetViewRect(CefRefPtr<CefBrowser>, CefRect& rect) override {
    CEF_REQUIRE_UI_THREAD();
    rect = CefRect(0, 0, owner_->width_, owner_->height_);
  }
  void OnPaint(CefRefPtr<CefBrowser>, PaintElementType, const RectList&, const void*, int, int) override {}
#if defined(CEF_VERSION_MAJOR) && CEF_VERSION_MAJOR >= 120
  void OnAcceleratedPaint(CefRefPtr<CefBrowser>, PaintElementType type, const RectList& dirty,
                          const CefAcceleratedPaintInfo& info) override {
    CEF_REQUIRE_UI_THREAD();
    if (type != PET_VIEW) return;
    owner_->OnAcceleratedPaint(reinterpret_cast<void*>(info.shared_texture_handle),
                               static_cast<unsigned>(info.format));
  }
#else
  void OnAcceleratedPaint(CefRefPtr<CefBrowser>, PaintElementType type, const RectList&, void* handle) override {
    CEF_REQUIRE_UI_THREAD();
    if (type == PET_VIEW) owner_->OnAcceleratedPaint(handle, 0);
  }
#endif
  IMPLEMENT_REFCOUNTING(RuntimeRenderHandler);
 private:
  DirectCefRuntime* owner_;
};

class RuntimeClient final : public CefClient, public CefLifeSpanHandler, public CefLoadHandler {
 public:
  explicit RuntimeClient(DirectCefRuntime* owner) : owner_(owner), render_(new RuntimeRenderHandler(owner)) {}
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }
  CefRefPtr<CefRenderHandler> GetRenderHandler() override { return render_; }
  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override { CEF_REQUIRE_UI_THREAD(); owner_->OnBrowserCreated(browser); }
  void OnBeforeClose(CefRefPtr<CefBrowser>) override { CEF_REQUIRE_UI_THREAD(); owner_->OnBrowserClosed(); }
  IMPLEMENT_REFCOUNTING(RuntimeClient);
 private:
  DirectCefRuntime* owner_;
  CefRefPtr<RuntimeRenderHandler> render_;
};

namespace {

void PostBrowser(CefRefPtr<CefBrowser> browser, base::OnceClosure task) {
  if (browser) CefPostTask(TID_UI, CefCreateClosureTask(std::move(task)));
}
}

std::unique_ptr<DirectCefRuntime> DirectCefRuntime::Create(std::string url, std::string cache_dir,
                                                           std::string helper_path, void* parent_window,
                                                           int width, int height, int target_hz) {
  auto runtime = std::unique_ptr<DirectCefRuntime>(new DirectCefRuntime(
      std::move(url), std::move(cache_dir), std::move(helper_path), parent_window,
      width, height, target_hz));
  return runtime->Initialize() ? std::move(runtime) : nullptr;
}

DirectCefRuntime::DirectCefRuntime(std::string url, std::string cache_dir,
                                   std::string helper_path, void* parent_window,
                                   int width, int height, int target_hz)
    : url_(std::move(url)), cache_dir_(std::move(cache_dir)), helper_path_(std::move(helper_path)),
      parent_window_(reinterpret_cast<HWND>(parent_window)), width_(std::max(1, width)),
      height_(std::max(1, height)), target_hz_(std::max(1, target_hz)) {}

DirectCefRuntime::~DirectCefRuntime() {
  CloseBrowser();
  UnregisterGlObjects();
}

bool DirectCefRuntime::Initialize() {
  if (closing_.load()) return false;
  owner_thread_ = std::this_thread::get_id();
  HINSTANCE instance = GetModuleHandle(nullptr);
  CefMainArgs args(instance);
  CefSettings settings;
  settings.no_sandbox = true;
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = true;
  settings.log_severity = LOGSEVERITY_WARNING;
  CefString(&settings.browser_subprocess_path) = helper_path_;
  CefString(&settings.root_cache_path) = cache_dir_;
  CefString(&settings.cache_path) = (std::filesystem::path(cache_dir_) / "cache").wstring();
  CefRefPtr<CefApp> app;
  if (!CefInitialize(args, settings, app, nullptr)) {
    last_error_ = "CefInitialize failed";
    return false;
  }
  client_ = new RuntimeClient(this);
  CefWindowInfo info;
  // CEF 144 exposes only the one-argument SetAsWindowless overload. Transparency
  // is selected by the zero-alpha browser background below; older CEF SDKs that
  // expose a second transparent argument are handled by their compatibility
  // build, not by calling a non-existent modern overload.
  info.SetAsWindowless(parent_window_);
  info.shared_texture_enabled = 1;
  CefBrowserSettings browser_settings;
  browser_settings.background_color = CefColorSetARGB(0, 0, 0, 0);
  browser_settings.windowless_frame_rate = std::clamp(target_hz_, 1, 144);
  if (!CefBrowserHost::CreateBrowser(info, client_, url_, browser_settings, nullptr, nullptr)) {
    last_error_ = "CreateBrowser returned false";
    CefShutdown();
    return false;
  }
  cef_initialized_ = true;
  EnsureD3DDevice();
  {
    std::unique_lock<std::mutex> lock(mutex_);
    browser_cv_.wait_for(lock, std::chrono::seconds(5), [this] { return browser_created_; });
  }
  if (!browser_created_) {
    last_error_ = "Cef browser creation timed out";
    CefShutdown();
    cef_initialized_ = false;
    shutdown_complete_ = true;
    return false;
  }
  ready_.store(true);
  return true;
}

void DirectCefRuntime::CloseBrowser() {
  if (closing_.exchange(true)) return;
  CefRefPtr<CefBrowser> browser;
  { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (browser) {
    PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b) { b->GetHost()->CloseBrowser(true); }, browser));
    std::unique_lock<std::mutex> lock(mutex_);
    browser_cv_.wait_for(lock, std::chrono::seconds(5), [this] { return !browser_created_; });
  }
  if (ready_.exchange(false) && cef_initialized_ && OwnerThread() && !shutdown_complete_) {
    CefShutdown();
    cef_initialized_ = false;
    shutdown_complete_ = true;
  }
}

void DirectCefRuntime::OnBrowserCreated(CefRefPtr<CefBrowser> browser) {
  std::lock_guard<std::mutex> lock(mutex_);
  browser_ = browser;
  browser_created_ = true;
  browser_cv_.notify_all();
}

void DirectCefRuntime::OnBrowserClosed() {
  std::lock_guard<std::mutex> lock(mutex_);
  browser_ = nullptr;
  browser_created_ = false;
  browser_cv_.notify_all();
}

bool DirectCefRuntime::Resize(int width, int height) {
  width_ = std::max(1, width); height_ = std::max(1, height);
  { std::lock_guard<std::mutex> lock(mutex_); resize_pending_ = true; }
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b) { b->GetHost()->WasResized(); }, browser));
  return true;
}

bool DirectCefRuntime::SetVisible(bool visible) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b, bool v) { b->GetHost()->WasHidden(!v); }, browser, visible));
  return true;
}

bool DirectCefRuntime::RequestFrame() {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false;
  ++frame_requests_;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b) { b->GetHost()->SendExternalBeginFrame(); }, browser));
  return true;
}

bool DirectCefRuntime::SetFocus(bool focused) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b, bool f) { b->GetHost()->SetFocus(f); }, browser, focused));
  return true;
}

bool DirectCefRuntime::SendMouseMove(int x, int y, uint32_t modifiers, bool leave) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false; CefMouseEvent e{}; e.x=x; e.y=y; e.modifiers=modifiers;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b, CefMouseEvent e, bool l) { b->GetHost()->SendMouseMoveEvent(e,l); }, browser,e,leave)); return true;
}

bool DirectCefRuntime::SendMouseButton(int x, int y, uint32_t modifiers, int button, bool up, int count) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false; CefMouseEvent e{}; e.x=x; e.y=y; e.modifiers=modifiers;
  auto type = button == 1 ? MBT_RIGHT : button == 2 ? MBT_MIDDLE : MBT_LEFT;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b, CefMouseEvent e, CefBrowserHost::MouseButtonType t, bool u, int c) { b->GetHost()->SendMouseClickEvent(e,t,u,c); }, browser,e,type,up,count)); return true;
}

bool DirectCefRuntime::SendMouseWheel(int x, int y, uint32_t modifiers, int dx, int dy) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false; CefMouseEvent e{}; e.x=x; e.y=y; e.modifiers=modifiers;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b, CefMouseEvent e, int x, int y) { b->GetHost()->SendMouseWheelEvent(e,x,y); }, browser,e,dx,dy)); return true;
}

bool DirectCefRuntime::SendKey(uint32_t message, uintptr_t wparam, intptr_t lparam) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false; CefKeyEvent e{}; e.windows_key_code=(int)wparam; e.native_key_code=(int)lparam; e.type = (message==WM_KEYUP||message==WM_SYSKEYUP) ? KEYEVENT_KEYUP : (message==WM_CHAR||message==WM_SYSCHAR) ? KEYEVENT_CHAR : KEYEVENT_RAWKEYDOWN;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b, CefKeyEvent e) { b->GetHost()->SendKeyEvent(e); }, browser,e)); return true;
}

bool DirectCefRuntime::SendText(const std::u16string& text) {
  for (char16_t c : text) if (!SendKey(WM_CHAR, c, 0)) return false;
  return true;
}

void DirectCefRuntime::OnAcceleratedPaint(void* handle, unsigned) {
  if (!handle || !d3d_device1_ || closing_.load()) return;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;
  if (FAILED(d3d_device1_->OpenSharedResource1(reinterpret_cast<HANDLE>(handle), IID_PPV_ARGS(&texture)))) return;
  ++accelerated_callbacks_;
  D3D11_TEXTURE2D_DESC desc{}; texture->GetDesc(&desc);
  std::lock_guard<std::mutex> gpu_lock(d3d_mutex_);
  std::lock_guard<std::mutex> lock(mutex_);
  mailbox_width_ = desc.Width; mailbox_height_ = desc.Height; mailbox_format_ = desc.Format;
  for (const auto& existing : slots_) {
    if (!existing.texture) continue;
    D3D11_TEXTURE2D_DESC existing_desc{};
    existing.texture->GetDesc(&existing_desc);
    if (existing_desc.Width != desc.Width || existing_desc.Height != desc.Height ||
        existing_desc.Format != desc.Format) {
      resize_pending_ = true;
      ++copy_failures_;
      return;
    }
  }
  int slot = -1;
  for (int i = 0; i < static_cast<int>(slots_.size()); ++i) {
    if (i == render_slot_ || slots_[i].writing) continue;
    if (i == latest_slot_) continue;
    slot = i; break;
  }
  if (slot < 0) { ++copy_failures_; return; }
  auto& target = slots_[slot];
  if (!target.texture) {
    D3D11_TEXTURE2D_DESC host = desc;
    host.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    host.Usage = D3D11_USAGE_DEFAULT; host.CPUAccessFlags = 0; host.MiscFlags = 0;
    if (FAILED(d3d_device_->CreateTexture2D(&host, nullptr, &target.texture))) { ++copy_failures_; return; }
  }
  target.writing = true;
  d3d_context_->CopyResource(target.texture.Get(), texture.Get());
  target.generation = ++next_generation_;
  target.writing = false;
  latest_slot_ = slot;
  ++published_generations_;
}

void DirectCefRuntime::EnsureD3DDevice() {
  if (d3d_device_) return;
  const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0}; D3D_FEATURE_LEVEL selected{};
  if (SUCCEEDED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, &d3d_device_, &selected, &d3d_context_))) d3d_device_.As(&d3d_device1_);
}

bool DirectCefRuntime::EnsureGlInterop() {
  if (gl_capability_checked_) return interop_supported_;
  gl_capability_checked_ = true;
  auto get = [](const char* name) -> PROC { return wglGetProcAddress(name); };
  using WglGetExtensionsStringARB = const char* (WINAPI*)(HDC);
  const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
  std::string ext = extensions ? extensions : "";
  // WGL_NV_DX_interop2 is a WGL extension and is not guaranteed to appear in
  // GL_EXTENSIONS. Query the WGL extension string from the current Minecraft
  // DC first, then retain the GL string as a driver-specific fallback.
  auto get_wgl_extensions = reinterpret_cast<WglGetExtensionsStringARB>(get("wglGetExtensionsStringARB"));
  if (get_wgl_extensions) {
    const char* wgl_extensions = get_wgl_extensions(wglGetCurrentDC());
    if (wgl_extensions && *wgl_extensions) {
      if (!ext.empty()) ext.push_back(' ');
      ext += wgl_extensions;
    }
  }
  auto has = [&ext](const char* name) { return ext.find(name) != std::string::npos; };
  wgl_dx_open_device_ = reinterpret_cast<WglDXOpenDeviceNV>(get("wglDXOpenDeviceNV"));
  wgl_dx_close_device_ = reinterpret_cast<WglDXCloseDeviceNV>(get("wglDXCloseDeviceNV"));
  wgl_dx_register_object_ = reinterpret_cast<WglDXRegisterObjectNV>(get("wglDXRegisterObjectNV"));
  wgl_dx_unregister_object_ = reinterpret_cast<WglDXUnregisterObjectNV>(get("wglDXUnregisterObjectNV"));
  wgl_dx_object_access_ = reinterpret_cast<WglDXObjectAccessNV>(get("wglDXObjectAccessNV"));
  wgl_dx_lock_objects_ = reinterpret_cast<WglDXLockObjectsNV>(get("wglDXLockObjectsNV"));
  wgl_dx_unlock_objects_ = reinterpret_cast<WglDXUnlockObjectsNV>(get("wglDXUnlockObjectsNV"));
  interop_supported_ = has("WGL_NV_DX_interop2") && wgl_dx_open_device_ && wgl_dx_close_device_ &&
      wgl_dx_register_object_ && wgl_dx_unregister_object_ && wgl_dx_object_access_ &&
      wgl_dx_lock_objects_ && wgl_dx_unlock_objects_;
  if (interop_supported_ && !interop_device_) {
    interop_device_ = wgl_dx_open_device_(d3d_device_.Get());
    interop_device_open_ = interop_device_ != nullptr;
  }
  return interop_supported_ && interop_device_open_;
}
bool DirectCefRuntime::RegisterSlot(int index) {
  if (index < 0 || index >= static_cast<int>(slots_.size()) || !EnsureGlInterop()) return false;
  Slot& slot = slots_[index];
  if (slot.gl_object) return true;
  glGenTextures(1, &slot.gl_name); glBindTexture(GL_TEXTURE_2D, slot.gl_name);
  slot.gl_object = wgl_dx_register_object_(interop_device_, slot.texture.Get(), slot.gl_name,
                                           GL_TEXTURE_2D, 0);
  if (!slot.gl_object || !wgl_dx_object_access_(slot.gl_object, 0)) {
    if (slot.gl_object) wgl_dx_unregister_object_(interop_device_, slot.gl_object);
    if (slot.gl_name) glDeleteTextures(1, &slot.gl_name);
    slot.gl_object = nullptr; slot.gl_name = 0; return false;
  }
  return true;
}
void DirectCefRuntime::UnregisterGlObjects() {
  if (interop_device_ && wgl_dx_unregister_object_) {
    for (auto& slot : slots_) {
      if (slot.gl_object) wgl_dx_unregister_object_(interop_device_, slot.gl_object);
      if (slot.gl_name) glDeleteTextures(1, &slot.gl_name);
      slot.gl_object = nullptr; slot.gl_name = 0;
    }
    if (wgl_dx_close_device_) wgl_dx_close_device_(interop_device_);
  }
  interop_device_ = nullptr; interop_device_open_ = false; texture_id_ = 0;
}
bool DirectCefRuntime::BeginRenderFrame() {
  if (!OwnerThread() || !EnsureGlInterop()) return false;
  std::lock_guard<std::mutex> gpu_lock(d3d_mutex_);
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (resize_pending_ && !render_locked_) {
      UnregisterGlObjects();
      for (auto& slot : slots_) {
        slot.texture.Reset();
        slot.generation = 0;
        slot.writing = false;
      }
      latest_slot_ = -1;
      resize_pending_ = false;
    }
  }
  std::lock_guard<std::mutex> lock(mutex_);
  int slot = latest_slot_;
  if (slot < 0 || slot == render_slot_ || slots_[slot].writing || !slots_[slot].texture) return false;
  if (!RegisterSlot(slot)) return false;
  HANDLE object = slots_[slot].gl_object;
  if (!wgl_dx_lock_objects_(interop_device_, 1, &object)) { ++gl_lock_failures_; return false; }
  render_slot_ = slot; render_locked_ = true; texture_id_ = slots_[slot].gl_name;
  return true;
}
void DirectCefRuntime::EndRenderFrame() {
  if (!render_locked_ || render_slot_ < 0) return;
  std::lock_guard<std::mutex> lock(mutex_);
  HANDLE object = slots_[render_slot_].gl_object;
  if (interop_device_ && wgl_dx_unlock_objects_) wgl_dx_unlock_objects_(interop_device_, 1, &object);
  render_locked_ = false; render_slot_ = -1;
}
unsigned DirectCefRuntime::TextureId() const { return texture_id_; }
std::string DirectCefRuntime::DiagnosticsJson() const {
  std::ostringstream out; out << "{\"ready\":" << (ready_.load() ? "true" : "false")
      << ",\"acceleratedCallbacks\":" << accelerated_callbacks_.load()
      << ",\"publishedGenerations\":" << published_generations_.load()
      << ",\"frameRequests\":" << frame_requests_.load()
      << ",\"copyFailures\":" << copy_failures_.load()
      << ",\"glLockFailures\":" << gl_lock_failures_.load()
      << ",\"interop\":\"" << (interop_supported_ ? "SUPPORTED" : "UNSUPPORTED") << "\"}"; return out.str();
}
