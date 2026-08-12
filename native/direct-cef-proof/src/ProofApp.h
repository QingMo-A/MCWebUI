#pragma once

#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>

#include <windows.h>

#include "include/cef_app.h"
#include "ProofClient.h"
#include "ProofMetrics.h"
#include "ProofSimulator.h"

class ProofApp final : public CefApp, public CefBrowserProcessHandler {
 public:
  ProofApp(std::string url, std::string mode, int width, int height,
           int target_hz, int duration_ms, bool accelerated,
           bool animate, bool simulator, std::string output_path);
  CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override { return this; }
  void OnBeforeChildProcessLaunch(CefRefPtr<CefCommandLine> command_line) override;
  void OnContextInitialized() override;
  CefRefPtr<CefClient> GetDefaultClient() override { return client_; }
  void WaitForClose();
  bool WriteResult() const;

 private:
  void SignalClosed();
  const std::string url_;
  const std::string mode_;
  const int width_;
  const int height_;
  const int target_hz_;
  const int duration_ms_;
  const bool accelerated_;
  const bool animate_;
  const bool simulator_;
  const std::string output_path_;
  mutable ProofMetrics metrics_;
  CefRefPtr<ProofClient> client_;
  std::thread close_thread_;
  std::thread scheduler_thread_;
  mutable std::mutex mutex_;
  std::condition_variable closed_condition_;
  bool closed_ = false;
  bool stopping_ = false;
  HWND host_window_ = nullptr;
  std::unique_ptr<ProofSimulator> simulator_renderer_;
  IMPLEMENT_REFCOUNTING(ProofApp);
};
