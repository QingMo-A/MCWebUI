#include "ProofSimulator.h"

#include <d3dcompiler.h>

#include <algorithm>
#include <array>
#include <cmath>

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

ProofSimulator::ProofSimulator(HWND window, int width, int height)
    : window_(window), width_(width), height_(height) {}

ProofSimulator::~ProofSimulator() = default;

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
  if (!device1_ || !CreateTargets() || !CreateShaders()) return false;
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

bool ProofSimulator::PresentSharedHandle(HANDLE shared_handle) {
  if (!EnsurePipeline() || !device1_ || !shared_handle) return false;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> source;
  if (FAILED(device1_->OpenSharedResource1(shared_handle, IID_PPV_ARGS(&source)))) return false;
  Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> source_view;
  if (FAILED(device_->CreateShaderResourceView(source.Get(), nullptr, &source_view))) return false;
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
  context_->PSSetShaderResources(0, 1, source_view.GetAddressOf());
  context_->PSSetSamplers(0, 1, sampler_.GetAddressOf());
  context_->PSSetConstantBuffers(0, 1, constants_.GetAddressOf());
  context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
  context_->Draw(3, 0);
  if (FAILED(swap_chain_->Present(1, 0))) return false;
  ++presented_frames_;
  return true;
}

void ProofSimulator::PresentBackground() {
  if (!EnsurePipeline()) return;
  const float clear[4] = {0.02f + 0.02f * std::sin(phase_ * 0.03f), 0.04f, 0.10f, 1.0f};
  context_->OMSetRenderTargets(1, render_target_.GetAddressOf(), nullptr);
  context_->ClearRenderTargetView(render_target_.Get(), clear);
  if (SUCCEEDED(swap_chain_->Present(1, 0))) ++presented_frames_;
  phase_ += 1.0f;
}

std::uint64_t ProofSimulator::PresentedFrames() const { return presented_frames_; }
