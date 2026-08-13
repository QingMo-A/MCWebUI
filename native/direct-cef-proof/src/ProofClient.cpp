#include "ProofClient.h"

#include <iostream>
#include <sstream>
#include <utility>
#include <windows.h>

#include "include/cef_browser.h"
#include "include/cef_frame.h"
#include "include/cef_task.h"
#include "include/base/cef_callback.h"
#include "include/base/cef_bind.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"

namespace {
constexpr char kRafPrefix[] = "MCWEBUI_RAF ";
constexpr char kLayoutPrefix[] = "MCWEBUI_LAYOUT ";
void SendExternalFrame(CefRefPtr<CefBrowser> browser) {
  browser->GetHost()->SendExternalBeginFrame();
}

void CloseBrowser(CefRefPtr<CefBrowser> browser, bool force_close) {
  browser->GetHost()->CloseBrowser(force_close);
}
void SetBrowserFocus(CefRefPtr<CefBrowser> browser, bool focus) { browser->GetHost()->SetFocus(focus); }
void SendBrowserMouseMove(CefRefPtr<CefBrowser> browser, CefMouseEvent event, bool leave) { browser->GetHost()->SendMouseMoveEvent(event, leave); }
void SendBrowserMouseButton(CefRefPtr<CefBrowser> browser, CefMouseEvent event, CefBrowserHost::MouseButtonType type, bool up, int count) { browser->GetHost()->SendMouseClickEvent(event, type, up, count); }
void SendBrowserMouseWheel(CefRefPtr<CefBrowser> browser, CefMouseEvent event, int delta_x, int delta_y) { browser->GetHost()->SendMouseWheelEvent(event, delta_x, delta_y); }
void SendBrowserKey(CefRefPtr<CefBrowser> browser, CefKeyEvent event) { browser->GetHost()->SendKeyEvent(event); }

const char kProbe[] = R"JS((()=>{
  if (window.__MCWEBUI_DIRECT_CEF_PROBE__) return;
  window.__MCWEBUI_DIRECT_CEF_PROBE__ = true;
  const animate=window.__MCWEBUI_DIRECT_CEF_ANIMATE__===true;
  const node=animate?document.createElement('div'):null;
  if(node){
    node.setAttribute('aria-hidden','true');
    node.style.cssText='position:fixed;left:0;top:0;width:2px;height:2px;z-index:2147483647;pointer-events:none;background:#58d3a2;will-change:transform;';
    document.documentElement.appendChild(node);
  }
  let total=0,start=performance.now(),last=start,samples=[],phase=0;
  const tick=now=>{
    total++; samples.push(now-last); last=now; phase=(phase+.7)%360;
    if(node) node.style.transform=`translate3d(${phase}px,0,0)`;
    if(now-start>=1000){
      samples.sort((a,b)=>a-b);
      const pct=p=>samples[Math.min(samples.length-1,Math.max(0,Math.ceil(samples.length*p)-1))]||0;
      console.info('MCWEBUI_RAF '+[total,total*1000/(now-start),pct(.5),pct(.95),samples[samples.length-1]||0].join(','));
      total=0; samples=[]; start=now;
    }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
})();)JS";
}

ProofClient::ProofClient(int width, int height, ProofMetrics* metrics,
                         bool animate, ProofSimulator* simulator,
                         ClosedCallback closed_callback, LayoutCallback layout_callback)
    : metrics_(metrics), render_handler_(new ProofRenderHandler(width, height, metrics, simulator)),
      closed_callback_(std::move(closed_callback)), layout_callback_(std::move(layout_callback)), animate_(animate) {}

void ProofClient::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  std::lock_guard<std::mutex> lock(browser_mutex_);
  browser_ = browser;
}

void ProofClient::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  {
    std::lock_guard<std::mutex> lock(browser_mutex_);
    if (browser_ && browser_->IsSame(browser)) browser_ = nullptr;
  }
  if (closed_callback_) closed_callback_();
}

void ProofClient::OnLoadEnd(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame,
                            int http_status_code) {
  CEF_REQUIRE_UI_THREAD();
  if (!frame->IsMain()) return;
  metrics_->RecordLoad(http_status_code == 0 || (http_status_code >= 200 && http_status_code < 400));
  const std::string setup = std::string("window.__MCWEBUI_DIRECT_CEF_ANIMATE__=") +
      (animate_ ? "true;" : "false;") + kProbe;
  frame->ExecuteJavaScript(setup, frame->GetURL(), 0);
  // The acceptance page is selected by the caller's URL, but a file:// URL
  // can expose an empty search string in some CEF harnesses.  Probe the
  // stable data-test controls unconditionally; a normal showcase page simply
  // produces an empty controls object.  Include URL/marker evidence so an
  // empty result cannot be mistaken for a successful layout probe.
  frame->ExecuteJavaScript(R"JS((()=>{
    let attempts=0;
    const emit=()=>{const app=document.querySelector('#app');const out={url:location.href,marker:!!document.querySelector('.transparent-lab'),ready:document.readyState,appChildren:app?.childElementCount??-1,bodyClass:document.body.className};
      for(const k of ['button','range','checkbox','select','text','scroll','modal','modal-close','world-reveal','alpha0','alpha25','alpha50','alpha75','alpha100']){const e=document.querySelector(`[data-test="${k}"]`);if(e){const r=e.getBoundingClientRect();const sample=k==='world-reveal'||k.startsWith('alpha');out[k]={x:sample?r.left+5:r.left+r.width/2,y:sample?r.top+5:r.top+r.height/2,width:r.width,height:r.height};}}
      console.info('MCWEBUI_LAYOUT '+JSON.stringify(out));
      if(!out.marker&&++attempts<12)setTimeout(emit,100);};
    if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',()=>requestAnimationFrame(emit),{once:true}); else requestAnimationFrame(emit);
  })();)JS", frame->GetURL(), 0);
}

void ProofClient::OnLoadError(CefRefPtr<CefBrowser>, CefRefPtr<CefFrame> frame,
                              ErrorCode error_code, const CefString& error_text,
                              const CefString& failed_url) {
  CEF_REQUIRE_UI_THREAD();
  if (error_code == ERR_ABORTED) return;
  if (frame->IsMain()) metrics_->RecordLoad(false);
  std::cerr << "[direct-cef-proof] load_error code=" << error_code
            << " url=" << failed_url.ToString()
            << " message=" << error_text.ToString() << std::endl;
}

bool ProofClient::OnConsoleMessage(CefRefPtr<CefBrowser>, cef_log_severity_t,
                                   const CefString& message, const CefString&, int) {
  const std::string value = message.ToString();
  if (value.rfind(kLayoutPrefix, 0) == 0) {
    metrics_->RecordLabInput(value);
    if (layout_callback_) layout_callback_(value.substr(sizeof(kLayoutPrefix) - 1));
    return true;
  }
  if (value.rfind("MCWEBUI_INPUT ", 0) == 0) {
    metrics_->RecordLabInput(value);
    if (value.find("\"kind\":\"escape\"") != std::string::npos &&
        layout_callback_) layout_callback_(value);
    return true;
  }
  if (value.rfind(kRafPrefix, 0) != 0) return false;
  std::stringstream stream(value.substr(sizeof(kRafPrefix) - 1));
  std::string field;
  double values[5] = {};
  for (double& item : values) {
    if (!std::getline(stream, field, ',')) return true;
    item = std::strtod(field.c_str(), nullptr);
  }
  metrics_->RecordRaf(static_cast<std::uint64_t>(values[0]), values[1],
                      values[2], values[3], values[4]);
  return true;
}

void ProofClient::RequestExternalFrame() {
  CefRefPtr<CefBrowser> browser;
  {
    std::lock_guard<std::mutex> lock(browser_mutex_);
    browser = browser_;
  }
  if (!browser) return;
  metrics_->RecordFrameRequest();
  CefPostTask(TID_UI, CefCreateClosureTask(
      base::BindOnce(&SendExternalFrame, browser)));
}

void ProofClient::Close(bool force_close) {
  CefRefPtr<CefBrowser> browser;
  {
    std::lock_guard<std::mutex> lock(browser_mutex_);
    browser = browser_;
  }
  if (browser) CefPostTask(TID_UI, CefCreateClosureTask(
      base::BindOnce(&CloseBrowser, browser, force_close)));
}

void ProofClient::SetFocus(bool focus) {
  CefRefPtr<CefBrowser> browser;
  { std::lock_guard<std::mutex> lock(browser_mutex_); browser = browser_; }
  if (browser) CefPostTask(TID_UI, CefCreateClosureTask(base::BindOnce(&SetBrowserFocus, browser, focus)));
}

void ProofClient::SendMouseMove(int x, int y, uint32_t modifiers, bool leave) {
  CefRefPtr<CefBrowser> browser;
  { std::lock_guard<std::mutex> lock(browser_mutex_); browser = browser_; }
  if (!browser) return;
  CefMouseEvent event; event.x = x; event.y = y; event.modifiers = modifiers;
  CefPostTask(TID_UI, CefCreateClosureTask(base::BindOnce(&SendBrowserMouseMove, browser, event, leave)));
}

void ProofClient::SendMouseButton(int x, int y, uint32_t modifiers,
                                  CefBrowserHost::MouseButtonType type, bool up, int count) {
  CefRefPtr<CefBrowser> browser;
  { std::lock_guard<std::mutex> lock(browser_mutex_); browser = browser_; }
  if (!browser) return;
  CefMouseEvent event; event.x = x; event.y = y; event.modifiers = modifiers;
  CefPostTask(TID_UI, CefCreateClosureTask(base::BindOnce(&SendBrowserMouseButton, browser, event, type, up, count)));
}

void ProofClient::SendMouseWheel(int x, int y, uint32_t modifiers, int delta_x, int delta_y) {
  CefRefPtr<CefBrowser> browser;
  { std::lock_guard<std::mutex> lock(browser_mutex_); browser = browser_; }
  if (!browser) return;
  CefMouseEvent event; event.x = x; event.y = y; event.modifiers = modifiers;
  CefPostTask(TID_UI, CefCreateClosureTask(base::BindOnce(&SendBrowserMouseWheel, browser, event, delta_x, delta_y)));
}

void ProofClient::SendKey(std::uint32_t message, std::uintptr_t wparam, std::intptr_t lparam) {
  CefRefPtr<CefBrowser> browser;
  { std::lock_guard<std::mutex> lock(browser_mutex_); browser = browser_; }
  if (!browser) return;
  CefKeyEvent event;
  event.type = message == WM_KEYUP || message == WM_SYSKEYUP ? KEYEVENT_KEYUP :
               message == WM_CHAR || message == WM_SYSCHAR ? KEYEVENT_CHAR : KEYEVENT_RAWKEYDOWN;
  event.windows_key_code = static_cast<int>(wparam);
  event.native_key_code = static_cast<int>(lparam);
  event.is_system_key = message == WM_SYSKEYDOWN || message == WM_SYSKEYUP || message == WM_SYSCHAR;
  event.character = static_cast<char16_t>(wparam);
  event.unmodified_character = event.character;
  event.modifiers = 0;
  if (GetKeyState(VK_SHIFT) & 0x8000) event.modifiers |= EVENTFLAG_SHIFT_DOWN;
  if (GetKeyState(VK_CONTROL) & 0x8000) event.modifiers |= EVENTFLAG_CONTROL_DOWN;
  if (GetKeyState(VK_MENU) & 0x8000) event.modifiers |= EVENTFLAG_ALT_DOWN;
  CefPostTask(TID_UI, CefCreateClosureTask(base::BindOnce(&SendBrowserKey, browser, event)));
}
