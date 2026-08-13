#pragma once

#include <windows.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <d3d11.h>
#include <wrl/client.h>

class ProofMetrics;
class ProofSimulator;

// Standalone WGL_NV_DX_interop2 proof.  The OpenGL context and all GL calls
// are confined to one thread.  D3D mailbox ownership is acquired/released
// through ProofSimulator; there is no CPU texture upload or fallback path.
class ProofOpenGLInterop final {
 public:
  ProofOpenGLInterop(HWND owner, int width, int height, int target_hz,
                     ProofSimulator* mailbox, ProofMetrics* metrics,
                     bool interactive, bool alpha_proof);
  ~ProofOpenGLInterop();
  ProofOpenGLInterop(const ProofOpenGLInterop&) = delete;
  ProofOpenGLInterop& operator=(const ProofOpenGLInterop&) = delete;

  bool Initialize();
  void Stop();
  bool Ready() const { return ready_.load(); }

 private:
  void ThreadMain();
  bool CreateContext();
  bool LoadInteropFunctions();
  bool RegisterTexture(int slot, ID3D11Texture2D* texture);
  void UnregisterTextures();
  bool DrawFrame(unsigned texture_name, bool fixed_blue, bool flip_y);
  bool ReadAlphaSamples(unsigned texture_name);
  void SetStatusUnsupported();
  void SetStatusFailed();

  HWND owner_ = nullptr;
  HWND gl_window_ = nullptr;
  HDC dc_ = nullptr;
  HGLRC context_ = nullptr;
  const int width_;
  const int height_;
  const int target_hz_;
  ProofSimulator* const mailbox_;
  ProofMetrics* const metrics_;
  const bool interactive_;
  const bool alpha_proof_;
  std::atomic<bool> stopping_{false};
  std::atomic<bool> ready_{false};
  std::thread thread_;
  std::mutex state_mutex_;
  std::condition_variable state_cv_;
  bool initialized_ = false;

  using WglDXOpenDeviceNV = HANDLE(WINAPI*)(void*);
  using WglDXCloseDeviceNV = BOOL(WINAPI*)(HANDLE);
  using WglDXRegisterObjectNV = HANDLE(WINAPI*)(HANDLE, void*, unsigned int,
                                                 unsigned int, unsigned int);
  using WglDXUnregisterObjectNV = BOOL(WINAPI*)(HANDLE, HANDLE);
  using WglDXObjectAccessNV = BOOL(WINAPI*)(HANDLE, unsigned int);
  using WglDXLockObjectsNV = BOOL(WINAPI*)(HANDLE, int, HANDLE*);
  using WglDXUnlockObjectsNV = BOOL(WINAPI*)(HANDLE, int, HANDLE*);
  using WglDXSetResourceShareHandleNV = BOOL(WINAPI*)(void*, HANDLE);
  WglDXOpenDeviceNV wgl_dx_open_device_ = nullptr;
  WglDXCloseDeviceNV wgl_dx_close_device_ = nullptr;
  WglDXRegisterObjectNV wgl_dx_register_object_ = nullptr;
  WglDXUnregisterObjectNV wgl_dx_unregister_object_ = nullptr;
  WglDXObjectAccessNV wgl_dx_object_access_ = nullptr;
  WglDXLockObjectsNV wgl_dx_lock_objects_ = nullptr;
  WglDXUnlockObjectsNV wgl_dx_unlock_objects_ = nullptr;
  WglDXSetResourceShareHandleNV wgl_dx_set_resource_share_handle_ = nullptr;
  HANDLE interop_device_ = nullptr;
  bool extension_nv_ = false;
  bool extension_nv2_ = false;
  bool entries_complete_ = false;
  // CEF/D3D shared textures use top-left origin; imported GL textures need
  // inverted V coordinates when sampled by the OpenGL pipeline.
  bool flip_y_ = true;
  std::string vendor_;
  std::string renderer_;
  std::string version_;
  std::string extensions_;
  std::string adapter_;
  std::uint32_t luid_high_ = 0;
  std::uint32_t luid_low_ = 0;
  struct RegisteredTexture {
    unsigned gl_name = 0;
    HANDLE interop_object = nullptr;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;
  };
  std::map<int, RegisteredTexture> textures_;
};
