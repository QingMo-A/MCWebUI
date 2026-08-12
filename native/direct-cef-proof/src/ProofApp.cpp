#include "ProofApp.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <regex>
#include <sstream>
#include <map>
#include <utility>

#include "include/cef_browser.h"
#include "include/cef_command_line.h"
#include "include/cef_version.h"
#include "include/internal/cef_types_win.h"
#include "include/wrapper/cef_helpers.h"
#include <windowsx.h>

namespace {
// CEF 5845 (and the older CEF APIs it represents) documented a 60 Hz
// windowless-rendering ceiling. Modern CEF removed that compatibility limit;
// keep the legacy clamp for old SDKs while allowing the requested rate on
// newer SDKs (including the pinned CEF 144 proof build).
int ConfigureWindowlessFrameRate(int requested_hz, bool windowed,
                                 const std::string& mode,
                                 int override_hz) {
  if (windowed || mode == "backend-default") return 0;
  const int effective_hz = override_hz > 0 ? override_hz : requested_hz;
#if CEF_VERSION_MAJOR >= 144
  return std::max(1, effective_hz);
#else
  return std::clamp(effective_hz, 1, 60);
#endif
}

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

std::map<std::string, POINT> ParseLayoutPoints(const std::string& payload) {
  std::map<std::string, POINT> points;
  static const std::regex point_pattern(
      R"REGEX("([a-z]+)"\s*:\s*\{\s*"x"\s*:\s*([-+0-9.eE]+)\s*,\s*"y"\s*:\s*([-+0-9.eE]+))REGEX",
      std::regex::optimize);
  for (std::sregex_iterator it(payload.begin(), payload.end(), point_pattern), end;
       it != end; ++it) {
    const std::smatch& match = *it;
    const std::string name = match[1].str();
      if (name != "button" && name != "range" && name != "checkbox" &&
        name != "select" && name != "text" && name != "scroll" &&
        name != "modal" && name != "modal-close") {
      continue;
    }
    const std::string x_text = match[2].str();
    const std::string y_text = match[3].str();
    char* x_end = nullptr;
    char* y_end = nullptr;
    const double x = std::strtod(x_text.c_str(), &x_end);
    const double y = std::strtod(y_text.c_str(), &y_end);
    if (x_end == x_text.c_str() || y_end == y_text.c_str()) continue;
    points[name] = POINT{static_cast<LONG>(std::lround(x)),
                         static_cast<LONG>(std::lround(y))};
  }
  return points;
}
}  // namespace

ProofApp::ProofApp(std::string url, std::string mode, int width, int height,
                   int target_hz, int duration_ms, bool accelerated,
                   bool animate, bool simulator, bool mailbox,
                   std::string present_mode, std::string output_path,
                   int windowless_frame_rate_override, bool alpha_proof, bool interactive, bool auto_input)
    : url_(std::move(url)), mode_(std::move(mode)), width_(width), height_(height),
      target_hz_(target_hz), duration_ms_(duration_ms), accelerated_(accelerated),
      animate_(animate), simulator_(simulator), mailbox_(mailbox),
      windowless_frame_rate_override_(windowless_frame_rate_override),
      alpha_proof_(alpha_proof),
      interactive_(interactive),
      auto_input_(auto_input),
      present_mode_(std::move(present_mode)),
      output_path_(std::move(output_path)),
      metrics_(mode_, target_hz_, width_, height_, accelerated_, animate_,
               mailbox_ ? "mailbox" : (simulator_ ? "coupled" : "none"),
               present_mode_) {}

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
    HWND created_window = CreateWindowExW(interactive_ ? 0 : WS_EX_NOACTIVATE, L"STATIC",
        L"MCWebUI Direct CEF Proof", WS_POPUP, 0, 0, width_, height_,
        nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!created_window) {
      std::cerr << "[direct-cef-proof] hidden host window creation failed"
                << std::endl;
      SignalClosed();
      return;
    }
    {
      std::lock_guard<std::mutex> lock(window_mutex_);
      host_window_ = created_window;
    }
    if (interactive_) ShowWindow(host_window_, SW_SHOW);
    input_active_.store(interactive_);
    // A real hidden native parent is required for the Windows shared-texture
    // path. The proof remains headless; this window is never shown.
    window_info.SetAsWindowless(host_window_);
    window_info.shared_texture_enabled = accelerated_;
    window_info.external_begin_frame_enabled = mode_ == "external-begin-frame";
  }
  CefBrowserSettings browser_settings;
  const int configured_windowless_frame_rate =
      ConfigureWindowlessFrameRate(target_hz_, windowed, mode_,
                                   windowless_frame_rate_override_);
  metrics_.RecordWindowlessFrameRate(configured_windowless_frame_rate);
  browser_settings.windowless_frame_rate = configured_windowless_frame_rate;
  if (simulator_ && !windowed) {
    simulator_renderer_ = std::make_unique<ProofSimulator>(
        host_window_, width_, height_, &metrics_, mailbox_, target_hz_,
        present_mode_ == "uncoupled", alpha_proof_);
    if (!simulator_renderer_->Initialize()) {
      std::cerr << "[direct-cef-proof] D3D simulator initialization failed" << std::endl;
      SignalClosed();
      return;
    }
  }
  client_ = new ProofClient(width_, height_, &metrics_, animate_, simulator_renderer_.get(),
                            [this] { SignalClosed(); }, [this](const std::string& payload) {
                              if (payload.find("\"kind\":\"escape\"") != std::string::npos) {
                                if (client_) client_->Close(true);
                                return;
                              }
                              auto points = ParseLayoutPoints(payload);
                              auto button = points.find("button");
                              if (button == points.end()) return;
                              {
                                std::lock_guard<std::mutex> lock(layout_mutex_);
                                layout_points_ = std::move(points);
                              }
                              layout_ready_.store(true);
                            });
  if (simulator_renderer_ && interactive_) {
    simulator_renderer_->SetInputSink([this](const InputEvent& event) {
      if (!input_active_.load()) return;
      const auto client = client_;
      if (!client) return;
      switch (event.kind) {
        case InputEventKind::Focus:
          input_focus_.store(event.focused);
          client->SetFocus(event.focused);
          metrics_.RecordInputDispatch("focus");
          break;
        case InputEventKind::MouseMove:
          client->SendMouseMove(event.x, event.y, event.modifiers, event.leave);
          metrics_.RecordInputDispatch("mouseMove");
          break;
        case InputEventKind::MouseButton:
          client->SendMouseButton(event.x, event.y, event.modifiers,
                                  event.button == 0 ? MBT_LEFT : (event.button == 1 ? MBT_RIGHT : MBT_MIDDLE),
                                  !event.pressed, 1);
          metrics_.RecordInputDispatch("mouseButton");
          break;
        case InputEventKind::MouseWheel:
          client->SendMouseWheel(event.x, event.y, event.modifiers, event.delta_x, event.delta_y);
          metrics_.RecordInputDispatch("mouseWheel");
          break;
        case InputEventKind::Key:
          client->SendKey(event.message, event.wparam, event.lparam);
          metrics_.RecordInputDispatch("key");
          break;
        case InputEventKind::CaptureLost:
          metrics_.RecordInputDispatch("captureLost");
          break;
        case InputEventKind::Close:
          client->Close(true);
          metrics_.RecordInputDispatch("close");
          break;
      }
    });
  }
  if (!CefBrowserHost::CreateBrowser(window_info, client_, url_, browser_settings,
                                     nullptr, nullptr)) {
    std::cerr << "[direct-cef-proof] CreateBrowser returned false" << std::endl;
    SignalClosed();
    return;
  }
  if (simulator_renderer_ && simulator_renderer_->MailboxMode())
    simulator_renderer_->StartConsumer();
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
  if (duration_ms_ > 0) {
    close_thread_ = std::thread([this] {
      std::this_thread::sleep_for(std::chrono::milliseconds(duration_ms_));
      if (client_) client_->Close(true);
    });
  }
  if (interactive_ && auto_input_) {
    input_thread_ = std::thread([this] {
      // Wait for the main-frame layout probe.  Coordinates are supplied by
      // getBoundingClientRect and then used for real native messages; no DOM
      // event is synthesized by this harness.
      for (int attempt = 0; attempt < 40 && !layout_ready_.load(); ++attempt)
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
      if (!input_active_.load()) return;
      if (!layout_ready_.load()) return;
      HWND window = nullptr;
      {
        std::lock_guard<std::mutex> lock(window_mutex_);
        window = host_window_;
      }
      if (!window || !input_active_.load() || !IsWindow(window)) return;
      std::map<std::string, POINT> points;
      {
        std::lock_guard<std::mutex> lock(layout_mutex_);
        points = layout_points_;
      }
      auto point = [&points](const char* name) -> POINT {
        const auto it = points.find(name);
        return it == points.end() ? POINT{} : it->second;
      };
      auto mouse = [this, window](POINT target, UINT message, WPARAM buttons = 0) {
        if (!input_active_.load() || !IsWindow(window)) return false;
        return PostMessageW(window, message, buttons, MAKELPARAM(target.x, target.y)) != FALSE;
      };
      auto click = [&mouse](POINT target) {
        if (!mouse(target, WM_MOUSEMOVE)) return false;
        if (!mouse(target, WM_LBUTTONDOWN, MK_LBUTTON)) return false;
        if (!mouse(target, WM_LBUTTONUP)) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(80));
        return true;
      };
      if (!click(point("button")) || !click(point("checkbox"))) return;

      POINT range = point("range");
      POINT range_end{range.x + 120, range.y};
      if (!mouse(range, WM_MOUSEMOVE) || !mouse(range, WM_LBUTTONDOWN, MK_LBUTTON) ||
          !mouse(range_end, WM_MOUSEMOVE, MK_LBUTTON) || !mouse(range_end, WM_LBUTTONUP)) return;
      std::this_thread::sleep_for(std::chrono::milliseconds(80));

      if (!click(point("select"))) return;
      if (!input_active_.load() || !PostMessageW(window, WM_KEYDOWN, VK_DOWN, 0) ||
          !PostMessageW(window, WM_KEYUP, VK_DOWN, 0) ||
          !PostMessageW(window, WM_KEYDOWN, VK_RETURN, 0) ||
          !PostMessageW(window, WM_KEYUP, VK_RETURN, 0)) return;
      std::this_thread::sleep_for(std::chrono::milliseconds(80));

      if (!click(point("text"))) return;
      for (wchar_t character : std::wstring(L"Hello MCWebUI 123"))
        if (!input_active_.load() || !PostMessageW(window, WM_CHAR, character, 0)) return;
      if (!input_active_.load() || !PostMessageW(window, WM_KEYDOWN, VK_LEFT, 0) ||
          !PostMessageW(window, WM_KEYUP, VK_LEFT, 0) ||
          !PostMessageW(window, WM_KEYDOWN, VK_BACK, 0) ||
          !PostMessageW(window, WM_KEYUP, VK_BACK, 0) ||
          !PostMessageW(window, WM_CHAR, L'3', 0)) return;
      std::this_thread::sleep_for(std::chrono::milliseconds(80));

      POINT scroll = point("scroll");
      POINT scroll_screen = scroll;
      ClientToScreen(window, &scroll_screen);
      if (!input_active_.load() || !PostMessageW(window, WM_MOUSEWHEEL,
                   MAKEWPARAM(0, -WHEEL_DELTA),
                   MAKELPARAM(scroll_screen.x, scroll_screen.y))) return;
      std::this_thread::sleep_for(std::chrono::milliseconds(100));

      if (!click(point("modal"))) return;
      if (!input_active_.load() || !PostMessageW(window, WM_KEYDOWN, VK_ESCAPE, 0) ||
          !PostMessageW(window, WM_KEYUP, VK_ESCAPE, 0)) return;
      std::this_thread::sleep_for(std::chrono::milliseconds(120));
      if (!input_active_.load()) return;
      PostMessageW(window, WM_KEYDOWN, VK_ESCAPE, 0);
      PostMessageW(window, WM_KEYUP, VK_ESCAPE, 0);
    });
  }
}

void ProofApp::SignalClosed() {
  input_active_.store(false);
  // The auto-input worker posts native messages asynchronously.  Join it
  // before removing the subclass or destroying the HWND so no worker can
  // enqueue a message against a window whose subclass owner is gone.
  if (input_thread_.joinable() &&
      input_thread_.get_id() != std::this_thread::get_id()) {
    input_thread_.join();
  }
  if (simulator_renderer_) simulator_renderer_->Stop();
  // OnBeforeClose executes on CEF's UI thread, so the hidden HWND is destroyed
  // on the same thread that created it.
  HWND window = nullptr;
  {
    std::lock_guard<std::mutex> lock(window_mutex_);
    window = host_window_;
  }
  if (window) DestroyWindow(window);
  {
    std::lock_guard<std::mutex> lock(window_mutex_);
    if (host_window_ == window) host_window_ = nullptr;
  }
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
  if (input_thread_.joinable()) input_thread_.join();
}

bool ProofApp::WriteResult() const {
  std::cout << metrics_.FormatLine() << std::endl;
  return metrics_.WriteJson(output_path_);
}
