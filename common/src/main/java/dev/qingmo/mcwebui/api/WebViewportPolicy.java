package dev.qingmo.mcwebui.api;

/** Backend-neutral policy for choosing a WebScreen browser viewport. */
public enum WebViewportPolicy {
    /** Match the logical Minecraft GUI/screen dimensions. */
    GUI,
    /** Match the physical framebuffer-equivalent dimensions. */
    FRAMEBUFFER
}
