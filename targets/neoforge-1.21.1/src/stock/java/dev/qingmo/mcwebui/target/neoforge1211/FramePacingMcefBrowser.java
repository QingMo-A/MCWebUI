package dev.qingmo.mcwebui.target.neoforge1211;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import dev.qingmo.mcwebui.backend.FrameMetrics;
import org.cef.browser.CefBrowser;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/** Stock source-set browser. It intentionally remains callback-driven. */
final class FramePacingMcefBrowser extends MCEFBrowser {
    private final FrameMetrics metrics;

    FramePacingMcefBrowser(String url, boolean transparent, FrameMetrics metrics) {
        super(MCEF.getClient(), url, transparent);
        this.metrics = metrics;
        setCloseAllowed();
        createImmediately();
    }

    boolean supportsExternalFrames() { return false; }
    boolean requestExternalFrame() { return false; }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                        ByteBuffer buffer, int width, int height) {
        super.onPaint(browser, popup, dirtyRects, buffer, width, height);
        if (!popup) metrics.recordPaint(width, height);
    }
}
