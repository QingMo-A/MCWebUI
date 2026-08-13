#pragma once

#include <condition_variable>
#include <memory>
#include <mutex>
#include <map>
#include <string>
#include <thread>
#include <atomic>

#include <windows.h>

#include "include/cef_app.h"
#include "ProofClient.h"
#include "ProofMetrics.h"
#include "ProofSimulator.h"
#include "ProofOpenGLInterop.h"

class ProofApp final : public CefApp, public CefBrowserProcessHandler {
 public:
  ProofApp(std::string url, std::string mode, int width, int height,
           int target_hz, int duration_ms, bool accelerated,
           bool animate, bool simulator, bool mailbox,
           std::string present_mode, std::string output_path,
           int windowless_frame_rate_override, bool alpha_proof, bool interactive, bool auto_input,
           bool opengl_interop);
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
  const int windowless_frame_rate_override_;
  const bool alpha_proof_;
  const bool interactive_;
  const bool auto_input_;
  const bool opengl_interop_;
  std::atomic<bool> layout_ready_{false};
  std::atomic<bool> input_focus_{false};
  std::atomic<bool> input_active_{false};
  mutable std::mutex layout_mutex_;
  std::map<std::string, POINT> layout_points_;
  const int duration_ms_;
  const bool accelerated_;
  const bool animate_;
  const bool simulator_;
  const bool mailbox_;
  const std::string present_mode_;
  const std::string output_path_;
  mutable ProofMetrics metrics_;
  CefRefPtr<ProofClient> client_;
  std::thread close_thread_;
  std::thread scheduler_thread_;
  std::thread input_thread_;
  mutable std::mutex mutex_;
  std::condition_variable closed_condition_;
  bool closed_ = false;
  bool stopping_ = false;
  mutable std::mutex window_mutex_;
  HWND host_window_ = nullptr;
  std::unique_ptr<ProofSimulator> simulator_renderer_;
  std::unique_ptr<ProofOpenGLInterop> opengl_renderer_;
  IMPLEMENT_REFCOUNTING(ProofApp);
};
