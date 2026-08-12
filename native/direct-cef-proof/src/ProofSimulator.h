#pragma once

#include <windows.h>

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#include <d3d11.h>
#include <d3d11_1.h>
#include <d3d11_4.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

class ProofMetrics;

enum class InputEventKind : std::uint8_t {
  Focus,
  MouseMove,
  MouseButton,
  MouseWheel,
  Key,
  CaptureLost,
  Close,
};

struct InputEvent {
  InputEventKind kind = InputEventKind::MouseMove;
  int x = 0;
  int y = 0;
  std::uint32_t modifiers = 0;
  std::uint32_t message = 0;
  std::uintptr_t wparam = 0;
  std::intptr_t lparam = 0;
  int delta_x = 0;
  int delta_y = 0;
  int button = 0;
  bool pressed = false;
  bool leave = false;
  bool focused = false;
};

// GPU-only presentation proof. Coupled mode is retained for the historical
// baseline. Mailbox mode copies CEF frames into host-owned slots and presents
// them from an independent consumer loop; no CPU readback is used.
class ProofSimulator final {
 public:
  ProofSimulator(HWND window, int width, int height, ProofMetrics* metrics,
                 bool mailbox, int target_hz, bool uncoupled,
                 bool alpha_proof = false);
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
  using InputSink = std::function<void(const InputEvent&)>;
  void SetInputSink(InputSink sink);

 private:
  bool EnsurePipeline();
  bool CreateTargets();
  bool CreateShaders();
  bool EnsureMailboxTextures(ID3D11Texture2D* source);
  void ConsumerLoop();
  bool DrawFrame(ID3D11ShaderResourceView* source);
  bool PresentFrame(bool has_generation, bool has_new_generation);
  bool RunAlphaAcceptance(ID3D11Texture2D* source);
  bool InstallInputSubclass();
  void RemoveInputSubclass();
  void DispatchInput(const InputEvent& event);
  static LRESULT CALLBACK InputSubclassProc(HWND window, UINT message,
                                             WPARAM wparam, LPARAM lparam,
                                             UINT_PTR subclass_id,
                                             DWORD_PTR ref_data);

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
  const bool alpha_proof_;
  bool ready_ = false;
  bool alpha_checked_ = false;
  mutable std::mutex input_mutex_;
  InputSink input_sink_;
  bool input_subclass_installed_ = false;
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
