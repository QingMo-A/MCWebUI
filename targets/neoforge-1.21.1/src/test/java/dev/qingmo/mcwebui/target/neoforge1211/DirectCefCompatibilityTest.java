package dev.qingmo.mcwebui.target.neoforge1211;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DirectCefCompatibilityTest {
    @Test void staticEligibilityNormalizesOnlyWindowsX8664() {
        assertTrue(DirectCefStaticCompatibility.evaluate("Windows 11", "amd64").eligible());
        assertTrue(DirectCefStaticCompatibility.evaluate("Windows 10", "x86_64").eligible());
        assertTrue(DirectCefStaticCompatibility.evaluate("Windows 10", "x86-64").eligible());

        assertEquals(DirectCefStaticCompatibility.Status.UNSUPPORTED_ARCHITECTURE,
                DirectCefStaticCompatibility.evaluate("Windows 11", "arm64").status());
        assertEquals(DirectCefStaticCompatibility.Status.UNSUPPORTED_ARCHITECTURE,
                DirectCefStaticCompatibility.evaluate("Windows 11", "x86").status());
        assertEquals(DirectCefStaticCompatibility.Status.UNSUPPORTED_PLATFORM,
                DirectCefStaticCompatibility.evaluate("Linux", "amd64").status());
        assertEquals(DirectCefStaticCompatibility.Status.UNSUPPORTED_PLATFORM,
                DirectCefStaticCompatibility.evaluate("Mac OS X", "aarch64").status());
    }

    @Test void graphicsRequiresBothExtensionsAndEveryInteropEntryPoint() {
        assertEquals(DirectCefGraphicsCompatibility.Status.SUPPORTED,
                graphics(true, true, true, null).status());
        assertEquals(DirectCefGraphicsCompatibility.Status.UNSUPPORTED,
                graphics(false, true, true, null).status());
        assertEquals(DirectCefGraphicsCompatibility.Status.UNSUPPORTED,
                graphics(true, false, true, null).status());
        assertEquals(DirectCefGraphicsCompatibility.Status.UNSUPPORTED,
                graphics(true, true, false, null).status());
    }

    @Test void graphicsDistinguishesPendingAndProbeFailure() {
        assertEquals(DirectCefGraphicsCompatibility.Status.NOT_PROBED,
                DirectCefGraphicsCompatibility.evaluate(false, false, false, false,
                        "", "", "", null).status());
        assertEquals(DirectCefGraphicsCompatibility.Status.PROBE_FAILED,
                graphics(false, false, false, new IllegalStateException("probe")).status());
    }

    private static DirectCefGraphicsCompatibility.Result graphics(
            boolean interop, boolean interop2, boolean entryPoints, Throwable failure) {
        return DirectCefGraphicsCompatibility.evaluate(true, interop, interop2, entryPoints,
                "Vendor", "Renderer", "4.6", failure);
    }
}
