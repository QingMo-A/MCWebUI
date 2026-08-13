#include "ProofSimulator.h"

#include <d3dcompiler.h>
#include <commctrl.h>
#include <windowsx.h>

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

constexpr char kAlphaPixelShader[] = R"HLSL(
Texture2D sourceTexture : register(t0);
SamplerState sourceSampler : register(s0);
float4 main(float4 position : SV_Position, float2 uv : TEXCOORD0) : SV_Target {
  float4 web = sourceTexture.Sample(sourceSampler, uv);
  float3 background = float3(0.0, 0.0, 1.0);
  return float4(web.rgb + background * (1.0 - web.a), 1.0);
}
)HLSL";
}

ProofSimulator::ProofSimulator(HWND window, int width, int height,
                               ProofMetrics* metrics, bool mailbox,
                               int target_hz, bool uncoupled, bool alpha_proof)
    : window_(window), width_(width), height_(height), metrics_(metrics),
      mailbox_(mailbox), target_hz_(std::max(1, target_hz)),
      uncoupled_(uncoupled), alpha_proof_(alpha_proof) {}

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
  InstallInputSubclass();
  return true;
}

void ProofSimulator::SetInputSink(InputSink sink) {
  std::lock_guard<std::mutex> lock(input_mutex_);
  input_sink_ = std::move(sink);
}

void ProofSimulator::SetAlphaSamplePoints(const std::map<std::string, POINT>& points) {
  std::lock_guard<std::mutex> lock(gpu_mutex_);
  alpha_sample_points_ = points;
}

void ProofSimulator::DispatchInput(const InputEvent& event) {
  InputSink sink;
  {
    std::lock_guard<std::mutex> lock(input_mutex_);
    sink = input_sink_;
  }
  if (sink) sink(event);
}

LRESULT CALLBACK ProofSimulator::InputSubclassProc(HWND window, UINT message,
                                                    WPARAM wparam, LPARAM lparam,
                                                    UINT_PTR subclass_id,
                                                    DWORD_PTR ref_data) {
  auto* simulator = reinterpret_cast<ProofSimulator*>(ref_data);
  if (!simulator) return DefSubclassProc(window, message, wparam, lparam);
  InputEvent event;
  bool handled = true;
  event.message = message;
  event.wparam = static_cast<std::uintptr_t>(wparam);
  event.lparam = static_cast<std::intptr_t>(lparam);
  event.x = GET_X_LPARAM(lparam);
  event.y = GET_Y_LPARAM(lparam);
  switch (message) {
    case WM_SETFOCUS:
      event.kind = InputEventKind::Focus; event.focused = true; break;
    case WM_KILLFOCUS:
      event.kind = InputEventKind::Focus; event.focused = false; break;
    case WM_MOUSEMOVE:
      event.kind = InputEventKind::MouseMove; break;
    case WM_LBUTTONDOWN: case WM_LBUTTONUP:
      event.kind = InputEventKind::MouseButton; event.button = 0;
      event.pressed = message == WM_LBUTTONDOWN;
      if (event.pressed) { SetFocus(window); SetCapture(window); }
      else if (GetCapture() == window) ReleaseCapture();
      break;
    case WM_RBUTTONDOWN: case WM_RBUTTONUP:
      event.kind = InputEventKind::MouseButton; event.button = 1;
      event.pressed = message == WM_RBUTTONDOWN; break;
    case WM_MBUTTONDOWN: case WM_MBUTTONUP:
      event.kind = InputEventKind::MouseButton; event.button = 2;
      event.pressed = message == WM_MBUTTONDOWN; break;
    case WM_MOUSEWHEEL:
      { POINT point{event.x, event.y}; ScreenToClient(window, &point);
        event.x = point.x; event.y = point.y; event.kind = InputEventKind::MouseWheel;
        event.delta_y = GET_WHEEL_DELTA_WPARAM(wparam); break; }
    case WM_MOUSEHWHEEL:
      { POINT point{event.x, event.y}; ScreenToClient(window, &point);
        event.x = point.x; event.y = point.y; event.kind = InputEventKind::MouseWheel;
        event.delta_x = GET_WHEEL_DELTA_WPARAM(wparam); break; }
    case WM_MOUSELEAVE:
      event.kind = InputEventKind::MouseMove; event.leave = true; break;
    case WM_CAPTURECHANGED: case WM_CANCELMODE:
      event.kind = InputEventKind::CaptureLost; break;
    case WM_KEYDOWN: case WM_KEYUP: case WM_SYSKEYDOWN: case WM_SYSKEYUP: case WM_CHAR: case WM_SYSCHAR:
      event.kind = InputEventKind::Key; break;
    case WM_CLOSE:
      event.kind = InputEventKind::Close; break;
    default:
      handled = false; break;
  }
  if (handled) {
    if (simulator->metrics_)
      simulator->metrics_->RecordNativeWindowMessage(std::to_string(message));
    simulator->DispatchInput(event);
  }
  if (message == WM_NCDESTROY) {
    RemoveWindowSubclass(window, &ProofSimulator::InputSubclassProc, subclass_id);
    simulator->input_subclass_installed_ = false;
  }
  return DefSubclassProc(window, message, wparam, lparam);
}

bool ProofSimulator::InstallInputSubclass() {
  if (!window_ || input_subclass_installed_) return true;
  input_subclass_installed_ = SetWindowSubclass(window_, &ProofSimulator::InputSubclassProc,
                                                0x4D435749u,
                                                reinterpret_cast<DWORD_PTR>(this)) != FALSE;
  return input_subclass_installed_;
}

void ProofSimulator::RemoveInputSubclass() {
  if (!window_ || !input_subclass_installed_) return;
  RemoveWindowSubclass(window_, &ProofSimulator::InputSubclassProc, 0x4D435749u);
  input_subclass_installed_ = false;
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

bool ProofSimulator::RunAlphaAcceptance(ID3D11Texture2D*) {
  if (!alpha_proof_ || alpha_checked_ || !EnsurePipeline()) return true;
  alpha_checked_ = true;
  constexpr UINT kWidth = 5;
  constexpr UINT kHeight = 1;
  const std::array<BYTE, kWidth * 4> source_pixels = {
      0, 0, 0, 0, 0, 0, 64, 64, 0, 0, 128, 128,
      0, 0, 191, 191, 0, 0, 255, 255};
  D3D11_TEXTURE2D_DESC source_desc{};
  source_desc.Width = kWidth;
  source_desc.Height = kHeight;
  source_desc.MipLevels = 1;
  source_desc.ArraySize = 1;
  source_desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
  source_desc.SampleDesc.Count = 1;
  source_desc.Usage = D3D11_USAGE_DEFAULT;
  source_desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
  D3D11_SUBRESOURCE_DATA source_data{};
  source_data.pSysMem = source_pixels.data();
  source_data.SysMemPitch = kWidth * 4;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> source;
  Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> source_view;
  if (FAILED(device_->CreateTexture2D(&source_desc, &source_data, &source)) ||
      FAILED(device_->CreateShaderResourceView(source.Get(), nullptr, &source_view))) return false;
  D3D11_TEXTURE2D_DESC target_desc = source_desc;
  target_desc.BindFlags = D3D11_BIND_RENDER_TARGET;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> target;
  Microsoft::WRL::ComPtr<ID3D11RenderTargetView> target_view;
  if (FAILED(device_->CreateTexture2D(&target_desc, nullptr, &target)) ||
      FAILED(device_->CreateRenderTargetView(target.Get(), nullptr, &target_view))) return false;
  D3D11_TEXTURE2D_DESC staging_desc = target_desc;
  staging_desc.BindFlags = 0;
  staging_desc.Usage = D3D11_USAGE_STAGING;
  staging_desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> staging;
  if (FAILED(device_->CreateTexture2D(&staging_desc, nullptr, &staging))) return false;
  Microsoft::WRL::ComPtr<ID3DBlob> ps_blob;
  if (FAILED(D3DCompile(kAlphaPixelShader, sizeof(kAlphaPixelShader) - 1,
                        "alpha_acceptance_ps", nullptr, nullptr, "main", "ps_5_0", 0, 0,
                        &ps_blob, nullptr))) return false;
  Microsoft::WRL::ComPtr<ID3D11PixelShader> alpha_shader;
  if (FAILED(device_->CreatePixelShader(ps_blob->GetBufferPointer(), ps_blob->GetBufferSize(),
                                         nullptr, &alpha_shader))) return false;
  D3D11_VIEWPORT viewport{0, 0, static_cast<float>(kWidth), static_cast<float>(kHeight), 0, 1};
  context_->RSSetViewports(1, &viewport);
  context_->OMSetRenderTargets(1, target_view.GetAddressOf(), nullptr);
  context_->VSSetShader(vertex_shader_.Get(), nullptr, 0);
  context_->PSSetShader(alpha_shader.Get(), nullptr, 0);
  ID3D11ShaderResourceView* view = source_view.Get();
  context_->PSSetShaderResources(0, 1, &view);
  context_->PSSetSamplers(0, 1, sampler_.GetAddressOf());
  context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
  context_->Draw(3, 0);
  ID3D11ShaderResourceView* null_view = nullptr;
  context_->PSSetShaderResources(0, 1, &null_view);
  context_->CopyResource(staging.Get(), target.Get());
  context_->Flush();
  D3D11_MAPPED_SUBRESOURCE mapped{};
  if (FAILED(context_->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
  constexpr unsigned tolerance = 3;
  bool all_passed = true;
  for (UINT index = 0; index < kWidth; ++index) {
    const auto* pixel = static_cast<const BYTE*>(mapped.pData) + index * 4;
    const unsigned alpha = source_pixels[index * 4 + 3];
    const unsigned expected_red = alpha;
    const unsigned expected_blue = 255u - alpha;
    const bool passed = std::abs(static_cast<int>(pixel[2]) - static_cast<int>(expected_red)) <= tolerance &&
        std::abs(static_cast<int>(pixel[0]) - static_cast<int>(expected_blue)) <= tolerance &&
        pixel[1] <= tolerance;
    all_passed = all_passed && passed;
    if (metrics_) metrics_->RecordAlphaAcceptance(passed, "sRGB_UNORM_shader", tolerance,
                                                   index, 0, pixel[0], pixel[1], pixel[2], pixel[3],
                                                   expected_blue, 0, expected_red, 255);
  }
  context_->Unmap(staging.Get(), 0);
  return all_passed;
}

bool ProofSimulator::RunRealCefAlphaAcceptance(ID3D11Texture2D* source) {
  if (!alpha_proof_ || real_alpha_checked_ || !source || !EnsurePipeline() ||
      alpha_sample_points_.size() < 5) return true;
  real_alpha_checked_ = true;
  if (!metrics_) return false;
  metrics_->BeginRealCefAlphaAcceptance("premultiplied");
  D3D11_TEXTURE2D_DESC source_desc{};
  source->GetDesc(&source_desc);
  D3D11_TEXTURE2D_DESC capture_desc = source_desc;
  capture_desc.Width = 1;
  capture_desc.Height = 1;
  capture_desc.BindFlags = 0;
  capture_desc.Usage = D3D11_USAGE_STAGING;
  capture_desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
  capture_desc.MiscFlags = 0;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> staging;
  if (FAILED(device_->CreateTexture2D(&capture_desc, nullptr, &staging))) return false;
  const std::array<const char*, 5> names = {"world-reveal", "alpha25", "alpha50", "alpha75", "alpha100"};
  const std::array<unsigned, 5> expected_alpha = {0, 64, 128, 191, 255};
  bool all_passed = true;
  for (std::size_t index = 0; index < names.size(); ++index) {
    const auto point_it = alpha_sample_points_.find(names[index]);
    if (point_it == alpha_sample_points_.end()) { all_passed = false; continue; }
    const POINT point = point_it->second;
    const UINT x = static_cast<UINT>(std::max<LONG>(0, std::min<LONG>(point.x, static_cast<LONG>(source_desc.Width - 1))));
    const UINT y = static_cast<UINT>(std::max<LONG>(0, std::min<LONG>(point.y, static_cast<LONG>(source_desc.Height - 1))));
    D3D11_BOX box{x, y, 0, x + 1, y + 1, 1};
    context_->CopySubresourceRegion(staging.Get(), 0, 0, 0, 0, source, 0, &box);
    context_->Flush();
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(context_->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) { all_passed = false; continue; }
    const auto* pixel = static_cast<const BYTE*>(mapped.pData);
    const unsigned actual_b = pixel[0], actual_g = pixel[1], actual_r = pixel[2], actual_a = pixel[3];
    // The acceptance page uses a fixed red source. Chromium's BGRA OSR output
    // is premultiplied, so RGB should track alpha for these deterministic swatches.
    const unsigned source_r = 214;
    const unsigned source_g = 74;
    const unsigned source_b = 88;
    const unsigned expected_r = (source_r * expected_alpha[index] + 127) / 255;
    const unsigned expected_g = (source_g * expected_alpha[index] + 127) / 255;
    const unsigned expected_b = (source_b * expected_alpha[index] + 127) / 255;
    const bool passed = std::abs(static_cast<int>(actual_a) - static_cast<int>(expected_alpha[index])) <= 12 &&
        std::abs(static_cast<int>(actual_b) - static_cast<int>(expected_b)) <= 16 &&
        std::abs(static_cast<int>(actual_g) - static_cast<int>(expected_g)) <= 16 &&
        std::abs(static_cast<int>(actual_r) - static_cast<int>(expected_r)) <= 16;
    metrics_->RecordRealCefRawSample(names[index], point.x, point.y, actual_b, actual_g, actual_r, actual_a,
                                     expected_b, expected_g, expected_r, expected_alpha[index], passed);
    all_passed = all_passed && passed;
    context_->Unmap(staging.Get(), 0);
  }
  metrics_->FinishRealCefAlphaAcceptance(all_passed);
  return all_passed;
}

bool ProofSimulator::RunRealCefCompositionAcceptance(ID3D11ShaderResourceView* source_view,
                                                      const D3D11_TEXTURE2D_DESC& source_desc) {
  if (!alpha_proof_ || !real_alpha_checked_ || !source_view || !EnsurePipeline()) return true;
  constexpr UINT kSamples = 5;
  struct UvConstants { float uv[kSamples][4]; } constants{};
  const std::array<const char*, kSamples> names = {"world-reveal", "alpha25", "alpha50", "alpha75", "alpha100"};
  const std::array<unsigned, kSamples> expected_alpha = {0, 64, 128, 191, 255};
  for (UINT index = 0; index < kSamples; ++index) {
    const POINT point = alpha_sample_points_[names[index]];
    constants.uv[index][0] = (static_cast<float>(point.x) + .5f) / static_cast<float>(source_desc.Width);
    constants.uv[index][1] = (static_cast<float>(point.y) + .5f) / static_cast<float>(source_desc.Height);
  }
  D3D11_BUFFER_DESC cb_desc{};
  cb_desc.ByteWidth = sizeof(constants);
  cb_desc.Usage = D3D11_USAGE_DEFAULT;
  cb_desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
  D3D11_SUBRESOURCE_DATA cb_data{&constants, 0, 0};
  Microsoft::WRL::ComPtr<ID3D11Buffer> cb;
  if (FAILED(device_->CreateBuffer(&cb_desc, &cb_data, &cb))) return false;
  constexpr char kAcceptanceShader[] = R"HLSL(
Texture2D sourceTexture : register(t0); SamplerState sourceSampler : register(s0);
cbuffer Samples : register(b0) { float4 uv[5]; };
float4 main(float4 position : SV_Position) : SV_Target {
  uint index = min((uint)position.x, 4u); float4 web = sourceTexture.SampleLevel(sourceSampler, uv[index].xy, 0);
  return float4(web.rgb + float3(0,0,1) * (1.0 - web.a), 1.0);
})HLSL";
  Microsoft::WRL::ComPtr<ID3DBlob> blob;
  if (FAILED(D3DCompile(kAcceptanceShader, sizeof(kAcceptanceShader) - 1, "real_alpha_composition_ps",
                        nullptr, nullptr, "main", "ps_5_0", 0, 0, &blob, nullptr))) return false;
  Microsoft::WRL::ComPtr<ID3D11PixelShader> shader;
  if (FAILED(device_->CreatePixelShader(blob->GetBufferPointer(), blob->GetBufferSize(), nullptr, &shader))) return false;
  D3D11_TEXTURE2D_DESC target_desc{};
  target_desc.Width = kSamples; target_desc.Height = 1; target_desc.MipLevels = 1;
  target_desc.ArraySize = 1; target_desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
  target_desc.SampleDesc.Count = 1; target_desc.Usage = D3D11_USAGE_DEFAULT;
  target_desc.BindFlags = D3D11_BIND_RENDER_TARGET;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> target;
  Microsoft::WRL::ComPtr<ID3D11RenderTargetView> target_view;
  if (FAILED(device_->CreateTexture2D(&target_desc, nullptr, &target)) ||
      FAILED(device_->CreateRenderTargetView(target.Get(), nullptr, &target_view))) return false;
  D3D11_TEXTURE2D_DESC staging_desc = target_desc;
  staging_desc.BindFlags = 0; staging_desc.Usage = D3D11_USAGE_STAGING; staging_desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
  Microsoft::WRL::ComPtr<ID3D11Texture2D> staging;
  if (FAILED(device_->CreateTexture2D(&staging_desc, nullptr, &staging))) return false;
  D3D11_VIEWPORT viewport{0, 0, static_cast<float>(kSamples), 1, 0, 1};
  context_->RSSetViewports(1, &viewport); context_->OMSetRenderTargets(1, target_view.GetAddressOf(), nullptr);
  context_->VSSetShader(vertex_shader_.Get(), nullptr, 0); context_->PSSetShader(shader.Get(), nullptr, 0);
  context_->PSSetShaderResources(0, 1, &source_view); context_->PSSetSamplers(0, 1, sampler_.GetAddressOf());
  context_->PSSetConstantBuffers(0, 1, cb.GetAddressOf()); context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
  context_->Draw(3, 0); ID3D11ShaderResourceView* null_view = nullptr; context_->PSSetShaderResources(0, 1, &null_view);
  context_->CopyResource(staging.Get(), target.Get()); context_->Flush();
  D3D11_MAPPED_SUBRESOURCE mapped{};
  if (FAILED(context_->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
  bool all_passed = true;
  for (UINT index = 0; index < kSamples; ++index) {
    const auto* pixel = static_cast<const BYTE*>(mapped.pData) + index * 4;
    const unsigned alpha = expected_alpha[index];
    const unsigned src_b = (88 * alpha + 127) / 255, src_g = (74 * alpha + 127) / 255, src_r = (214 * alpha + 127) / 255;
    const unsigned expected_b = src_b + (255 - alpha), expected_g = src_g, expected_r = src_r;
    const bool passed = std::abs(static_cast<int>(pixel[0]) - static_cast<int>(expected_b)) <= 18 &&
        std::abs(static_cast<int>(pixel[1]) - static_cast<int>(expected_g)) <= 18 &&
        std::abs(static_cast<int>(pixel[2]) - static_cast<int>(expected_r)) <= 18 && pixel[3] >= 240;
    const POINT point = alpha_sample_points_[names[index]];
    metrics_->RecordRealCefCompositionSample(names[index], point.x, point.y, pixel[0], pixel[1], pixel[2], pixel[3],
                                             expected_b, expected_g, expected_r, 255, passed);
    all_passed = all_passed && passed;
  }
  context_->Unmap(staging.Get(), 0);
  metrics_->FinishRealCefAlphaAcceptance(all_passed);
  return all_passed;
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
  RunAlphaAcceptance(source);
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
  RunAlphaAcceptance(source.Get());
  D3D11_TEXTURE2D_DESC source_desc{};
  source->GetDesc(&source_desc);
  MailboxSlot* target = nullptr;
  for (std::size_t index = 0; index < slots_.size(); ++index) {
    if (static_cast<int>(index) == latest_slot_ ||
        static_cast<int>(index) == consumer_slot_) continue;
    target = &slots_[index];
    break;
  }
  if (!target) return false;
  context_->CopyResource(target->texture.Get(), source.Get());
  RunRealCefAlphaAcceptance(source.Get());
  RunRealCefCompositionAcceptance(target->view.Get(), source_desc);
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
  // The proof-only 1x5 composition readback temporarily changes the immediate
  // context viewport. Restore the normal framebuffer dimensions before every
  // regular frame so that the low-frequency readback cannot shrink later
  // presents to the acceptance strip.
  D3D11_VIEWPORT viewport{0, 0, static_cast<float>(width_), static_cast<float>(height_), 0, 1};
  context_->RSSetViewports(1, &viewport);
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
  RemoveInputSubclass();
  {
    std::lock_guard<std::mutex> lock(input_mutex_);
    input_sink_ = nullptr;
  }
  std::lock_guard<std::mutex> lock(gpu_mutex_);
  slots_.clear();
  latest_slot_ = -1;
  consumer_slot_ = -1;
  ready_ = false;
}

std::uint64_t ProofSimulator::PresentedFrames() const { return presented_frames_; }
