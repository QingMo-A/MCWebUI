package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Files;
import java.nio.file.Path;

/** Thin opaque-handle JNI surface for the opt-in Windows Direct CEF backend. */
public final class DirectCefRuntime implements AutoCloseable {
    public record BridgeQuery(long id, String request) { }
    static {
        String absolute = System.getProperty("mcwebui.directCef.native", "").trim();
        if (!absolute.isEmpty()) {
            Path nativePath = Path.of(absolute).toAbsolutePath().normalize();
            Path runtimeDirectory = nativePath.getParent();
            Path chromeElfPath = runtimeDirectory == null ? null : runtimeDirectory.resolve("chrome_elf.dll");
            Path cefPath = runtimeDirectory == null ? null : runtimeDirectory.resolve("libcef.dll");
            if (chromeElfPath == null || !Files.isRegularFile(chromeElfPath)) {
                throw new UnsatisfiedLinkError("Direct CEF dependency missing: " + chromeElfPath);
            }
            if (cefPath == null || !Files.isRegularFile(cefPath)) {
                throw new UnsatisfiedLinkError("Direct CEF dependency missing: " + cefPath);
            }
            // Windows does not automatically search an absolute JNI library's directory for
            // transitive dependencies. Load CEF's imported helper first, then pinned libcef,
            // so the JNI DLL resolves without relying on a user/global PATH mutation.
            System.load(chromeElfPath.toString());
            System.load(cefPath.toString());
            System.load(nativePath.toString());
        } else System.loadLibrary("mcwebui-direct-cef");
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
    public boolean refreshGlContext() { return handle != 0 && nRefreshGlContext(handle); }
    public boolean requestFrame() { return handle != 0 && nRequestFrame(handle); }
    public boolean setFocus(boolean focused) { return handle != 0 && nSetFocus(handle, focused); }
    public boolean setMouseButtons(int buttons) { return handle != 0 && nSetMouseButtons(handle, buttons); }
    public boolean mouseMove(int x, int y, int modifiers, boolean leave) { return handle != 0 && nMouseMove(handle,x,y,modifiers,leave); }
    public boolean mouseButton(int x, int y, int modifiers, int button, boolean up, int count) { return handle != 0 && nMouseButton(handle,x,y,modifiers,button,up,count); }
    public boolean mouseWheel(int x, int y, int modifiers, int dx, int dy) { return handle != 0 && nMouseWheel(handle,x,y,modifiers,dx,dy); }
    public boolean key(int message, long wparam, long lparam) { return handle != 0 && nKey(handle,message,wparam,lparam); }
    public boolean text(String value) { return handle != 0 && nText(handle,value); }
    public BridgeQuery pollBridgeQuery() {
        if (handle == 0) return null;
        String[] values = nPollBridgeQuery(handle);
        return values == null ? null : new BridgeQuery(Long.parseLong(values[0]), values[1]);
    }
    public boolean completeBridgeQuery(long id, String response) {
        return handle != 0 && nCompleteBridgeQuery(handle, id, response, 0, "");
    }
    public boolean failBridgeQuery(long id, int errorCode, String errorMessage) {
        return handle != 0 && nCompleteBridgeQuery(handle, id, "", errorCode, errorMessage);
    }
    public synchronized boolean deliverBridgeMessage(long navigationEpoch, String encodedMessage) {
        return handle != 0 && nDeliverBridgeMessage(handle, navigationEpoch, encodedMessage);
    }
    public long bridgeNavigationEpoch() { return handle == 0 ? 0 : nBridgeNavigationEpoch(handle); }
    public boolean beginRenderFrame() { return handle != 0 && nBeginRenderFrame(handle); }
    public void endRenderFrame() { if (handle != 0) nEndRenderFrame(handle); }
    public void markFrameDrawn() { if (handle != 0) nMarkFrameDrawn(handle); }
    public int textureId() { return handle == 0 ? 0 : nTextureId(handle); }
    public String diagnosticsJson() { return handle == 0 ? "{\"ready\":false}" : nDiagnostics(handle); }
    public String alphaMode() { return "PREMULTIPLIED"; }
    // The standalone OpenGL proof renders in bottom-left coordinates and flips
    // CEF's shared texture there. Minecraft's GUI projection is already
    // top-left-oriented, so applying the proof flip again turns the page
    // upside down in the F8 screen.
    public boolean yFlipped() { return false; }
    @Override public synchronized void close() { if (handle != 0) { nDestroy(handle); handle = 0; } }
    private static native long nCreate(String url, String cacheDir, String helperPath, long parentWindow, int width, int height, int targetHz);
    private static native void nDestroy(long handle);
    private static native boolean nResize(long handle, int width, int height);
    private static native boolean nSetVisible(long handle, boolean visible);
    private static native boolean nRefreshGlContext(long handle);
    private static native boolean nRequestFrame(long handle);
    private static native boolean nSetFocus(long handle, boolean focused);
    private static native boolean nSetMouseButtons(long handle, int buttons);
    private static native boolean nMouseMove(long handle, int x, int y, int modifiers, boolean leave);
    private static native boolean nMouseButton(long handle, int x, int y, int modifiers, int button, boolean up, int count);
    private static native boolean nMouseWheel(long handle, int x, int y, int modifiers, int dx, int dy);
    private static native boolean nKey(long handle, int message, long wparam, long lparam);
    private static native boolean nText(long handle, String value);
    private static native String[] nPollBridgeQuery(long handle);
    private static native boolean nCompleteBridgeQuery(long handle, long id, String response, int errorCode, String errorMessage);
    private static native boolean nDeliverBridgeMessage(long handle, long navigationEpoch, String encodedMessage);
    private static native long nBridgeNavigationEpoch(long handle);
    private static native boolean nBeginRenderFrame(long handle);
    private static native void nEndRenderFrame(long handle);
    private static native void nMarkFrameDrawn(long handle);
    private static native int nTextureId(long handle);
    private static native String nDiagnostics(long handle);
}
