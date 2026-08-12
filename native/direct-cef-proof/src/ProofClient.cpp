#include "ProofClient.h"

#include <iostream>
#include <sstream>
#include <utility>

#include "include/cef_browser.h"
#include "include/cef_frame.h"
#include "include/cef_task.h"
#include "include/base/cef_callback.h"
#include "include/base/cef_bind.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"

namespace {
constexpr char kRafPrefix[] = "MCWEBUI_RAF ";
void SendExternalFrame(CefRefPtr<CefBrowser> browser) {
  browser->GetHost()->SendExternalBeginFrame();
}

void CloseBrowser(CefRefPtr<CefBrowser> browser, bool force_close) {
  browser->GetHost()->CloseBrowser(force_close);
}

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
                         bool animate, ClosedCallback closed_callback)
    : metrics_(metrics), render_handler_(new ProofRenderHandler(width, height, metrics)),
      closed_callback_(std::move(closed_callback)), animate_(animate) {}

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
