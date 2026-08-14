package dev.qingmo.mcwebui.target.neoforge1211;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalFramePacerTest {
    @Test void convertsOneEightyHostSignalsToConfiguredRates() {
        assertEquals(60, requestsForOneSecond(60));
        assertEquals(120, requestsForOneSecond(120));
        assertEquals(144, requestsForOneSecond(144));
    }

    @Test void emitsAtMostOneRequestAfterALongStall() {
        ExternalFramePacer pacer = new ExternalFramePacer(144);
        assertTrue(pacer.shouldRequest(1L));
        assertTrue(pacer.shouldRequest(5_000_000_001L));
        assertFalse(pacer.shouldRequest(5_000_000_001L));
    }

    @Test void resetMakesTheNextVisibleFrameImmediate() {
        ExternalFramePacer pacer = new ExternalFramePacer(60);
        assertTrue(pacer.shouldRequest(100L));
        assertFalse(pacer.shouldRequest(101L));
        pacer.reset();
        assertTrue(pacer.shouldRequest(101L));
    }

    private static int requestsForOneSecond(int targetHz) {
        ExternalFramePacer pacer = new ExternalFramePacer(targetHz);
        int requests = 0;
        // 180 evenly spaced host frames over [0, 1s). Use rational timestamps to
        // avoid baking a rounded 5,555,556 ns period into the expected result.
        for (int frame = 0; frame < 180; frame++) {
            long now = frame * 1_000_000_000L / 180L;
            if (pacer.shouldRequest(now)) requests++;
        }
        return requests;
    }
}
