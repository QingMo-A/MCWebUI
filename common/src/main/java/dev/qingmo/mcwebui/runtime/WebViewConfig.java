package dev.qingmo.mcwebui.runtime;

import dev.qingmo.mcwebui.security.WebOrigin;

import java.util.Objects;

public record WebViewConfig(WebOrigin origin, String initialPath, int width, int height,
                            FramePolicy framePolicy, boolean allowExternalNetwork) {
    public WebViewConfig {
        origin = Objects.requireNonNull(origin, "origin");
        if (!origin.isTrustedLocal()) throw new SecurityException("WebView origin must be mcui://...");
        initialPath = Objects.requireNonNull(initialPath, "initialPath");
        if (!initialPath.startsWith("/") || initialPath.contains("..") || initialPath.contains("\\")) {
            throw new IllegalArgumentException("initialPath must be a safe absolute path");
        }
        if (width < 1 || height < 1) throw new IllegalArgumentException("view dimensions must be positive");
        framePolicy = framePolicy == null ? FramePolicy.ADAPTIVE : framePolicy;
        if (allowExternalNetwork) throw new SecurityException("External network is disabled for Phase 1 bundled views");
    }

    public WebViewConfig(WebOrigin origin, String initialPath, int width, int height) {
        this(origin, initialPath, width, height, FramePolicy.ADAPTIVE, false);
    }
}
