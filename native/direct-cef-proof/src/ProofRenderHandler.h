#pragma once

#include <d3d11.h>
#include <d3d11_1.h>
#include <wrl/client.h>

#include "include/cef_version.h"
#include "include/cef_render_handler.h"
#include "ProofMetrics.h"
#include "ProofSimulator.h"

class ProofRenderHandler final : public CefRenderHandler {
 public:
  ProofRenderHandler(int width, int height, ProofMetrics* metrics,
                     ProofSimulator* simulator = nullptr);
  void GetViewRect(CefRefPtr<CefBrowser> browser, CefRect& rect) override;
  void OnPaint(CefRefPtr<CefBrowser> browser, PaintElementType type,
               const RectList& dirty_rects, const void* buffer,
               int width, int height) override;
#if defined(CEF_VERSION_MAJOR) && CEF_VERSION_MAJOR >= 120
  void OnAcceleratedPaint(CefRefPtr<CefBrowser> browser, PaintElementType type,
                          const RectList& dirty_rects,
                          const CefAcceleratedPaintInfo& info) override;
#else
  void OnAcceleratedPaint(CefRefPtr<CefBrowser> browser, PaintElementType type,
                          const RectList& dirty_rects, void* shared_handle) override;
#endif

 private:
  bool EnsureD3DDevice();
  const int width_;
  const int height_;
  ProofMetrics* const metrics_;
  ProofSimulator* const simulator_;
  Microsoft::WRL::ComPtr<ID3D11Device> d3d_device_;
  Microsoft::WRL::ComPtr<ID3D11Device1> d3d_device1_;
  Microsoft::WRL::ComPtr<ID3D11DeviceContext> d3d_context_;
  IMPLEMENT_REFCOUNTING(ProofRenderHandler);
};
