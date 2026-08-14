package dev.qingmo.mcwebui.api;

import java.util.Objects;

/**
 * Backend-neutral behavior options for a WebScreen.
 *
 * <p>The options describe screen semantics only.  Browser frame pacing,
 * texture formats, alpha blend state, and other backend details deliberately
 * do not appear here.</p>
 */
public final class WebScreenOptions {
    /** Alias for callers that keep the preview version next to screen options. */
    public static final int API_VERSION = MCWebUIApi.API_VERSION;

    /** Default semantics for a non-pausing, dismissible transparent overlay. */
    public static final WebScreenOptions DEFAULTS = new WebScreenOptions(
            false, true, true, WebViewportPolicy.GUI);

    private final boolean pauseGame;
    private final boolean closeOnEsc;
    private final boolean transparent;
    private final WebViewportPolicy viewportPolicy;

    public WebScreenOptions(boolean pauseGame, boolean closeOnEsc, boolean transparent,
                            WebViewportPolicy viewportPolicy) {
        this.pauseGame = pauseGame;
        this.closeOnEsc = closeOnEsc;
        this.transparent = transparent;
        this.viewportPolicy = Objects.requireNonNull(viewportPolicy, "viewportPolicy");
    }

    public WebScreenOptions(boolean pauseGame, boolean closeOnEsc, boolean transparent) {
        this(pauseGame, closeOnEsc, transparent, WebViewportPolicy.GUI);
    }

    public static WebScreenOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .pauseGame(pauseGame)
                .closeOnEsc(closeOnEsc)
                .transparent(transparent)
                .viewportPolicy(viewportPolicy);
    }

    public boolean pauseGame() {
        return pauseGame;
    }

    public boolean closeOnEsc() {
        return closeOnEsc;
    }

    public boolean transparent() {
        return transparent;
    }

    public WebViewportPolicy viewportPolicy() {
        return viewportPolicy;
    }

    /** Alias for integrations that call this setting simply the viewport. */
    public WebViewportPolicy viewport() {
        return viewportPolicy;
    }

    public WebViewportPolicy viewportMode() {
        return viewportPolicy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WebScreenOptions that)) return false;
        return pauseGame == that.pauseGame
                && closeOnEsc == that.closeOnEsc
                && transparent == that.transparent
                && viewportPolicy == that.viewportPolicy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pauseGame, closeOnEsc, transparent, viewportPolicy);
    }

    @Override
    public String toString() {
        return "WebScreenOptions[ pauseGame=" + pauseGame
                + ", closeOnEsc=" + closeOnEsc
                + ", transparent=" + transparent
                + ", viewportPolicy=" + viewportPolicy + " ]";
    }

    public static final class Builder {
        private boolean pauseGame = DEFAULTS.pauseGame;
        private boolean closeOnEsc = DEFAULTS.closeOnEsc;
        private boolean transparent = DEFAULTS.transparent;
        private WebViewportPolicy viewportPolicy = DEFAULTS.viewportPolicy;

        public Builder pauseGame(boolean value) {
            pauseGame = value;
            return this;
        }

        public Builder closeOnEsc(boolean value) {
            closeOnEsc = value;
            return this;
        }

        public Builder transparent(boolean value) {
            transparent = value;
            return this;
        }

        public Builder viewportPolicy(WebViewportPolicy value) {
            viewportPolicy = Objects.requireNonNull(value, "viewportPolicy");
            return this;
        }

        public Builder viewport(WebViewportPolicy value) {
            return viewportPolicy(value);
        }

        public WebScreenOptions build() {
            return new WebScreenOptions(pauseGame, closeOnEsc, transparent, viewportPolicy);
        }
    }
}
