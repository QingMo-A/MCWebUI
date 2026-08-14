#include "DirectCefRuntime.h"
#include "DirectCefApp.h"

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <sstream>
#include <thread>

#include <GL/gl.h>
#include <dxgi.h>

#include "include/cef_command_line.h"
#include "include/cef_context_menu_handler.h"
#include "include/cef_life_span_handler.h"
#include "include/cef_load_handler.h"
#include "include/cef_parser.h"
#include "include/cef_request_handler.h"
#include "include/cef_task.h"
#include "include/base/cef_callback.h"
#include "include/internal/cef_types_win.h"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/base/cef_bind.h"
#include "include/wrapper/cef_message_router.h"

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

std::string BridgeUrlKey(const std::string& url) {
  CefURLParts parts;
  if (!CefParseURL(url, parts)) return {};
  std::ostringstream out;
  std::string scheme = CefString(&parts.scheme).ToString();
  std::string host = CefString(&parts.host).ToString();
  std::transform(scheme.begin(), scheme.end(), scheme.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  std::transform(host.begin(), host.end(), host.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  out << scheme << "://" << host << ':' << CefString(&parts.port).ToString()
      << CefString(&parts.path).ToString() << '?'
      << CefString(&parts.query).ToString();
  return out.str();
}

std::string QuoteJavaScript(const std::string& value) {
  std::ostringstream out;
  out << '"';
  constexpr char hex[] = "0123456789abcdef";
  for (unsigned char c : value) {
    switch (c) {
      case '"': out << "\\\""; break;
      case '\\': out << "\\\\"; break;
      case '\b': out << "\\b"; break;
      case '\f': out << "\\f"; break;
      case '\n': out << "\\n"; break;
      case '\r': out << "\\r"; break;
      case '\t': out << "\\t"; break;
      default:
        if (c < 0x20) {
          out << "\\u00" << hex[(c >> 4) & 0xf] << hex[c & 0xf];
        } else {
          out << static_cast<char>(c);
        }
    }
  }
  out << '"';
  return out.str();
}

const char kBridgeBootstrap[] =
    "(() => {const listeners=new Set();let closed=false;const deliver=(m)=>{if(closed)return;try{const v=typeof m==='string'?JSON.parse(m):m;listeners.forEach((l)=>{try{l(v)}catch(_){}})}catch(_){} };const call=(m)=>new Promise((resolve,reject)=>{if(closed){reject(new Error('Bridge closed'));return}try{window.cefQuery({request:JSON.stringify(m),onSuccess:(raw)=>{try{const v=JSON.parse(raw);deliver(v);resolve(v)}catch(e){reject(e)}},onFailure:(code,msg)=>reject(Object.assign(new Error(msg||'Bridge query failed'),{code}))})}catch(e){reject(e)}});window.__MCWEBUI_BRIDGE_DELIVER__=deliver;window.__MCWEBUI_BRIDGE__={connect:()=>call({version:1,type:'handshake'}),send:(m)=>call(m).then(()=>undefined),subscribe:(l)=>{listeners.add(l);return()=>listeners.delete(l)},close:()=>{closed=true;listeners.clear();delete window.__MCWEBUI_BRIDGE__;delete window.__MCWEBUI_BRIDGE_DELIVER__}};window.dispatchEvent(new Event('__MCWEBUI_BRIDGE_READY__'))})()";
}

class RuntimeRenderHandler final : public CefRenderHandler {
 public:
  explicit RuntimeRenderHandler(DirectCefRuntime* owner) : owner_(owner) {}
  void GetViewRect(CefRefPtr<CefBrowser>, CefRect& rect) override {
    CEF_REQUIRE_UI_THREAD();
    rect = CefRect(0, 0, owner_->width_.load(), owner_->height_.load());
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

class RuntimeBridgeHandler final : public CefMessageRouterBrowserSide::Handler {
 public:
  explicit RuntimeBridgeHandler(DirectCefRuntime* owner) : owner_(owner) {}
  bool OnQuery(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
               int64_t query_id, const CefString& request, bool persistent,
               CefRefPtr<Callback> callback) override {
    CEF_REQUIRE_UI_THREAD();
    if (persistent) {
      callback->Failure(400, "Persistent bridge queries are not supported");
      return true;
    }
    return owner_->OnBridgeQuery(browser, frame, query_id, request.ToString(), callback);
  }
  void OnQueryCanceled(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame>,
                       int64_t query_id) override {
    CEF_REQUIRE_UI_THREAD();
    owner_->OnBridgeQueryCanceled(query_id);
  }
 private:
  DirectCefRuntime* owner_;
};

class RuntimeClient final : public CefClient, public CefLifeSpanHandler,
                            public CefLoadHandler, public CefRequestHandler {
 public:
  explicit RuntimeClient(DirectCefRuntime* owner) : owner_(owner), render_(new RuntimeRenderHandler(owner)) {}
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }
  CefRefPtr<CefRequestHandler> GetRequestHandler() override { return this; }
  CefRefPtr<CefRenderHandler> GetRenderHandler() override { return render_; }
  bool OnProcessMessageReceived(CefRefPtr<CefBrowser> browser,
                                CefRefPtr<CefFrame> frame,
                                CefProcessId source_process,
                                CefRefPtr<CefProcessMessage> message) override {
    CEF_REQUIRE_UI_THREAD();
    return message_router_ && message_router_->OnProcessMessageReceived(
        browser, frame, source_process, message);
  }
  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_UI_THREAD();
    CefMessageRouterConfig config;
    message_router_ = CefMessageRouterBrowserSide::Create(config);
    bridge_handler_ = std::make_unique<RuntimeBridgeHandler>(owner_);
    message_router_->AddHandler(bridge_handler_.get(), true);
    owner_->OnBrowserCreated(browser);
  }
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_UI_THREAD();
    if (message_router_) {
      message_router_->OnBeforeClose(browser);
      message_router_->RemoveHandler(bridge_handler_.get());
      bridge_handler_.reset();
      message_router_ = nullptr;
    }
    owner_->OnBrowserClosed();
  }
  bool OnBeforeBrowse(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                      CefRefPtr<CefRequest> request, bool, bool) override {
    CEF_REQUIRE_UI_THREAD();
    if (message_router_) message_router_->OnBeforeBrowse(browser, frame);
    if (frame && frame->IsMain()) owner_->OnBridgeNavigationStart(request->GetURL().ToString());
    return false;
  }
  void OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser,
                                 TerminationStatus, int,
                                 const CefString&) override {
    CEF_REQUIRE_UI_THREAD();
    if (message_router_) message_router_->OnRenderProcessTerminated(browser);
    auto frame = browser ? browser->GetMainFrame() : nullptr;
    owner_->OnBridgeNavigationStart(frame ? frame->GetURL().ToString() : "");
  }
  void OnLoadEnd(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                 int) override {
    CEF_REQUIRE_UI_THREAD();
    if (frame && frame->IsMain() && owner_->IsTrustedBridgeUrl(frame->GetURL().ToString())) {
      owner_->OnBridgeBootstrapInstalled(browser);
    }
  }
  IMPLEMENT_REFCOUNTING(RuntimeClient);
 private:
  DirectCefRuntime* owner_;
  CefRefPtr<RuntimeRenderHandler> render_;
  CefRefPtr<CefMessageRouterBrowserSide> message_router_;
  std::unique_ptr<RuntimeBridgeHandler> bridge_handler_;
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
      height_(std::max(1, height)), target_hz_(std::max(1, target_hz)),
      trusted_url_key_(BridgeUrlKey(url_)) {}

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
  CefRefPtr<CefApp> app = CreateDirectCefApp();
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
  // SendExternalBeginFrame is ignored unless this creation-time flag is set.
  // Keep browser cadence owned by Minecraft's render opportunities instead of
  // silently falling back to CEF's default ~30 Hz windowless timer.
  info.external_begin_frame_enabled = 1;
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
  {
    std::lock_guard<std::mutex> lock(mutex_);
    browser = browser_;
    browser_close_drained_ = false;
  }
  if (browser) {
    PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b) { b->GetHost()->CloseBrowser(true); }, browser));
    std::unique_lock<std::mutex> lock(mutex_);
    if (!browser_cv_.wait_for(lock, std::chrono::seconds(5),
                              [this] { return browser_close_drained_; })) {
      // Never call CefShutdown while the browser is still alive. A failed
      // close is reported and shutdown is skipped instead of tearing Chromium
      // down underneath an outstanding callback.
      last_error_ = "CEF browser close did not drain before timeout";
      ready_.store(false);
      return;
    }
    browser_ = nullptr;
    client_ = nullptr;
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
  {
    std::lock_guard<std::mutex> bridge_lock(bridge_mutex_);
    bridge_query_queue_.clear();
    bridge_queries_.clear();
    queued_bridge_messages_.clear();
    bridge_bootstrap_installed_ = false;
  }
  {
    std::lock_guard<std::mutex> lock(mutex_);
    browser_ = nullptr;
    browser_created_ = false;
    browser_close_drained_ = true;
    browser_cv_.notify_all();
  }
}

bool DirectCefRuntime::IsTrustedBridgeUrl(const std::string& url) const {
  return !trusted_url_key_.empty() && BridgeUrlKey(url) == trusted_url_key_;
}

bool DirectCefRuntime::OnBridgeQuery(
    CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame, std::int64_t cef_query_id,
    const std::string& request,
    CefRefPtr<CefMessageRouterBrowserSide::Callback> callback) {
  if (closing_.load() || !frame || !frame->IsMain() ||
      !IsTrustedBridgeUrl(frame->GetURL().ToString())) {
    {
      std::lock_guard<std::mutex> lock(bridge_mutex_);
      bridge_last_error_ = "Rejected query from an untrusted frame";
    }
    callback->Failure(403, "MCWebUI bridge origin is not trusted");
    return true;
  }
  constexpr std::size_t kMaxBridgeRequestBytes = 1024 * 1024;
  constexpr std::size_t kMaxPendingBridgeQueries = 256;
  if (request.size() > kMaxBridgeRequestBytes) {
    callback->Failure(413, "MCWebUI bridge request is too large");
    return true;
  }
  {
    std::lock_guard<std::mutex> lock(bridge_mutex_);
    if (bridge_queries_.size() < kMaxPendingBridgeQueries &&
        bridge_query_queue_.size() < kMaxPendingBridgeQueries) {
      const std::uint64_t id = next_bridge_query_id_++;
      bridge_queries_.emplace(id, PendingBridgeQuery{cef_query_id, request, callback});
      bridge_query_queue_.push_back(id);
      bridge_last_error_.clear();
      ++bridge_queries_received_;
      return true;
    }
    bridge_last_error_ = "Bridge query queue is full";
  }
  callback->Failure(429, "MCWebUI bridge query queue is full");
  return true;
}

void DirectCefRuntime::OnBridgeQueryCanceled(std::int64_t cef_query_id) {
  std::lock_guard<std::mutex> lock(bridge_mutex_);
  for (auto it = bridge_queries_.begin(); it != bridge_queries_.end(); ++it) {
    if (it->second.cef_query_id == cef_query_id) {
      const std::uint64_t id = it->first;
      bridge_queries_.erase(it);
      bridge_query_queue_.erase(
          std::remove(bridge_query_queue_.begin(), bridge_query_queue_.end(), id),
          bridge_query_queue_.end());
      break;
    }
  }
}

bool DirectCefRuntime::PollBridgeQuery(BridgeQuery* query) {
  if (!query || closing_.load()) return false;
  std::lock_guard<std::mutex> lock(bridge_mutex_);
  while (!bridge_query_queue_.empty()) {
    const std::uint64_t id = bridge_query_queue_.front();
    bridge_query_queue_.pop_front();
    const auto it = bridge_queries_.find(id);
    if (it == bridge_queries_.end()) continue;
    query->id = id;
    query->request = it->second.request;
    return true;
  }
  return false;
}

bool DirectCefRuntime::CompleteBridgeQuery(std::uint64_t id,
                                           const std::string& response,
                                           int error_code,
                                           const std::string& error_message) {
  CefRefPtr<CefMessageRouterBrowserSide::Callback> callback;
  bool handshake = false;
  {
    std::lock_guard<std::mutex> lock(bridge_mutex_);
    auto it = bridge_queries_.find(id);
    if (it == bridge_queries_.end()) return false;
    handshake = it->second.request.find("\"type\":\"handshake\"") != std::string::npos;
    callback = it->second.callback;
    bridge_queries_.erase(it);
  }
  if (!callback) return false;
  if (error_code == 0) {
    callback->Success(response);
    if (handshake) ++bridge_handshakes_completed_;
  } else {
    callback->Failure(error_code, error_message);
  }
  return true;
}

void DirectCefRuntime::OnBridgeNavigationStart(const std::string&) {
  std::lock_guard<std::mutex> lock(bridge_mutex_);
  bridge_bootstrap_installed_ = false;
  bridge_query_queue_.clear();
  bridge_queries_.clear();
  queued_bridge_messages_.clear();
  bridge_last_error_.clear();
  ++bridge_navigation_epoch_;
}

void DirectCefRuntime::OnBridgeBootstrapInstalled(CefRefPtr<CefBrowser> browser) {
  if (!browser || closing_.load()) return;
  auto frame = browser->GetMainFrame();
  if (!frame || !IsTrustedBridgeUrl(frame->GetURL().ToString())) return;
  const std::uint64_t epoch = bridge_navigation_epoch_.load();
  frame->ExecuteJavaScript(
      "window.__MCWEBUI_BRIDGE_EPOCH__=" + std::to_string(epoch) + ";" + kBridgeBootstrap,
      frame->GetURL(), 0);
  std::deque<std::string> queued;
  {
    std::lock_guard<std::mutex> lock(bridge_mutex_);
    // A delayed load callback from the previous document must not mark a
    // newer navigation as ready or flush its messages into the stale frame.
    if (epoch != bridge_navigation_epoch_.load() || closing_.load()) return;
    bridge_bootstrap_installed_ = true;
    bridge_last_error_.clear();
    queued.swap(queued_bridge_messages_);
  }
  for (const auto& message : queued) {
    frame->ExecuteJavaScript(
        "window.__MCWEBUI_BRIDGE_EPOCH__===" + std::to_string(epoch) +
            "&&window.__MCWEBUI_BRIDGE_DELIVER__&&window.__MCWEBUI_BRIDGE_DELIVER__(" +
            QuoteJavaScript(message) + ");",
        frame->GetURL(), 0);
  }
}

bool DirectCefRuntime::DeliverBridgeMessage(std::uint64_t navigation_epoch,
                                            const std::string& encoded_message) {
  if (encoded_message.size() > 1024 * 1024) return false;
  CefRefPtr<CefBrowser> browser;
  {
    std::lock_guard<std::mutex> lock(bridge_mutex_);
    if (closing_.load() || navigation_epoch != bridge_navigation_epoch_.load()) return false;
    if (!bridge_bootstrap_installed_) {
      if (queued_bridge_messages_.size() >= 256) queued_bridge_messages_.pop_front();
      queued_bridge_messages_.push_back(encoded_message);
      return true;
    }
  }
  {
    std::lock_guard<std::mutex> lock(mutex_);
    browser = browser_;
  }
  if (!browser) return false;
  const std::string script =
      "window.__MCWEBUI_BRIDGE_EPOCH__===" + std::to_string(navigation_epoch) +
      "&&window.__MCWEBUI_BRIDGE_DELIVER__&&window.__MCWEBUI_BRIDGE_DELIVER__(" +
      QuoteJavaScript(encoded_message) + ");";
  const std::string trusted_url_key = trusted_url_key_;
  PostBrowser(browser, base::BindOnce(
      [](CefRefPtr<CefBrowser> b, std::string javascript, std::string allowed_url_key) {
        auto frame = b->GetMainFrame();
        if (frame && BridgeUrlKey(frame->GetURL().ToString()) == allowed_url_key) {
          frame->ExecuteJavaScript(javascript, frame->GetURL(), 0);
        }
      },
      browser, script, trusted_url_key));
  return true;
}

bool DirectCefRuntime::Resize(int width, int height) {
  width_.store(std::max(1, width));
  height_.store(std::max(1, height));
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

bool DirectCefRuntime::RefreshGlContext() {
  gl_context_refresh_requested_.store(true);
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

bool DirectCefRuntime::SetMouseButtons(uint32_t buttons) {
  mouse_buttons_.store(buttons & 0x7u);
  return true;
}

namespace {
uint32_t CefMouseModifiers(uint32_t modifiers, uint32_t buttons) {
  uint32_t result = modifiers;
  if (buttons & 0x1u) result |= EVENTFLAG_LEFT_MOUSE_BUTTON;
  if (buttons & 0x2u) result |= EVENTFLAG_RIGHT_MOUSE_BUTTON;
  if (buttons & 0x4u) result |= EVENTFLAG_MIDDLE_MOUSE_BUTTON;
  return result;
}
}

bool DirectCefRuntime::SendMouseMove(int x, int y, uint32_t modifiers, bool leave) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false; CefMouseEvent e{}; e.x=x; e.y=y;
  e.modifiers=CefMouseModifiers(modifiers, mouse_buttons_.load());
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b, CefMouseEvent e, bool l) { b->GetHost()->SendMouseMoveEvent(e,l); }, browser,e,leave)); return true;
}

bool DirectCefRuntime::SendMouseButton(int x, int y, uint32_t modifiers, int button, bool up, int count) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false; CefMouseEvent e{}; e.x=x; e.y=y;
  e.modifiers=CefMouseModifiers(modifiers, mouse_buttons_.load());
  auto type = button == 1 ? MBT_RIGHT : button == 2 ? MBT_MIDDLE : MBT_LEFT;
  PostBrowser(browser, base::BindOnce([](CefRefPtr<CefBrowser> b, CefMouseEvent e, CefBrowserHost::MouseButtonType t, bool u, int c) { b->GetHost()->SendMouseClickEvent(e,t,u,c); }, browser,e,type,up,count)); return true;
}

bool DirectCefRuntime::SendMouseWheel(int x, int y, uint32_t modifiers, int dx, int dy) {
  CefRefPtr<CefBrowser> browser; { std::lock_guard<std::mutex> lock(mutex_); browser = browser_; }
  if (!browser) return false; CefMouseEvent e{}; e.x=x; e.y=y;
  e.modifiers=CefMouseModifiers(modifiers, mouse_buttons_.load());
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
  bool mailbox_mismatch = false;
  bool has_mailbox_texture = false;
  for (const auto& existing : slots_) {
    if (!existing.texture) continue;
    has_mailbox_texture = true;
    D3D11_TEXTURE2D_DESC existing_desc{};
    existing.texture->GetDesc(&existing_desc);
    if (existing_desc.Width != desc.Width || existing_desc.Height != desc.Height ||
        existing_desc.Format != desc.Format) {
      mailbox_mismatch = true;
      break;
    }
  }
  // After CEF reports a resize it can publish the new shared texture before
  // any host mailbox slot exists. Treat that first texture as a pending
  // replacement too; otherwise one slot gets the new size while the remaining
  // slots are later compared as if they were an established mailbox.
  if (mailbox_mismatch || !has_mailbox_texture) {
    D3D11_TEXTURE2D_DESC pending_desc{};
    if (pending_texture_) pending_texture_->GetDesc(&pending_desc);
    if (!pending_texture_ || pending_desc.Width != desc.Width ||
        pending_desc.Height != desc.Height || pending_desc.Format != desc.Format) {
      pending_texture_.Reset();
      D3D11_TEXTURE2D_DESC host = desc;
      host.BindFlags = D3D11_BIND_SHADER_RESOURCE;
      host.Usage = D3D11_USAGE_DEFAULT;
      host.CPUAccessFlags = 0;
      host.MiscFlags = 0;
      if (FAILED(d3d_device_->CreateTexture2D(&host, nullptr, &pending_texture_))) {
        ++copy_failures_;
        return;
      }
    }
    // Keep rendering the previous registered texture until a complete frame
    // at the new size is copied. The render thread swaps this pending texture
    // into the mailbox atomically, avoiding a transparent resize interval.
    d3d_context_->CopyResource(pending_texture_.Get(), texture.Get());
    pending_generation_ = ++next_generation_;
    resize_pending_ = true;
    ++published_generations_;
    return;
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
  auto get = [](const char* name) -> PROC { return wglGetProcAddress(name); };
  using WglGetExtensionsStringARB = const char* (WINAPI*)(HDC);
  using WglGetExtensionsStringEXT = const char* (WINAPI*)();
  std::string extensions;
  auto get_wgl_extensions_arb =
      reinterpret_cast<WglGetExtensionsStringARB>(get("wglGetExtensionsStringARB"));
  if (get_wgl_extensions_arb) {
    const char* value = get_wgl_extensions_arb(wglGetCurrentDC());
    if (value) extensions = value;
  }
  if (extensions.empty()) {
    auto get_wgl_extensions_ext =
        reinterpret_cast<WglGetExtensionsStringEXT>(get("wglGetExtensionsStringEXT"));
    if (get_wgl_extensions_ext) {
      const char* value = get_wgl_extensions_ext();
      if (value) extensions = value;
    }
  }
  // Do not query GL_EXTENSIONS with glGetString here. Minecraft uses a core
  // profile where that legacy query is GL_INVALID_ENUM; this capability is a
  // WGL extension and must come from the current window DC instead.
  auto has = [&extensions](const char* name) {
    std::istringstream stream(extensions);
    std::string token;
    while (stream >> token) if (token == name) return true;
    return false;
  };
  wgl_dx_open_device_ = reinterpret_cast<WglDXOpenDeviceNV>(get("wglDXOpenDeviceNV"));
  wgl_dx_close_device_ = reinterpret_cast<WglDXCloseDeviceNV>(get("wglDXCloseDeviceNV"));
  wgl_dx_register_object_ = reinterpret_cast<WglDXRegisterObjectNV>(get("wglDXRegisterObjectNV"));
  wgl_dx_unregister_object_ = reinterpret_cast<WglDXUnregisterObjectNV>(get("wglDXUnregisterObjectNV"));
  wgl_dx_object_access_ = reinterpret_cast<WglDXObjectAccessNV>(get("wglDXObjectAccessNV"));
  wgl_dx_lock_objects_ = reinterpret_cast<WglDXLockObjectsNV>(get("wglDXLockObjectsNV"));
  wgl_dx_unlock_objects_ = reinterpret_cast<WglDXUnlockObjectsNV>(get("wglDXUnlockObjectsNV"));
  interop_supported_ = has("WGL_NV_DX_interop2") && wgl_dx_open_device_ &&
      wgl_dx_close_device_ && wgl_dx_register_object_ && wgl_dx_unregister_object_ &&
      wgl_dx_object_access_ && wgl_dx_lock_objects_ && wgl_dx_unlock_objects_;
  if (!interop_supported_) return false;
  // Resize teardown intentionally closes the old interop device. Capability
  // functions remain valid for the same current WGL context, but the device
  // handle must be reopened before any slot can be registered. Returning the
  // cached capability alone previously passed nullptr into the NVIDIA driver.
  if (!interop_device_) {
    interop_device_ = wgl_dx_open_device_(d3d_device_.Get());
    interop_device_open_ = interop_device_ != nullptr;
  }
  return interop_device_ != nullptr && interop_device_open_;
}

bool DirectCefRuntime::RebindGlContextIfNeeded() {
  HGLRC current_context = wglGetCurrentContext();
  HDC current_dc = wglGetCurrentDC();
  if (!current_context || !current_dc) return false;
  if (!interop_gl_context_) {
    interop_gl_context_ = current_context;
    interop_gl_dc_ = current_dc;
    gl_context_refresh_requested_.store(false);
    return true;
  }
  const bool forced = gl_context_refresh_requested_.exchange(false);
  if (!forced && interop_gl_context_ == current_context && interop_gl_dc_ == current_dc) return true;

  // GLFW can replace the Minecraft WGL context during fullscreen transitions
  // without changing logical GUI dimensions. WGL_NV_DX_interop objects belong
  // to the context that registered them; reusing those handles in the new
  // context can crash inside the NVIDIA driver. This method only runs on the
  // render thread with the new context current.
  if (render_locked_) return false;
  ++gl_context_rebinds_;
  UnregisterGlObjects();
  interop_gl_context_ = current_context;
  interop_gl_dc_ = current_dc;
  interop_supported_ = false;
  return true;
}
bool DirectCefRuntime::RegisterSlot(int index) {
  if (index < 0 || index >= static_cast<int>(slots_.size()) || !EnsureGlInterop()) return false;
  if (!interop_device_ || !wgl_dx_register_object_ || !wgl_dx_object_access_) return false;
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
  const bool registration_context_current = interop_gl_context_ &&
      wglGetCurrentContext() == interop_gl_context_ &&
      wglGetCurrentDC() == interop_gl_dc_;
  if (registration_context_current && interop_device_ && wgl_dx_unregister_object_) {
    for (auto& slot : slots_) {
      if (slot.gl_object) wgl_dx_unregister_object_(interop_device_, slot.gl_object);
      if (slot.gl_name) glDeleteTextures(1, &slot.gl_name);
      slot.gl_object = nullptr; slot.gl_name = 0;
    }
    if (wgl_dx_close_device_) wgl_dx_close_device_(interop_device_);
  } else {
    // A fullscreen transition may already have destroyed the registration
    // context. Calling unregister/delete with the replacement context makes
    // the old object handles undefined and can terminate the NVIDIA driver.
    // Drop those process-lifetime proof handles and register fresh names in
    // the current context instead. The OS reclaims the abandoned old-context
    // resources when the client exits.
    for (auto& slot : slots_) {
      slot.gl_object = nullptr;
      slot.gl_name = 0;
    }
  }
  interop_device_ = nullptr; interop_device_open_ = false; texture_id_ = 0;
}
bool DirectCefRuntime::BeginRenderFrame() {
  if (!OwnerThread()) return false;
  std::lock_guard<std::mutex> gpu_lock(d3d_mutex_);
  HGLRC context_before = interop_gl_context_;
  HDC dc_before = interop_gl_dc_;
  if (!RebindGlContextIfNeeded()) return false;
  const bool rebound_context = context_before &&
      (context_before != interop_gl_context_ || dc_before != interop_gl_dc_);
  if (rebound_context) {
    // Give the replacement GLFW/WGL context one complete render opportunity
    // before opening/registering NVIDIA interop objects. Teardown and a fresh
    // wglDXLockObjectsNV in the same fullscreen transition frame has proven
    // unsafe in the driver.
    return false;
  }
  if (!EnsureGlInterop()) return false;
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
      if (pending_texture_) {
        slots_[0].texture = std::move(pending_texture_);
        slots_[0].generation = pending_generation_;
        pending_generation_ = 0;
        latest_slot_ = 0;
      }
      resize_pending_ = false;
      // Registration and locking resume on the next render opportunity. This
      // keeps resource teardown/replacement separate from the driver lock.
      return false;
    }
  }
  std::lock_guard<std::mutex> lock(mutex_);
  int slot = latest_slot_;
  if (slot < 0 || slot == render_slot_ || slots_[slot].writing || !slots_[slot].texture) return false;
  if (!RegisterSlot(slot)) return false;
  HANDLE object = slots_[slot].gl_object;
  if (!interop_device_ || !object || !wgl_dx_lock_objects_ ||
      !wgl_dx_lock_objects_(interop_device_, 1, &object)) {
    ++gl_lock_failures_;
    return false;
  }
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
  std::size_t bridge_pending = 0;
  bool bridge_bootstrap = false;
  std::string bridge_error;
  {
    std::lock_guard<std::mutex> lock(bridge_mutex_);
    bridge_pending = bridge_queries_.size();
    bridge_bootstrap = bridge_bootstrap_installed_;
    bridge_error = bridge_last_error_;
  }
  std::ostringstream out; out << "{\"ready\":" << (ready_.load() ? "true" : "false")
      << ",\"requestedTargetHz\":" << target_hz_
      << ",\"configuredWindowlessFrameRate\":" << std::clamp(target_hz_, 1, 144)
      << ",\"externalBeginFrameEnabled\":true"
      << ",\"acceleratedCallbacks\":" << accelerated_callbacks_.load()
      << ",\"publishedGenerations\":" << published_generations_.load()
      << ",\"frameRequests\":" << frame_requests_.load()
      << ",\"copyFailures\":" << copy_failures_.load()
      << ",\"glLockFailures\":" << gl_lock_failures_.load()
      << ",\"glContextRebinds\":" << gl_context_rebinds_.load()
      << ",\"bridgeBootstrapInstalled\":" << (bridge_bootstrap ? "true" : "false")
      << ",\"bridgeQueriesReceived\":" << bridge_queries_received_.load()
      << ",\"bridgeHandshakesCompleted\":" << bridge_handshakes_completed_.load()
      << ",\"bridgePendingQueries\":" << bridge_pending
      << ",\"bridgeNavigationEpoch\":" << bridge_navigation_epoch_.load()
      << ",\"bridgeLastError\":" << QuoteJavaScript(bridge_error)
      << ",\"interop\":\"" << (interop_supported_ ? "SUPPORTED" : "UNSUPPORTED") << "\"}"; return out.str();
}
