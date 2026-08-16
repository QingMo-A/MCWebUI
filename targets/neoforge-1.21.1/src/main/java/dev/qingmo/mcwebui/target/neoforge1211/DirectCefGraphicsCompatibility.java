package dev.qingmo.mcwebui.target.neoforge1211;

/** Pure capability evaluation shared by the render-thread host probe and tests. */
final class DirectCefGraphicsCompatibility {
    static final String REQUIRED_INTEROP = "WGL_NV_DX_interop + WGL_NV_DX_interop2";

    enum Status { NOT_PROBED, SUPPORTED, UNSUPPORTED, PROBE_FAILED }
    record Result(Status status, boolean interop, boolean interop2, boolean entryPoints,
                  String vendor, String renderer, String version, String reason) {
        static Result notProbed(String reason) {
            return new Result(Status.NOT_PROBED, false, false, false, "", "", "", reason);
        }
        boolean supported() { return status == Status.SUPPORTED; }
    }

    private DirectCefGraphicsCompatibility() { }

    static Result evaluate(boolean currentContext, boolean interop, boolean interop2,
                           boolean entryPoints, String vendor, String renderer, String version,
                           Throwable failure) {
        String safeVendor = safe(vendor);
        String safeRenderer = safe(renderer);
        String safeVersion = safe(version);
        if (failure != null) {
            return new Result(Status.PROBE_FAILED, interop, interop2, entryPoints,
                    safeVendor, safeRenderer, safeVersion,
                    "Direct CEF graphics capability probe failed: " + failure.getClass().getSimpleName());
        }
        if (!currentContext) return Result.notProbed("No current WGL/OpenGL context is available yet.");
        if (interop && interop2 && entryPoints) {
            return new Result(Status.SUPPORTED, true, true, true,
                    safeVendor, safeRenderer, safeVersion, "");
        }
        return new Result(Status.UNSUPPORTED, interop, interop2, entryPoints,
                safeVendor, safeRenderer, safeVersion,
                "Direct CEF is unsupported by the current D3D/OpenGL interop path. Required: "
                        + REQUIRED_INTEROP + " compatible entry points.");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
