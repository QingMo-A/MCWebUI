package dev.qingmo.mcwebui.target.neoforge1211;

/**
 * Converts a host render clock into a bounded external BeginFrame clock.
 *
 * <p>The fractional accumulator matters when the host and browser rates are not
 * integer multiples (for example 180 Hz Minecraft and 144 Hz CEF). At most one
 * request is emitted for a host frame and a long stall never creates a catch-up
 * burst.</p>
 */
final class ExternalFramePacer {
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private final int targetHz;
    private long lastSignalNanos = Long.MIN_VALUE;
    private long scaledRemainder;

    ExternalFramePacer(int targetHz) {
        if (targetHz < 1) throw new IllegalArgumentException("targetHz must be positive");
        this.targetHz = targetHz;
    }

    int targetHz() { return targetHz; }

    boolean shouldRequest(long nowNanos) {
        if (lastSignalNanos == Long.MIN_VALUE) {
            lastSignalNanos = nowNanos;
            scaledRemainder = 0L;
            return true;
        }
        if (nowNanos == lastSignalNanos) return false;
        if (nowNanos < lastSignalNanos) {
            lastSignalNanos = nowNanos;
            scaledRemainder = 0L;
            return true;
        }
        long elapsed = Math.min(ONE_SECOND_NANOS, nowNanos - lastSignalNanos);
        lastSignalNanos = nowNanos;
        scaledRemainder += elapsed * (long) targetHz;
        if (scaledRemainder < ONE_SECOND_NANOS) return false;
        scaledRemainder %= ONE_SECOND_NANOS;
        return true;
    }

    void reset() {
        lastSignalNanos = Long.MIN_VALUE;
        scaledRemainder = 0L;
    }
}
