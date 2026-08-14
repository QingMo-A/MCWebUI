#pragma once

#include "include/cef_app.h"
#include "include/cef_render_process_handler.h"
#include "include/wrapper/cef_message_router.h"

/** Installs the renderer-side CefQuery functions used by the MCWebUI bridge. */
class DirectCefApp final : public CefApp, public CefRenderProcessHandler {
 public:
  DirectCefApp() = default;
  CefRefPtr<CefRenderProcessHandler> GetRenderProcessHandler() override { return this; }

  void OnWebKitInitialized() override;
  void OnContextCreated(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                        CefRefPtr<CefV8Context> context) override;
  void OnContextReleased(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                         CefRefPtr<CefV8Context> context) override;
  bool OnProcessMessageReceived(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                                CefProcessId source_process,
                                CefRefPtr<CefProcessMessage> message) override;

 private:
  CefRefPtr<CefMessageRouterRendererSide> message_router_;
  IMPLEMENT_REFCOUNTING(DirectCefApp);
  DISALLOW_COPY_AND_ASSIGN(DirectCefApp);
};

CefRefPtr<CefApp> CreateDirectCefApp();
