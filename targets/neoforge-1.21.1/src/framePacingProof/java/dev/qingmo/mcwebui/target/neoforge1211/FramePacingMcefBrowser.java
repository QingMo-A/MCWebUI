package dev.qingmo.mcwebui.target.neoforge1211;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import dev.qingmo.mcwebui.backend.FrameMetrics;
import org.cef.browser.CefBrowser;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/** Proof source-set browser; compiled only with the matched patched MCEF jar. */
final class FramePacingMcefBrowser extends MCEFBrowser {
    private final FrameMetrics metrics;

    FramePacingMcefBrowser(String url, boolean transparent, FrameMetrics metrics) {
        super(MCEF.getClient(), url, transparent, true);
        this.metrics = metrics;
        setCloseAllowed();
        createImmediately();
    }

    boolean supportsExternalFrames() { return supportsExternalBeginFrame(); }

    boolean requestExternalFrame() {
        if (!supportsExternalFrames()) return false;
        sendExternalBeginFrame();
        return true;
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                        ByteBuffer buffer, int width, int height) {
        super.onPaint(browser, popup, dirtyRects, buffer, width, height);
        if (!popup) metrics.recordPaint(width, height);
    }
}
