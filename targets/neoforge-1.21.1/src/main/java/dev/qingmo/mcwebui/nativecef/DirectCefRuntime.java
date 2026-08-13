package dev.qingmo.mcwebui.nativecef;

/** Thin opaque-handle JNI surface for the opt-in Windows Direct CEF backend. */
public final class DirectCefRuntime implements AutoCloseable {
    static {
        String absolute = System.getProperty("mcwebui.directCef.native", "").trim();
        if (!absolute.isEmpty()) System.load(absolute);
        else System.loadLibrary("mcwebui-direct-cef");
    }
    private long handle;
    private DirectCefRuntime(long handle) { this.handle = handle; }
    public static DirectCefRuntime create(String url, String cacheDir, String helperPath,
                                          long parentWindow, int width, int height, int targetHz) {
        long handle = nCreate(url, cacheDir, helperPath, parentWindow, width, height, targetHz);
        if (handle == 0) throw new IllegalStateException("Direct CEF runtime initialization failed");
        return new DirectCefRuntime(handle);
    }
    public boolean resize(int width, int height) { return handle != 0 && nResize(handle, width, height); }
    public boolean setVisible(boolean visible) { return handle != 0 && nSetVisible(handle, visible); }
    public boolean requestFrame() { return handle != 0 && nRequestFrame(handle); }
    public boolean setFocus(boolean focused) { return handle != 0 && nSetFocus(handle, focused); }
    public boolean mouseMove(int x, int y, int modifiers, boolean leave) { return handle != 0 && nMouseMove(handle,x,y,modifiers,leave); }
    public boolean mouseButton(int x, int y, int modifiers, int button, boolean up, int count) { return handle != 0 && nMouseButton(handle,x,y,modifiers,button,up,count); }
    public boolean mouseWheel(int x, int y, int modifiers, int dx, int dy) { return handle != 0 && nMouseWheel(handle,x,y,modifiers,dx,dy); }
    public boolean key(int message, long wparam, long lparam) { return handle != 0 && nKey(handle,message,wparam,lparam); }
    public boolean text(String value) { return handle != 0 && nText(handle,value); }
    public boolean beginRenderFrame() { return handle != 0 && nBeginRenderFrame(handle); }
    public void endRenderFrame() { if (handle != 0) nEndRenderFrame(handle); }
    public int textureId() { return handle == 0 ? 0 : nTextureId(handle); }
    public String diagnosticsJson() { return handle == 0 ? "{\"ready\":false}" : nDiagnostics(handle); }
    public String alphaMode() { return "PREMULTIPLIED"; }
    public boolean yFlipped() { return true; }
    @Override public void close() { if (handle != 0) { nDestroy(handle); handle = 0; } }
    private static native long nCreate(String url, String cacheDir, String helperPath, long parentWindow, int width, int height, int targetHz);
    private static native void nDestroy(long handle);
    private static native boolean nResize(long handle, int width, int height);
    private static native boolean nSetVisible(long handle, boolean visible);
    private static native boolean nRequestFrame(long handle);
    private static native boolean nSetFocus(long handle, boolean focused);
    private static native boolean nMouseMove(long handle, int x, int y, int modifiers, boolean leave);
    private static native boolean nMouseButton(long handle, int x, int y, int modifiers, int button, boolean up, int count);
    private static native boolean nMouseWheel(long handle, int x, int y, int modifiers, int dx, int dy);
    private static native boolean nKey(long handle, int message, long wparam, long lparam);
    private static native boolean nText(long handle, String value);
    private static native boolean nBeginRenderFrame(long handle);
    private static native void nEndRenderFrame(long handle);
    private static native int nTextureId(long handle);
    private static native String nDiagnostics(long handle);
}
