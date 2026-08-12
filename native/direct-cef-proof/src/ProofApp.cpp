#include "ProofApp.h"

#include <algorithm>
#include <chrono>
#include <iostream>
#include <utility>

#include "include/cef_browser.h"
#include "include/cef_command_line.h"
#include "include/internal/cef_types_win.h"
#include "include/wrapper/cef_helpers.h"

namespace {
std::string ChildProcessSwitchSummary(CefRefPtr<CefCommandLine> command_line) {
  CefCommandLine::SwitchMap switches;
  command_line->GetSwitches(switches);
  std::ostringstream summary;
  for (const auto& item : switches) {
    const std::string name = item.first.ToString();
    if (name != "type" && name != "disable-gpu" && name != "disable-gpu-compositing" &&
        name != "use-angle" && name != "use-gl" && name != "in-process-gpu" &&
        name != "gpu-preferences" && name != "gpu-testing" && name != "gpu-vendor-id" &&
        name != "gpu-device-id") {
      continue;
    }
    if (summary.tellp() > 0) summary << ' ';
    summary << "--" << name;
    const std::string value = item.second.ToString();
    if (!value.empty()) summary << '=' << value;
  }
  return summary.str();
}
}  // namespace

ProofApp::ProofApp(std::string url, std::string mode, int width, int height,
                   int target_hz, int duration_ms, bool accelerated,
                   bool animate, bool simulator, std::string output_path)
    : url_(std::move(url)), mode_(std::move(mode)), width_(width), height_(height),
      target_hz_(target_hz), duration_ms_(duration_ms), accelerated_(accelerated),
      animate_(animate), simulator_(simulator),
      output_path_(std::move(output_path)),
      metrics_(mode_, target_hz_, width_, height_, accelerated_, animate_) {}

void ProofApp::OnBeforeChildProcessLaunch(CefRefPtr<CefCommandLine> command_line) {
  CEF_REQUIRE_IO_THREAD();
  const std::string process_type = command_line->GetSwitchValue("type").ToString();
  metrics_.RecordChildProcess(process_type, ChildProcessSwitchSummary(command_line));
}

void ProofApp::OnContextInitialized() {
  CEF_REQUIRE_UI_THREAD();
  CefWindowInfo window_info;
  const bool windowed = mode_ == "windowed-baseline";
  if (windowed) {
    // Windowed baseline intentionally uses CEF's normal native top-level
    // browser window.  It is a reference for Chromium/rAF scheduling and is
    // not presented as an MCWebUI backend.
    window_info.SetAsPopup(nullptr, "MCWebUI Windowed Baseline");
  } else {
    host_window_ = CreateWindowExW(WS_EX_NOACTIVATE, L"STATIC",
        L"MCWebUI Direct CEF Proof", WS_POPUP, 0, 0, width_, height_,
        nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!host_window_) {
      std::cerr << "[direct-cef-proof] hidden host window creation failed"
                << std::endl;
      SignalClosed();
      return;
    }
    // A real hidden native parent is required for the Windows shared-texture
    // path. The proof remains headless; this window is never shown.
    window_info.SetAsWindowless(host_window_);
    window_info.shared_texture_enabled = accelerated_;
    window_info.external_begin_frame_enabled = mode_ == "external-begin-frame";
  }
  CefBrowserSettings browser_settings;
  browser_settings.windowless_frame_rate = (windowed || mode_ == "backend-default") ? 0 :
      std::clamp(target_hz_, 1, 60);
  if (simulator_ && !windowed) {
    simulator_renderer_ = std::make_unique<ProofSimulator>(host_window_, width_, height_);
    if (!simulator_renderer_->Initialize()) {
      std::cerr << "[direct-cef-proof] D3D simulator initialization failed" << std::endl;
      SignalClosed();
      return;
    }
  }
  client_ = new ProofClient(width_, height_, &metrics_, animate_, simulator_renderer_.get(),
                            [this] { SignalClosed(); });
  if (!CefBrowserHost::CreateBrowser(window_info, client_, url_, browser_settings,
                                     nullptr, nullptr)) {
    std::cerr << "[direct-cef-proof] CreateBrowser returned false" << std::endl;
    SignalClosed();
    return;
  }
  if (mode_ == "external-begin-frame") {
    scheduler_thread_ = std::thread([this] {
      const auto interval = std::chrono::duration<double>(1.0 / std::max(1, target_hz_));
      auto next = std::chrono::steady_clock::now();
      while (true) {
        {
          std::lock_guard<std::mutex> lock(mutex_);
          if (stopping_) break;
        }
        next += std::chrono::duration_cast<std::chrono::steady_clock::duration>(interval);
        if (client_) client_->RequestExternalFrame();
        std::this_thread::sleep_until(next);
      }
    });
  }
  close_thread_ = std::thread([this] {
    std::this_thread::sleep_for(std::chrono::milliseconds(duration_ms_));
    if (client_) client_->Close(true);
  });
}

void ProofApp::SignalClosed() {
  // OnBeforeClose executes on CEF's UI thread, so the hidden HWND is destroyed
  // on the same thread that created it.
  if (host_window_) DestroyWindow(host_window_);
  host_window_ = nullptr;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    closed_ = true;
    stopping_ = true;
  }
  closed_condition_.notify_all();
}

void ProofApp::WaitForClose() {
  std::unique_lock<std::mutex> lock(mutex_);
  closed_condition_.wait(lock, [this] { return closed_; });
  lock.unlock();
  if (close_thread_.joinable()) close_thread_.join();
  if (scheduler_thread_.joinable()) scheduler_thread_.join();
}

bool ProofApp::WriteResult() const {
  std::cout << metrics_.FormatLine() << std::endl;
  return metrics_.WriteJson(output_path_);
}
