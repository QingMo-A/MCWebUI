package dev.qingmo.mcwebui.backend;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Lightweight instrumentation for browser paint and estimated full-frame work. Paint rate is a completed one-second window estimate. */
public final class FrameMetrics {
    private final AtomicLong paintCallbacks = new AtomicLong();
    private final AtomicLong estimatedPaintBytes = new AtomicLong();
    private final AtomicLong gameSignals = new AtomicLong();
    private final AtomicLong externalRequests = new AtomicLong();
    private final LongSupplier nanoTime;
    private volatile int width;
    private volatile int height;
    private final RateWindow paintRate = new RateWindow();
    private final RateWindow gameRate = new RateWindow();
    private final RateWindow externalRate = new RateWindow();

    public FrameMetrics() { this(System::nanoTime); }

    public FrameMetrics(LongSupplier nanoTime) {
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void recordPaint(int width, int height) {
        this.width = width;
        this.height = height;
        paintRate.record(nanoTime.getAsLong());
        paintCallbacks.incrementAndGet();
        if (width > 0 && height > 0) estimatedPaintBytes.addAndGet((long) width * height * 4L);
    }

    public long paintCallbacks() { return paintCallbacks.get(); }
    public long gameSignals() { return gameSignals.get(); }
    public long externalRequests() { return externalRequests.get(); }
    public void recordGameSignal() { gameSignals.incrementAndGet(); gameRate.record(nanoTime.getAsLong()); }
    public void recordExternalRequest() { externalRequests.incrementAndGet(); externalRate.record(nanoTime.getAsLong()); }
    /** Estimated bytes if each paint were represented by one full RGBA frame; not GPU upload telemetry. */
    public long estimatedPaintBytes() { return estimatedPaintBytes.get(); }
    public int width() { return width; }
    public int height() { return height; }
    /** Completed one-second callback-window estimate; not game or GPU FPS. */
    public double paintRateHz() { return paintRate.rateHz(); }
    public double gameSignalRateHz() { return gameRate.rateHz(); }
    public double externalRequestRateHz() { return externalRate.rateHz(); }

    private static final class RateWindow {
        private long startNanos;
        private long samples;
        private volatile double rateHz;

        synchronized void record(long now) {
            if (startNanos == 0L) startNanos = now;
            else if (now - startNanos >= 1_000_000_000L) {
                rateHz = samples * 1_000_000_000.0 / Math.max(1L, now - startNanos);
                startNanos = now;
                samples = 0L;
            }
            samples++;
        }

        double rateHz() { return rateHz; }
    }
}
