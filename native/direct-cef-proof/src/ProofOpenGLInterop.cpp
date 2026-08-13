#include "ProofOpenGLInterop.h"

#include <windowsx.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <sstream>
#include <iostream>
#include <fstream>

#include <GL/gl.h>
#include <dxgi.h>

#include "ProofMetrics.h"
#include "ProofSimulator.h"

namespace {
constexpr unsigned WGL_ACCESS_READ_ONLY_NV = 0x0000;
constexpr unsigned kGlClampToEdge = 0x812F;

using WglGetExtensionsStringARB = const char*(WINAPI*)(HDC);
using WglSwapIntervalEXT = BOOL(WINAPI*)(int);

bool HasExtension(const std::string& extensions, const char* name) {
  std::istringstream stream(extensions);
  std::string token;
  while (stream >> token) {
    if (token == name) return true;
  }
  return false;
}

std::string WideToUtf8(const wchar_t* value) {
  if (!value || !*value) return {};
  const int size = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
  if (size <= 1) return {};
  std::string result(static_cast<std::size_t>(size - 1), '\0');
  WideCharToMultiByte(CP_UTF8, 0, value, -1, result.data(), size, nullptr, nullptr);
  return result;
}

LRESULT CALLBACK OpenGLWindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  return DefWindowProcW(window, message, wparam, lparam);
}

unsigned GetLastErrorCode() {
  return static_cast<unsigned>(::GetLastError());
}

void InteropLog(const std::string& message) {
  wchar_t path[MAX_PATH] = {};
  if (!GetTempPathW(MAX_PATH, path)) return;
  std::ofstream out(std::filesystem::path(path) / "mcwebui-opengl-interop.log",
                    std::ios::app);
  out << message << '\n';
}
}  // namespace

ProofOpenGLInterop::ProofOpenGLInterop(HWND owner, int width, int height, int target_hz,
                                       ProofSimulator* mailbox, ProofMetrics* metrics,
                                       bool interactive, bool alpha_proof)
    : owner_(owner), width_(width), height_(height), target_hz_(std::max(1, target_hz)),
      mailbox_(mailbox), metrics_(metrics), interactive_(interactive), alpha_proof_(alpha_proof) {}

ProofOpenGLInterop::~ProofOpenGLInterop() { Stop(); }

bool ProofOpenGLInterop::Initialize() {
  if (!owner_ || !mailbox_ || !metrics_) return false;
  stopping_.store(false);
  initialized_ = false;
  thread_ = std::thread(&ProofOpenGLInterop::ThreadMain, this);
  std::unique_lock<std::mutex> lock(state_mutex_);
  state_cv_.wait_for(lock, std::chrono::seconds(5), [this] { return initialized_; });
  return initialized_;
}

void ProofOpenGLInterop::Stop() {
  stopping_.store(true);
  if (thread_.joinable()) thread_.join();
}

bool ProofOpenGLInterop::CreateContext() {
  InteropLog("create-context begin");
  std::cerr << "[direct-cef-opengl] create-context begin" << std::endl;
  static const wchar_t kClassName[] = L"MCWebUIProofOpenGL";
  static std::once_flag register_once;
  std::call_once(register_once, [] {
    WNDCLASSW window_class{};
    window_class.lpfnWndProc = &OpenGLWindowProc;
    window_class.hInstance = GetModuleHandleW(nullptr);
    window_class.lpszClassName = kClassName;
    RegisterClassW(&window_class);
  });
  // Keep the WGL drawable independent from the CEF/D3D host HWND. Sharing the
  // host DC can make driver teardown block while CEF is unwinding its OSR
  // callback; this hidden top-level window has its own WGL-owned DC.
  gl_window_ = CreateWindowExW(WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, kClassName,
                               L"MCWebUI OpenGL Interop Proof",
                               WS_POPUP | WS_CLIPSIBLINGS | WS_CLIPCHILDREN,
                               -32000, -32000, width_, height_, nullptr, nullptr,
                               GetModuleHandleW(nullptr), nullptr);
  if (!gl_window_) {
    InteropLog("CreateWindowEx failed=" + std::to_string(GetLastErrorCode()));
    return false;
  }
  dc_ = GetDC(gl_window_);
  if (!dc_) {
    InteropLog("GetDC failed=" + std::to_string(GetLastErrorCode()));
    return false;
  }
  PIXELFORMATDESCRIPTOR descriptor{};
  descriptor.nSize = sizeof(descriptor);
  descriptor.nVersion = 1;
  descriptor.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
  descriptor.iPixelType = PFD_TYPE_RGBA;
  descriptor.cColorBits = 32;
  descriptor.cAlphaBits = 8;
  const int format = ChoosePixelFormat(dc_, &descriptor);
  if (!format || !SetPixelFormat(dc_, format, &descriptor)) {
    InteropLog("pixel format failed=" + std::to_string(GetLastErrorCode()));
    return false;
  }
  context_ = wglCreateContext(dc_);
  if (!context_ || !wglMakeCurrent(dc_, context_)) {
    InteropLog("WGL context failed=" + std::to_string(GetLastErrorCode()));
    return false;
  }
  using SwapInterval = BOOL(WINAPI*)(int);
  auto swap_interval = reinterpret_cast<SwapInterval>(wglGetProcAddress("wglSwapIntervalEXT"));
  if (swap_interval) swap_interval(0);
  if (interactive_) ShowWindow(gl_window_, SW_SHOWNA);
  else ShowWindow(gl_window_, SW_HIDE);
  InteropLog("create-context done");
  return true;
}

bool ProofOpenGLInterop::LoadInteropFunctions() {
  InteropLog("load-capabilities begin");
  std::cerr << "[direct-cef-opengl] load-capabilities begin" << std::endl;
  auto get = [](const char* name) -> PROC { return wglGetProcAddress(name); };
  auto extension_string = reinterpret_cast<WglGetExtensionsStringARB>(
      get("wglGetExtensionsStringARB"));
  if (extension_string) {
    const char* value = extension_string(dc_);
    if (value) extensions_ = value;
  }
  if (extensions_.empty()) {
    const char* value = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (value) extensions_ = value ? value : "";
  }
  const char* vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
  const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
  const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
  vendor_ = vendor ? vendor : "";
  renderer_ = renderer ? renderer : "";
  version_ = version ? version : "";
  extension_nv_ = HasExtension(extensions_, "WGL_NV_DX_interop");
  extension_nv2_ = HasExtension(extensions_, "WGL_NV_DX_interop2");
  wgl_dx_open_device_ = reinterpret_cast<WglDXOpenDeviceNV>(get("wglDXOpenDeviceNV"));
  wgl_dx_close_device_ = reinterpret_cast<WglDXCloseDeviceNV>(get("wglDXCloseDeviceNV"));
  wgl_dx_register_object_ = reinterpret_cast<WglDXRegisterObjectNV>(get("wglDXRegisterObjectNV"));
  wgl_dx_unregister_object_ = reinterpret_cast<WglDXUnregisterObjectNV>(get("wglDXUnregisterObjectNV"));
  wgl_dx_object_access_ = reinterpret_cast<WglDXObjectAccessNV>(get("wglDXObjectAccessNV"));
  wgl_dx_lock_objects_ = reinterpret_cast<WglDXLockObjectsNV>(get("wglDXLockObjectsNV"));
  wgl_dx_unlock_objects_ = reinterpret_cast<WglDXUnlockObjectsNV>(get("wglDXUnlockObjectsNV"));
  wgl_dx_set_resource_share_handle_ =
      reinterpret_cast<WglDXSetResourceShareHandleNV>(get("wglDXSetResourceShareHandleNV"));
  entries_complete_ = wgl_dx_open_device_ && wgl_dx_close_device_ &&
      wgl_dx_register_object_ && wgl_dx_unregister_object_ &&
      wgl_dx_object_access_ && wgl_dx_lock_objects_ &&
      wgl_dx_unlock_objects_ && wgl_dx_set_resource_share_handle_;
  if (mailbox_ && mailbox_->NativeDevice()) {
    Microsoft::WRL::ComPtr<IDXGIDevice> dxgi_device;
    Microsoft::WRL::ComPtr<IDXGIAdapter> adapter;
    if (SUCCEEDED(mailbox_->NativeDevice()->QueryInterface(IID_PPV_ARGS(&dxgi_device))) &&
        SUCCEEDED(dxgi_device->GetAdapter(&adapter))) {
      DXGI_ADAPTER_DESC desc{};
      if (SUCCEEDED(adapter->GetDesc(&desc))) {
        adapter_ = WideToUtf8(desc.Description);
        luid_high_ = static_cast<std::uint32_t>(static_cast<std::uint64_t>(desc.AdapterLuid.HighPart));
        luid_low_ = static_cast<std::uint32_t>(desc.AdapterLuid.LowPart);
      }
    }
  }
  metrics_->BeginOpenGLInterop(extension_nv_, extension_nv2_, entries_complete_,
                               vendor_, renderer_, version_, extensions_, adapter_,
                               luid_high_, luid_low_);
  InteropLog("vendor=" + vendor_ + " renderer=" + renderer_ +
             " version=" + version_ + " nv=" + std::to_string(extension_nv_) +
             " nv2=" + std::to_string(extension_nv2_) +
             " entries=" + std::to_string(entries_complete_));
  return extension_nv2_ && entries_complete_;
}

void ProofOpenGLInterop::SetStatusUnsupported() {
  metrics_->FinishOpenGLProof(false, false, flip_y_);
}

void ProofOpenGLInterop::SetStatusFailed() {
  metrics_->RecordOpenGLDevice(false, GetLastErrorCode());
}

bool ProofOpenGLInterop::RegisterTexture(int slot, ID3D11Texture2D* texture) {
  if (!texture || !interop_device_ || !wgl_dx_register_object_) return false;
  if (textures_.find(slot) != textures_.end()) return true;
  GLuint name = 0;
  glGenTextures(1, &name);
  glBindTexture(GL_TEXTURE_2D, name);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, kGlClampToEdge);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, kGlClampToEdge);
  HANDLE object = wgl_dx_register_object_(interop_device_, texture, name,
                                           GL_TEXTURE_2D, WGL_ACCESS_READ_ONLY_NV);
  const unsigned last_error = object ? 0u : GetLastErrorCode();
  metrics_->RecordOpenGLRegistration(object != nullptr, static_cast<unsigned>(slot), last_error);
  if (!object) {
    glDeleteTextures(1, &name);
    return false;
  }
  if (!wgl_dx_object_access_(object, WGL_ACCESS_READ_ONLY_NV)) {
    const unsigned access_error = GetLastErrorCode();
    wgl_dx_unregister_object_(interop_device_, object);
    glDeleteTextures(1, &name);
    metrics_->RecordOpenGLRegistration(false, static_cast<unsigned>(slot), access_error);
    return false;
  }
  RegisteredTexture registered;
  registered.gl_name = name;
  registered.interop_object = object;
  registered.texture = texture;
  textures_.emplace(slot, std::move(registered));
  return true;
}

void ProofOpenGLInterop::UnregisterTextures() {
  for (auto& entry : textures_) {
    if (entry.second.interop_object && wgl_dx_unregister_object_ && interop_device_)
      wgl_dx_unregister_object_(interop_device_, entry.second.interop_object);
    if (entry.second.gl_name) glDeleteTextures(1, &entry.second.gl_name);
  }
  textures_.clear();
}

bool ProofOpenGLInterop::DrawFrame(unsigned texture_name, bool fixed_blue, bool flip_y) {
  glViewport(0, 0, width_, height_);
  glMatrixMode(GL_PROJECTION);
  glLoadIdentity();
  glOrtho(-1, 1, -1, 1, -1, 1);
  glMatrixMode(GL_MODELVIEW);
  glLoadIdentity();
  const float phase = static_cast<float>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch()).count() % 2000) / 2000.0f;
  glDisable(GL_TEXTURE_2D);
  glDisable(GL_BLEND);
  glClearColor(0.04f + 0.03f * phase, 0.06f, 0.13f + 0.03f * (1.0f - phase), 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);
  if (!texture_name) return true;
  glEnable(GL_TEXTURE_2D);
  glEnable(GL_BLEND);
  glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
  glColor4f(1, 1, 1, 1);
  glBindTexture(GL_TEXTURE_2D, texture_name);
  const float y0 = flip_y ? 1.0f : 0.0f;
  const float y1 = flip_y ? 0.0f : 1.0f;
  glBegin(GL_QUADS);
  glTexCoord2f(0, y0); glVertex2f(-1, -1);
  glTexCoord2f(1, y0); glVertex2f(1, -1);
  glTexCoord2f(1, y1); glVertex2f(1, 1);
  glTexCoord2f(0, y1); glVertex2f(-1, 1);
  glEnd();
  glDisable(GL_BLEND);
  glDisable(GL_TEXTURE_2D);
  return true;
}

bool ProofOpenGLInterop::ReadAlphaSamples(unsigned texture_name) {
  if (!alpha_proof_ || !texture_name) return true;
  const auto points = mailbox_->AlphaSamplePoints();
  const std::array<const char*, 5> names = {
      "world-reveal", "alpha25", "alpha50", "alpha75", "alpha100"};
  const std::array<unsigned, 5> alpha = {0, 64, 128, 191, 255};
  for (const char* name : names) {
    if (points.find(name) == points.end()) return false;
  }
  glViewport(0, 0, width_, height_);
  glMatrixMode(GL_PROJECTION); glLoadIdentity(); glOrtho(-1, 1, -1, 1, -1, 1);
  glMatrixMode(GL_MODELVIEW); glLoadIdentity();
  glDisable(GL_DEPTH_TEST); glEnable(GL_TEXTURE_2D); glEnable(GL_BLEND);
  glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
  glBindTexture(GL_TEXTURE_2D, texture_name);
  glClearColor(0, 0, 1, 1); glClear(GL_COLOR_BUFFER_BIT);
  const float y0 = flip_y_ ? 1.0f : 0.0f;
  const float y1 = flip_y_ ? 0.0f : 1.0f;
  glBegin(GL_QUADS);
  glTexCoord2f(0, y0); glVertex2f(-1, -1);
  glTexCoord2f(1, y0); glVertex2f(1, -1);
  glTexCoord2f(1, y1); glVertex2f(1, 1);
  glTexCoord2f(0, y1); glVertex2f(-1, 1);
  glEnd();
  glFlush();
  std::array<unsigned char, 4> pixel{};
  bool all_passed = true;
  for (unsigned index = 0; index < names.size(); ++index) {
    const POINT point = points.at(names[index]);
    const GLint read_y = std::max<GLint>(0, std::min<GLint>(
        height_ - 1, static_cast<GLint>(height_ - 1 - point.y)));
    glReadPixels(std::max<GLint>(0, std::min<GLint>(width_ - 1, point.x)),
                 read_y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel.data());
    const unsigned source_alpha = alpha[index];
    const unsigned source_r = (214 * source_alpha + 127) / 255;
    const unsigned source_g = (74 * source_alpha + 127) / 255;
    const unsigned source_b = (88 * source_alpha + 127) / 255;
    const unsigned expected_r = source_r;
    const unsigned expected_g = source_g;
    const unsigned expected_b = source_b + 255 - source_alpha;
    const bool passed = std::abs(static_cast<int>(pixel[0]) - static_cast<int>(expected_r)) <= 24 &&
        std::abs(static_cast<int>(pixel[1]) - static_cast<int>(expected_g)) <= 24 &&
        std::abs(static_cast<int>(pixel[2]) - static_cast<int>(expected_b)) <= 24 &&
        std::abs(static_cast<int>(pixel[3]) - 255) <= 24;
    metrics_->RecordOpenGLSample(names[index], true, pixel[0], pixel[1], pixel[2], pixel[3],
                                 expected_r, expected_g, expected_b, 255, passed);
    all_passed = all_passed && passed;
  }
  glDisable(GL_BLEND); glDisable(GL_TEXTURE_2D);
  return all_passed;
}

void ProofOpenGLInterop::ThreadMain() {
  InteropLog("thread begin");
  std::cerr << "[direct-cef-opengl] thread begin" << std::endl;
  bool context_ok = CreateContext();
  InteropLog("context=" + std::to_string(context_ok));
  bool capabilities_ok = context_ok && LoadInteropFunctions();
  InteropLog("capabilities=" + std::to_string(capabilities_ok));
  if (!context_ok) {
    metrics_->BeginOpenGLInterop(false, false, false, {}, {}, {}, {}, {}, 0, 0);
  }
  {
    std::lock_guard<std::mutex> lock(state_mutex_);
    initialized_ = true;
  }
  state_cv_.notify_all();
  if (!capabilities_ok) {
    InteropLog("unsupported; stopping thread");
    SetStatusUnsupported();
    if (context_ && dc_) wglMakeCurrent(nullptr, nullptr);
    if (context_) { wglDeleteContext(context_); context_ = nullptr; }
    if (dc_ && gl_window_) { ReleaseDC(gl_window_, dc_); dc_ = nullptr; }
    if (gl_window_) { DestroyWindow(gl_window_); gl_window_ = nullptr; }
    return;
  }
  interop_device_ = wgl_dx_open_device_(mailbox_->NativeDevice());
  InteropLog("wglDXOpenDeviceNV=" + std::to_string(reinterpret_cast<std::uintptr_t>(interop_device_)) +
             " error=" + std::to_string(interop_device_ ? 0u : GetLastErrorCode()));
  metrics_->RecordOpenGLDevice(interop_device_ != nullptr,
                               interop_device_ ? 0u : GetLastErrorCode());
  if (!interop_device_) {
    SetStatusFailed();
  } else {
    ready_.store(true);
    // Register each currently materialized host-owned mailbox slot up front.
    // The producer owns the remaining slot while it is being filled; it is
    // registered on the first subsequent acquire without waiting for GL.
    for (const auto& candidate : mailbox_->SnapshotMailboxForInterop()) {
      RegisterTexture(candidate.slot, candidate.texture.Get());
    }
    const auto interval = std::chrono::duration<double>(1.0 / target_hz_);
    auto next = std::chrono::steady_clock::now();
    std::uint64_t last_generation = 0;
    bool sampled = false;
    while (!stopping_.load()) {
      next += std::chrono::duration_cast<std::chrono::steady_clock::duration>(interval);
      MailboxInteropFrame frame;
      if (!mailbox_->AcquireLatestForInterop(frame)) {
        metrics_->RecordOpenGLDrop();
        std::this_thread::sleep_until(next);
        continue;
      }
      InteropLog("acquired slot=" + std::to_string(frame.slot) +
                 " generation=" + std::to_string(frame.generation));
      auto texture = textures_.find(frame.slot);
      if (texture == textures_.end() &&
          !RegisterTexture(frame.slot, frame.texture.Get())) {
        mailbox_->ReleaseForInterop(frame.slot);
        metrics_->RecordOpenGLDrop();
        SetStatusFailed();
        std::this_thread::sleep_until(next);
        continue;
      }
      auto& registered = textures_.at(frame.slot);
      HANDLE object = registered.interop_object;
      if (!wgl_dx_lock_objects_(interop_device_, 1, &object)) {
        metrics_->RecordOpenGLLock(false, GetLastErrorCode());
        mailbox_->ReleaseForInterop(frame.slot);
        metrics_->RecordOpenGLDrop();
        SetStatusFailed();
        std::this_thread::sleep_until(next);
        continue;
      }
      InteropLog("locked slot=" + std::to_string(frame.slot));
      metrics_->RecordOpenGLLock(true, 0);
      const bool is_new = frame.generation != last_generation;
      if (is_new) last_generation = frame.generation;
      const bool drawn = DrawFrame(registered.gl_name, false, flip_y_);
      bool sample_passed = true;
      if (alpha_proof_ && !sampled) {
        sample_passed = ReadAlphaSamples(registered.gl_name);
        sampled = true;
        metrics_->FinishOpenGLProof(true, sample_passed, flip_y_);
      }
      const bool unlocked = wgl_dx_unlock_objects_(interop_device_, 1, &object) != FALSE;
      InteropLog("unlocked slot=" + std::to_string(frame.slot) +
                 " result=" + std::to_string(unlocked));
      metrics_->RecordOpenGLUnlock(unlocked, unlocked ? 0u : GetLastErrorCode());
      mailbox_->ReleaseForInterop(frame.slot);
      const bool swapped = !interactive_ ? (glFlush(), true)
                                        : (!stopping_.load() && SwapBuffers(dc_) != FALSE);
      if (!drawn || !unlocked || !swapped) {
        SetStatusFailed();
      } else {
        metrics_->RecordOpenGLFrame(is_new);
      }
      std::this_thread::sleep_until(next);
    }
    if (alpha_proof_ && !metrics_) metrics_->FinishOpenGLProof(false, false, flip_y_);
    InteropLog("loop done; unregister begin");
    ready_.store(false);
    UnregisterTextures();
    InteropLog("unregister done; close device begin");
    if (wgl_dx_close_device_ && interop_device_) wgl_dx_close_device_(interop_device_);
    interop_device_ = nullptr;
    InteropLog("close device done");
  }
  InteropLog("context teardown begin");
  if (context_ && dc_) { wglMakeCurrent(nullptr, nullptr); InteropLog("wglMakeCurrent cleared"); }
  if (context_) { wglDeleteContext(context_); context_ = nullptr; InteropLog("wglDeleteContext done"); }
  if (dc_ && gl_window_) { ReleaseDC(gl_window_, dc_); dc_ = nullptr; InteropLog("ReleaseDC done"); }
  if (gl_window_) { DestroyWindow(gl_window_); gl_window_ = nullptr; InteropLog("DestroyWindow done"); }
  InteropLog("thread done");
}
