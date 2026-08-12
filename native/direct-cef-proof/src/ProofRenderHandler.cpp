#include "ProofRenderHandler.h"

#include "include/wrapper/cef_helpers.h"

ProofRenderHandler::ProofRenderHandler(int width, int height, ProofMetrics* metrics)
    : width_(width), height_(height), metrics_(metrics) {}

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
  return SUCCEEDED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0,
      levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, &d3d_device_, &selected, &d3d_context_));
}

void ProofRenderHandler::OnAcceleratedPaint(CefRefPtr<CefBrowser>,
                                            PaintElementType type,
                                            const RectList& dirty_rects,
                                            void* shared_handle) {
  CEF_REQUIRE_UI_THREAD();
  if (type != PET_VIEW || !metrics_) return;
  metrics_->RecordAcceleratedPaint(dirty_rects.size(), shared_handle);
  if (!shared_handle || !EnsureD3DDevice()) {
    metrics_->RecordD3D(false, 0, 0, 0, 0, E_FAIL);
    return;
  }
  Microsoft::WRL::ComPtr<ID3D11Texture2D> texture;
  const HRESULT result = d3d_device_->OpenSharedResource(
      reinterpret_cast<HANDLE>(shared_handle), IID_PPV_ARGS(&texture));
  if (FAILED(result)) {
    metrics_->RecordD3D(false, 0, 0, 0, 0, result);
    return;
  }
  D3D11_TEXTURE2D_DESC desc{};
  texture->GetDesc(&desc);
  metrics_->RecordD3D(true, desc.Width, desc.Height,
                      static_cast<unsigned>(desc.Format), desc.SampleDesc.Count, result);
}

