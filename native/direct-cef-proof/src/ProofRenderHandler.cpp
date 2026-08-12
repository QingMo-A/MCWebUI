#include "ProofRenderHandler.h"

#include "include/wrapper/cef_helpers.h"

ProofRenderHandler::ProofRenderHandler(int width, int height, ProofMetrics* metrics,
                                       ProofSimulator* simulator)
    : width_(width), height_(height), metrics_(metrics), simulator_(simulator) {}

void ProofRenderHandler::GetViewRect(CefRefPtr<CefBrowser>, CefRect& rect) {
  CEF_REQUIRE_UI_THREAD();
  rect = CefRect(0, 0, width_, height_);
}

void ProofRenderHandler::OnPaint(CefRefPtr<CefBrowser>, PaintElementType type,
                                 const RectList& dirty_rects, const void* buffer,
                                 int width, int height) {
  CEF_REQUIRE_UI_THREAD();
  if (type == PET_VIEW && metrics_ && buffer)
    metrics_->RecordPaint(width, height, dirty_rects.size());
}

bool ProofRenderHandler::EnsureD3DDevice() {
  if (d3d_device_) return true;
  const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
  D3D_FEATURE_LEVEL selected{};
  const HRESULT result = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0,
      levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, &d3d_device_, &selected, &d3d_context_);
  if (FAILED(result)) return false;
  d3d_device_.As(&d3d_device1_);
  return true;
}

void ProofRenderHandler::OnAcceleratedPaint(CefRefPtr<CefBrowser>,
                                            PaintElementType type,
                                            const RectList& dirty_rects,
#if defined(CEF_VERSION_MAJOR) && CEF_VERSION_MAJOR >= 120
                                            const CefAcceleratedPaintInfo& info) {
#else
                                            void* shared_handle) {
#endif
  CEF_REQUIRE_UI_THREAD();
  if (type != PET_VIEW || !metrics_) return;
#if defined(CEF_VERSION_MAJOR) && CEF_VERSION_MAJOR >= 120
  void* shared_handle = reinterpret_cast<void*>(info.shared_texture_handle);
  const unsigned info_format = static_cast<unsigned>(info.format);
#else
  const unsigned info_format = 0;
#endif
  metrics_->RecordAcceleratedPaint(dirty_rects.size(), shared_handle);
  if (!shared_handle || !EnsureD3DDevice()) {
    metrics_->RecordD3D(false, 0, 0, info_format, 0, E_FAIL);
    return;
  }
  Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;
#if defined(CEF_VERSION_MAJOR) && CEF_VERSION_MAJOR >= 120
  HRESULT result = d3d_device1_ ? d3d_device1_->OpenSharedResource1(
      reinterpret_cast<HANDLE>(shared_handle), IID_PPV_ARGS(&texture)) : E_NOINTERFACE;
#else
  HRESULT result = d3d_device_->OpenSharedResource(
      reinterpret_cast<HANDLE>(shared_handle), IID_PPV_ARGS(&texture));
#endif
#if defined(CEF_VERSION_MAJOR) && CEF_VERSION_MAJOR >= 120
  // CEF 144 documents a handle without a keyed mutex. OpenSharedResource is
  // the D3D11 proof operation and is retained as the first compatibility path.
#endif
  if (FAILED(result)) {
    metrics_->RecordD3D(false, 0, 0, info_format, 0, result);
    return;
  }
  D3D11_TEXTURE2D_DESC desc{};
  texture->GetDesc(&desc);
  metrics_->RecordD3D(true, desc.Width, desc.Height,
                      info_format != 0 ? info_format : static_cast<unsigned>(desc.Format),
                      desc.SampleDesc.Count, result);
  if (simulator_ && simulator_->Ready() &&
      simulator_->PresentSharedHandle(reinterpret_cast<HANDLE>(shared_handle))) {
    metrics_->RecordPresented();
  }
}
