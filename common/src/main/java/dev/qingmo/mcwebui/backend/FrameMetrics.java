package dev.qingmo.mcwebui.backend;

import java.util.concurrent.atomic.AtomicLong;

/** Lightweight instrumentation for browser paint and texture upload baselines. */
public final class FrameMetrics {
    private final AtomicLong paintCallbacks = new AtomicLong();
    private final AtomicLong uploadedFrames = new AtomicLong();
    private final AtomicLong uploadedBytes = new AtomicLong();
    private volatile int width;
    private volatile int height;

    public void recordPaint(int width, int height) {
        this.width = width;
        this.height = height;
        paintCallbacks.incrementAndGet();
    }

    public void recordUpload(long bytes) {
        uploadedFrames.incrementAndGet();
        if (bytes > 0) uploadedBytes.addAndGet(bytes);
    }

    public long paintCallbacks() { return paintCallbacks.get(); }
    public long uploadedFrames() { return uploadedFrames.get(); }
    public long uploadedBytes() { return uploadedBytes.get(); }
    public int width() { return width; }
    public int height() { return height; }
}
