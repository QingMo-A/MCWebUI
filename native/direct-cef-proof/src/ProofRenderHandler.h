#pragma once

#include <d3d11.h>
#include <wrl/client.h>

#include "include/cef_render_handler.h"
#include "ProofMetrics.h"

class ProofRenderHandler final : public CefRenderHandler {
 public:
  ProofRenderHandler(int width, int height, ProofMetrics* metrics);
  void GetViewRect(CefRefPtr<CefBrowser> browser, CefRect& rect) override;
  void OnPaint(CefRefPtr<CefBrowser> browser, PaintElementType type,
               const RectList& dirty_rects, const void* buffer,
               int width, int height) override;
  void OnAcceleratedPaint(CefRefPtr<CefBrowser> browser, PaintElementType type,
                          const RectList& dirty_rects, void* shared_handle) override;

 private:
  bool EnsureD3DDevice();
  const int width_;
  const int height_;
  ProofMetrics* const metrics_;
  Microsoft::WRL::ComPtr<ID3D11Device> d3d_device_;
  Microsoft::WRL::ComPtr<ID3D11DeviceContext> d3d_context_;
  IMPLEMENT_REFCOUNTING(ProofRenderHandler);
};

