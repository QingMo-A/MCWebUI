package dev.qingmo.mcwebui;

import dev.qingmo.mcwebui.bridge.BridgeCapability;
import dev.qingmo.mcwebui.resource.WebResourceLocation;
import dev.qingmo.mcwebui.security.WebOrigin;
import dev.qingmo.mcwebui.security.WebPermissionPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class ResourceSecurityTest {
    @Test
    void resourceLocationNormalizesAndRejectsTraversal() {
        assertEquals("mcui://playground/assets/app.js", WebResourceLocation.parse("mcui://playground/assets//app.js").uri());
        assertThrows(IllegalArgumentException.class, () -> WebResourceLocation.parse("mcui://playground/../secret"));
        assertThrows(IllegalArgumentException.class, () -> WebResourceLocation.parse("mcui://playground/%2e%2e/secret"));
        assertThrows(IllegalArgumentException.class, () -> WebResourceLocation.parse("http://playground/index.html"));
    }

    @Test
    void onlyMcuiOriginsReceiveCapabilities() {
        WebPermissionPolicy policy = new WebPermissionPolicy(EnumSet.of(BridgeCapability.RPC), false);
        assertTrue(policy.allows(WebOrigin.mcui("playground"), BridgeCapability.RPC));
        assertFalse(policy.allows(WebOrigin.parse("https://example.com"), BridgeCapability.RPC));
        assertFalse(policy.allows(WebOrigin.parse("file://localhost"), BridgeCapability.RPC));
        assertFalse(policy.allowsExternalNetwork(WebOrigin.mcui("playground")));
    }
}
