#include "ProofSimulator.h"

#include <d3dcompiler.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>

#include "ProofMetrics.h"

namespace {
struct Constants {
  float phase;
  float width;
  float height;
  float pad;
};

constexpr char kVertexShader[] = R"HLSL(
struct VSOut { float4 position : SV_Position; float2 uv : TEXCOORD0; };
VSOut main(uint id : SV_VertexID) {
  float2 p[3] = { float2(-1,-1), float2(-1,3), float2(3,-1) };
  float2 uv[3] = { float2(0,1), float2(0,-1), float2(2,1) };
  VSOut o; o.position = float4(p[id],0,1); o.uv = uv[id]; return o;
}
)HLSL";

constexpr char kPixelShader[] = R"HLSL(
Texture2D sourceTexture : register(t0);
SamplerState sourceSampler : register(s0);
cbuffer Frame : register(b0) { float phase; float width; float height; float pad; };
float4 main(float4 position : SV_Position, float2 uv : TEXCOORD0) : SV_Target {
  float2 p = uv * float2(width, height);
  float3 bg = float3(0.03 + 0.03 * sin((p.x + phase) * 0.012),
                     0.05 + 0.04 * cos((p.y + phase) * 0.009), 0.11);
  float grid = (fmod(abs(p.x + phase), 64.0) < 2.0 || fmod(abs(p.y), 64.0) < 2.0) ? 0.12 : 0.0;
  float4 web = sourceTexture.Sample(sourceSampler, uv);
  return float4(bg + grid, 1.0) * (1.0 - web.a) + web;
}
)HLSL";
}

ProofSimulator::ProofSimulator(HWND window, int width, int height,
                               ProofMetrics* metrics, bool mailbox,
                               int target_hz, bool uncoupled)
    : window_(window), width_(width), height_(height), metrics_(metrics),
      mailbox_(mailbox), target_hz_(std::max(1, target_hz)),
      uncoupled_(uncoupled) {}

ProofSimulator::~ProofSimulator() { Stop(); }

bool ProofSimulator::Initialize() {
  if (!window_ || width_ <= 0 || height_ <= 0) return false;
  const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
  D3D_FEATURE_LEVEL selected{};
  UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
  if (FAILED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags,
      levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, &device_, &selected, &context_))) {
    return false;
  }
  device_.As(&device1_);
  device_.As(&multithread_);
  if (multithread_) multithread_->SetMultithreadProtected(TRUE);
  if (!device1_ || !CreateTargets() || !CreateShaders()) return false;
  if (mailbox_) slots_.resize(3);
  ready_ = true;
  return true;
}

bool ProofSimulator::CreateTargets() {
  Microsoft::WRL::ComPtr<IDXGIDevice> dxgi_device;
  Microsoft::WRL::ComPtr<IDXGIAdapter> adapter;
  Microsoft::WRL::ComPtr<IDXGIFactory2> factory;
  if (FAILED(device_.As(&dxgi_device)) || FAILED(dxgi_device->GetAdapter(&adapter)) ||
      FAILED(adapter->GetParent(IID_PPV_ARGS(&factory)))) return false;
  DXGI_SWAP_CHAIN_DESC1 desc{};
  desc.Width = static_cast<UINT>(width_);
  desc.Height = static_cast<UINT>(height_);
  desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
  desc.SampleDesc.Count = 1;
  desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
  desc.BufferCount = 2;
  desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
  if (FAILED(factory->CreateSwapChainForHwnd(device_.Get(), window_, &desc, nullptr, nullptr,
                                             &swap_chain_))) return false;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> back_buffer;
  if (FAILED(swap_chain_->GetBuffer(0, IID_PPV_ARGS(&back_buffer)))) return false;
  return SUCCEEDED(device_->CreateRenderTargetView(back_buffer.Get(), nullptr, &render_target_));
}

bool ProofSimulator::CreateShaders() {
  Microsoft::WRL::ComPtr<ID3DBlob> vs_blob;
  Microsoft::WRL::ComPtr<ID3DBlob> ps_blob;
  if (FAILED(D3DCompile(kVertexShader, sizeof(kVertexShader) - 1, "simulator_vs", nullptr,
                        nullptr, "main", "vs_5_0", 0, 0, &vs_blob, nullptr)) ||
      FAILED(D3DCompile(kPixelShader, sizeof(kPixelShader) - 1, "simulator_ps", nullptr,
                        nullptr, "main", "ps_5_0", 0, 0, &ps_blob, nullptr)) ||
      FAILED(device_->CreateVertexShader(vs_blob->GetBufferPointer(), vs_blob->GetBufferSize(),
                                          nullptr, &vertex_shader_)) ||
      FAILED(device_->CreatePixelShader(ps_blob->GetBufferPointer(), ps_blob->GetBufferSize(),
                                         nullptr, &pixel_shader_))) return false;
  D3D11_SAMPLER_DESC sampler_desc{};
  sampler_desc.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
  sampler_desc.AddressU = sampler_desc.AddressV = sampler_desc.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
  if (FAILED(device_->CreateSamplerState(&sampler_desc, &sampler_))) return false;
  D3D11_BUFFER_DESC constant_desc{};
  constant_desc.ByteWidth = sizeof(Constants);
  constant_desc.Usage = D3D11_USAGE_DYNAMIC;
  constant_desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
  constant_desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
  return SUCCEEDED(device_->CreateBuffer(&constant_desc, nullptr, &constants_));
}

bool ProofSimulator::EnsurePipeline() {
  return ready_ && context_ && render_target_ && vertex_shader_ && pixel_shader_;
}

bool ProofSimulator::EnsureMailboxTextures(ID3D11Texture2D* source) {
  if (!mailbox_ || slots_.empty() || !source) return false;
  D3D11_TEXTURE2D_DESC desc{};
  source->GetDesc(&desc);
  desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
  desc.Usage = D3D11_USAGE_DEFAULT;
  desc.CPUAccessFlags = 0;
  desc.MiscFlags = 0;
  for (MailboxSlot& slot : slots_) {
    if (slot.texture) continue;
    if (FAILED(device_->CreateTexture2D(&desc, nullptr, &slot.texture)) ||
        FAILED(device_->CreateShaderResourceView(slot.texture.Get(), nullptr, &slot.view))) {
      return false;
    }
  }
  return true;
}

bool ProofSimulator::PublishTexture(ID3D11Texture2D* source) {
  if (!mailbox_ || !EnsurePipeline() || !source) return false;
  std::unique_lock<std::mutex> lock(gpu_mutex_, std::try_to_lock);
  // The callback never waits for the consumer or for a present. If the
  // immediate context is busy with a draw, drop this frame immediately; a
  // later callback can publish the latest texture. The selected consumer
  // slot is protected separately. Present is intentionally outside this lock
  // and never gates producer copies.
  if (!lock.owns_lock()) return false;
  if (!EnsureMailboxTextures(source)) return false;
  MailboxSlot* target = nullptr;
  for (std::size_t index = 0; index < slots_.size(); ++index) {
    if (static_cast<int>(index) == latest_slot_ ||
        static_cast<int>(index) == consumer_slot_) {
      continue;
    }
    target = &slots_[index];
    break;
  }
  if (!target) return false;
  context_->CopyResource(target->texture.Get(), source);
  // The producer and consumer share one immediate context and this mutex.
  // CopyResource is therefore ordered before the consumer's subsequent draw;
  // no CPU readback, query wait, Flush, or Present call occurs in the CEF
  // callback.
  target->generation = ++next_generation_;
  latest_slot_ = static_cast<int>(target - slots_.data());
  latest_generation_ = target->generation;
  if (metrics_) metrics_->RecordGpuCopy();
  if (metrics_) metrics_->RecordGpuCopyCompleted();
  if (metrics_) metrics_->RecordPublished();
  return true;
}

bool ProofSimulator::PublishSharedHandle(HANDLE shared_handle,
                                         D3D11_TEXTURE2D_DESC* descriptor,
                                         HRESULT* open_result) {
  if (open_result) *open_result = E_INVALIDARG;
  if (!device1_ || !shared_handle) return false;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> source;
  std::unique_lock<std::mutex> lock(gpu_mutex_, std::try_to_lock);
  if (!lock.owns_lock()) return false;
  const HRESULT result = device1_->OpenSharedResource1(shared_handle,
                                                        IID_PPV_ARGS(&source));
  if (open_result) *open_result = result;
  if (FAILED(result)) return false;
  if (descriptor) source->GetDesc(descriptor);
  if (!EnsureMailboxTextures(source.Get())) return false;
  MailboxSlot* target = nullptr;
  for (std::size_t index = 0; index < slots_.size(); ++index) {
    if (static_cast<int>(index) == latest_slot_ ||
        static_cast<int>(index) == consumer_slot_) continue;
    target = &slots_[index];
    break;
  }
  if (!target) return false;
  context_->CopyResource(target->texture.Get(), source.Get());
  target->generation = ++next_generation_;
  latest_slot_ = static_cast<int>(target - slots_.data());
  latest_generation_ = target->generation;
  if (metrics_) metrics_->RecordGpuCopy();
  if (metrics_) metrics_->RecordGpuCopyCompleted();
  if (metrics_) metrics_->RecordPublished();
  return true;
}

bool ProofSimulator::DrawFrame(ID3D11ShaderResourceView* source) {
  if (!EnsurePipeline()) return false;
  D3D11_MAPPED_SUBRESOURCE mapped{};
  if (SUCCEEDED(context_->Map(constants_.Get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
    auto* values = static_cast<Constants*>(mapped.pData);
    values->phase = phase_;
    values->width = static_cast<float>(width_);
    values->height = static_cast<float>(height_);
    context_->Unmap(constants_.Get(), 0);
  }
  phase_ += 1.5f;
  const float clear[4] = {0.02f, 0.04f, 0.08f, 1.0f};
  context_->OMSetRenderTargets(1, render_target_.GetAddressOf(), nullptr);
  context_->ClearRenderTargetView(render_target_.Get(), clear);
  context_->VSSetShader(vertex_shader_.Get(), nullptr, 0);
  context_->PSSetShader(pixel_shader_.Get(), nullptr, 0);
  ID3D11ShaderResourceView* source_view = source;
  context_->PSSetShaderResources(0, 1, &source_view);
  context_->PSSetSamplers(0, 1, sampler_.GetAddressOf());
  context_->PSSetConstantBuffers(0, 1, constants_.GetAddressOf());
  context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
  context_->Draw(3, 0);
  // Unbind before a later producer CopyResource targets this slot. The
  // immediate context preserves command order across the draw/copy sequence.
  ID3D11ShaderResourceView* null_view = nullptr;
  context_->PSSetShaderResources(0, 1, &null_view);
  return true;
}

bool ProofSimulator::PresentFrame(bool has_generation, bool has_new_generation) {
  const HRESULT result = swap_chain_->Present(uncoupled_ ? 0 : 1, 0);
  if (SUCCEEDED(result)) {
    ++presented_frames_;
    if (metrics_) metrics_->RecordPresent(has_generation, has_new_generation);
    return true;
  }
  return false;
}

bool ProofSimulator::PresentSharedHandle(HANDLE shared_handle) {
  if (!EnsurePipeline() || mailbox_ || !device1_ || !shared_handle) return false;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> source;
  {
    std::lock_guard<std::mutex> lock(gpu_mutex_);
    if (FAILED(device1_->OpenSharedResource1(shared_handle, IID_PPV_ARGS(&source))))
      return false;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> view;
    if (FAILED(device_->CreateShaderResourceView(source.Get(), nullptr, &view)))
      return false;
    if (!DrawFrame(view.Get())) return false;
  }
  // Coupled mode intentionally retains callback-owned Present as the legacy
  // baseline. Mailbox consumers call this outside gpu_mutex_ below.
  return PresentFrame(true, true);
}

void ProofSimulator::StartConsumer() {
  if (!mailbox_ || consumer_thread_.joinable()) return;
  stopping_.store(false);
  consumer_thread_ = std::thread(&ProofSimulator::ConsumerLoop, this);
}

void ProofSimulator::ConsumerLoop() {
  const auto interval = std::chrono::duration<double>(1.0 / target_hz_);
  auto next = std::chrono::steady_clock::now();
  while (!stopping_.load()) {
    next += std::chrono::duration_cast<std::chrono::steady_clock::duration>(interval);
    ID3D11ShaderResourceView* view = nullptr;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> view_hold;
    bool has_generation = false;
    bool is_new = false;
    bool drawn = false;
    {
      std::lock_guard<std::mutex> lock(gpu_mutex_);
      if (metrics_) metrics_->RecordConsumerFrame();
      consumer_slot_ = latest_slot_;
      if (consumer_slot_ >= 0) {
        const int selected_slot = consumer_slot_;
        view_hold = slots_[selected_slot].view;
        view = view_hold.Get();
        is_new = slots_[selected_slot].generation != consumer_generation_;
        if (is_new) consumer_generation_ = slots_[selected_slot].generation;
      }
      has_generation = view != nullptr;
      drawn = DrawFrame(view);
    }
    if (drawn) {
      PresentFrame(has_generation, is_new);
    }
    {
      std::lock_guard<std::mutex> lock(gpu_mutex_);
      consumer_slot_ = -1;
    }
    std::this_thread::sleep_until(next);
  }
}

void ProofSimulator::Stop() {
  stopping_.store(true);
  if (consumer_thread_.joinable()) consumer_thread_.join();
  std::lock_guard<std::mutex> lock(gpu_mutex_);
  slots_.clear();
  latest_slot_ = -1;
  consumer_slot_ = -1;
  ready_ = false;
}

std::uint64_t ProofSimulator::PresentedFrames() const { return presented_frames_; }
