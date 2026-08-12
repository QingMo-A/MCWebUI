#pragma once

#include <windows.h>

#include <cstdint>
#include <mutex>

#include <d3d11.h>
#include <d3d11_1.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

// GPU-only presentation proof. It owns the swap chain and the latest copied
// CEF texture; no frame ever crosses through a CPU readback buffer.
class ProofSimulator final {
 public:
  ProofSimulator(HWND window, int width, int height);
  ~ProofSimulator();
  ProofSimulator(const ProofSimulator&) = delete;
  ProofSimulator& operator=(const ProofSimulator&) = delete;

  bool Initialize();
  bool PresentSharedHandle(HANDLE shared_handle);
  void PresentBackground();
  std::uint64_t PresentedFrames() const;
  bool Ready() const { return ready_; }

 private:
  bool EnsurePipeline();
  bool CreateTargets();
  bool CreateShaders();

  HWND window_ = nullptr;
  int width_ = 0;
  int height_ = 0;
  bool ready_ = false;
  std::uint64_t presented_frames_ = 0;
  float phase_ = 0.0f;
  Microsoft::WRL::ComPtr<ID3D11Device> device_;
  Microsoft::WRL::ComPtr<ID3D11Device1> device1_;
  Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
  Microsoft::WRL::ComPtr<IDXGISwapChain1> swap_chain_;
  Microsoft::WRL::ComPtr<ID3D11RenderTargetView> render_target_;
  Microsoft::WRL::ComPtr<ID3D11VertexShader> vertex_shader_;
  Microsoft::WRL::ComPtr<ID3D11PixelShader> pixel_shader_;
  Microsoft::WRL::ComPtr<ID3D11SamplerState> sampler_;
  Microsoft::WRL::ComPtr<ID3D11Buffer> constants_;
};
