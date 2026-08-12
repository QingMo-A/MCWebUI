package dev.qingmo.mcwebui;

import dev.qingmo.mcwebui.backend.FrameMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.atomic.AtomicLong;

class FrameMetricsTest {
    @Test
    void startsWithoutSyntheticRate() {
        FrameMetrics metrics = new FrameMetrics();
        assertEquals(0.0, metrics.paintRateHz());
        metrics.recordPaint(10, 10);
        assertEquals(0.0, metrics.paintRateHz());
        metrics.recordGameSignal();
        metrics.recordExternalRequest();
        assertEquals(1L, metrics.gameSignals());
        assertEquals(1L, metrics.externalRequests());
    }

    @Test
    void countersAreIndependentOfPaintRate() {
        FrameMetrics metrics = new FrameMetrics();
        metrics.recordGameSignal();
        metrics.recordGameSignal();
        metrics.recordExternalRequest();
        assertEquals(2L, metrics.gameSignals());
        assertEquals(1L, metrics.externalRequests());
        assertEquals(0.0, metrics.paintRateHz());
    }

    @Test
    void reportsOnlyCompletedPaintWindows() {
        AtomicLong clock = new AtomicLong(1L);
        FrameMetrics metrics = new FrameMetrics(clock::get);
        metrics.recordPaint(10, 10);
        clock.set(250_000_001L);
        metrics.recordPaint(10, 10);
        clock.set(1_000_000_001L);
        metrics.recordPaint(10, 10);
        assertEquals(2.0, metrics.paintRateHz(), 0.0001);

        clock.set(2_000_000_001L);
        metrics.recordGameSignal();
        metrics.recordExternalRequest();
        clock.set(3_000_000_001L);
        metrics.recordGameSignal();
        metrics.recordExternalRequest();
        assertEquals(1.0, metrics.gameSignalRateHz(), 0.0001);
        assertEquals(1.0, metrics.externalRequestRateHz(), 0.0001);
    }
}
