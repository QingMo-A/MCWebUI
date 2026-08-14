#include "DirectCefApp.h"

#include "include/wrapper/cef_helpers.h"

void DirectCefApp::OnWebKitInitialized() {
  CEF_REQUIRE_RENDERER_THREAD();
  CefMessageRouterConfig config;
  message_router_ = CefMessageRouterRendererSide::Create(config);
}

void DirectCefApp::OnContextCreated(CefRefPtr<CefBrowser> browser,
                                    CefRefPtr<CefFrame> frame,
                                    CefRefPtr<CefV8Context> context) {
  CEF_REQUIRE_RENDERER_THREAD();
  if (message_router_) message_router_->OnContextCreated(browser, frame, context);
}

void DirectCefApp::OnContextReleased(CefRefPtr<CefBrowser> browser,
                                     CefRefPtr<CefFrame> frame,
                                     CefRefPtr<CefV8Context> context) {
  CEF_REQUIRE_RENDERER_THREAD();
  if (message_router_) message_router_->OnContextReleased(browser, frame, context);
}

bool DirectCefApp::OnProcessMessageReceived(CefRefPtr<CefBrowser> browser,
                                            CefRefPtr<CefFrame> frame,
                                            CefProcessId source_process,
                                            CefRefPtr<CefProcessMessage> message) {
  CEF_REQUIRE_RENDERER_THREAD();
  return message_router_ &&
         message_router_->OnProcessMessageReceived(browser, frame, source_process, message);
}

CefRefPtr<CefApp> CreateDirectCefApp() { return new DirectCefApp(); }
