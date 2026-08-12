#pragma once

#include <functional>
#include <mutex>

#include "include/cef_client.h"
#include "include/cef_display_handler.h"
#include "include/cef_load_handler.h"
#include "include/cef_life_span_handler.h"
#include "ProofMetrics.h"
#include "ProofRenderHandler.h"
#include "ProofSimulator.h"

class ProofClient final : public CefClient,
                          public CefLifeSpanHandler,
                          public CefLoadHandler,
                          public CefDisplayHandler {
 public:
  using ClosedCallback = std::function<void()>;
  using LayoutCallback = std::function<void(const std::string&)>;
  ProofClient(int width, int height, ProofMetrics* metrics,
              bool animate, ProofSimulator* simulator,
              ClosedCallback closed_callback, LayoutCallback layout_callback);
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }
  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }
  CefRefPtr<CefRenderHandler> GetRenderHandler() override { return render_handler_.get(); }
  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override;
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override;
  void OnLoadEnd(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                 int http_status_code) override;
  void OnLoadError(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                   ErrorCode error_code, const CefString& error_text,
                   const CefString& failed_url) override;
  bool OnConsoleMessage(CefRefPtr<CefBrowser> browser, cef_log_severity_t level,
                        const CefString& message, const CefString& source,
                        int line) override;
  void RequestExternalFrame();
  void Close(bool force_close);
  void SetFocus(bool focus);
  void SendMouseMove(int x, int y, uint32_t modifiers, bool leave);
  void SendMouseButton(int x, int y, uint32_t modifiers,
                       CefBrowserHost::MouseButtonType type, bool up, int count);
  void SendMouseWheel(int x, int y, uint32_t modifiers, int delta_x, int delta_y);
  void SendKey(std::uint32_t message, std::uintptr_t wparam, std::intptr_t lparam);

 private:
  ProofMetrics* const metrics_;
  CefRefPtr<ProofRenderHandler> render_handler_;
  mutable std::mutex browser_mutex_;
  CefRefPtr<CefBrowser> browser_;
  ClosedCallback closed_callback_;
  LayoutCallback layout_callback_;
  const bool animate_;
  IMPLEMENT_REFCOUNTING(ProofClient);
};
