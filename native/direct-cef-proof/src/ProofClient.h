#pragma once

#include <functional>
#include <mutex>

#include "include/cef_client.h"
#include "include/cef_display_handler.h"
#include "include/cef_load_handler.h"
#include "include/cef_life_span_handler.h"
#include "ProofMetrics.h"
#include "ProofRenderHandler.h"

class ProofClient final : public CefClient,
                          public CefLifeSpanHandler,
                          public CefLoadHandler,
                          public CefDisplayHandler {
 public:
  using ClosedCallback = std::function<void()>;
  ProofClient(int width, int height, ProofMetrics* metrics,
              bool animate, ClosedCallback closed_callback);
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

 private:
  ProofMetrics* const metrics_;
  CefRefPtr<ProofRenderHandler> render_handler_;
  mutable std::mutex browser_mutex_;
  CefRefPtr<CefBrowser> browser_;
  ClosedCallback closed_callback_;
  const bool animate_;
  IMPLEMENT_REFCOUNTING(ProofClient);
};
