package dev.qingmo.mcwebui.target.neoforge1211;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.WGL;
import org.lwjgl.opengl.WGLCapabilities;

/** Render-thread-only WGL probe. It does not load Direct CEF or create a browser. */
final class DirectCefHostCompatibilityProbe {
    private DirectCefHostCompatibilityProbe() { }

    static DirectCefGraphicsCompatibility.Result probe(DirectCefStaticCompatibility.Result staticResult) {
        if (!staticResult.eligible()) {
            return DirectCefGraphicsCompatibility.Result.notProbed("Static compatibility did not pass.");
        }
        try {
            if (WGL.wglGetCurrentContext() == 0L || WGL.wglGetCurrentDC() == 0L) {
                return DirectCefGraphicsCompatibility.Result.notProbed(
                        "No current WGL/OpenGL context is available yet.");
            }
            WGLCapabilities caps = GL.getCapabilitiesWGL();
            boolean entryPoints = caps.wglDXOpenDeviceNV != 0L
                    && caps.wglDXCloseDeviceNV != 0L
                    && caps.wglDXRegisterObjectNV != 0L
                    && caps.wglDXUnregisterObjectNV != 0L
                    && caps.wglDXObjectAccessNV != 0L
                    && caps.wglDXLockObjectsNV != 0L
                    && caps.wglDXUnlockObjectsNV != 0L;
            return DirectCefGraphicsCompatibility.evaluate(true,
                    caps.WGL_NV_DX_interop, caps.WGL_NV_DX_interop2, entryPoints,
                    GL11.glGetString(GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER),
                    GL11.glGetString(GL11.GL_VERSION), null);
        } catch (Throwable failure) {
            return DirectCefGraphicsCompatibility.evaluate(true, false, false, false,
                    "", "", "", failure);
        }
    }
}
