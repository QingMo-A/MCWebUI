#pragma once

#include <windows.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

#include <d3d11.h>
#include <d3d11_1.h>
#include <d3d11_4.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

class ProofMetrics;

// GPU-only presentation proof. Coupled mode is retained for the historical
// baseline. Mailbox mode copies CEF frames into host-owned slots and presents
// them from an independent consumer loop; no CPU readback is used.
class ProofSimulator final {
 public:
  ProofSimulator(HWND window, int width, int height, ProofMetrics* metrics,
                 bool mailbox, int target_hz, bool uncoupled);
  ~ProofSimulator();
  ProofSimulator(const ProofSimulator&) = delete;
  ProofSimulator& operator=(const ProofSimulator&) = delete;

  bool Initialize();
  bool PresentSharedHandle(HANDLE shared_handle);
  bool PublishTexture(ID3D11Texture2D* source);
  bool PublishSharedHandle(HANDLE shared_handle,
                           D3D11_TEXTURE2D_DESC* descriptor = nullptr,
                           HRESULT* open_result = nullptr);
  void StartConsumer();
  void Stop();
  std::uint64_t PresentedFrames() const;
  bool MailboxMode() const { return mailbox_; }
  bool Ready() const { return ready_; }

 private:
  bool EnsurePipeline();
  bool CreateTargets();
  bool CreateShaders();
  bool EnsureMailboxTextures(ID3D11Texture2D* source);
  void ConsumerLoop();
  bool DrawFrame(ID3D11ShaderResourceView* source);
  bool PresentFrame(bool has_generation, bool has_new_generation);

  struct MailboxSlot {
    Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> view;
    std::uint64_t generation = 0;
  };

  HWND window_ = nullptr;
  int width_ = 0;
  int height_ = 0;
  ProofMetrics* const metrics_ = nullptr;
  const bool mailbox_;
  const int target_hz_;
  const bool uncoupled_;
  bool ready_ = false;
  std::atomic<bool> stopping_{false};
  std::thread consumer_thread_;
  mutable std::mutex gpu_mutex_;
  std::vector<MailboxSlot> slots_;
  int latest_slot_ = -1;
  int consumer_slot_ = -1;
  std::uint64_t next_generation_ = 0;
  std::uint64_t latest_generation_ = 0;
  std::uint64_t consumer_generation_ = 0;
  std::uint64_t presented_frames_ = 0;
  float phase_ = 0.0f;

  Microsoft::WRL::ComPtr<ID3D11Device> device_;
  Microsoft::WRL::ComPtr<ID3D11Device1> device1_;
  Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
  Microsoft::WRL::ComPtr<ID3D11Multithread> multithread_;
  Microsoft::WRL::ComPtr<IDXGISwapChain1> swap_chain_;
  Microsoft::WRL::ComPtr<ID3D11RenderTargetView> render_target_;
  Microsoft::WRL::ComPtr<ID3D11VertexShader> vertex_shader_;
  Microsoft::WRL::ComPtr<ID3D11PixelShader> pixel_shader_;
  Microsoft::WRL::ComPtr<ID3D11SamplerState> sampler_;
  Microsoft::WRL::ComPtr<ID3D11Buffer> constants_;
};
