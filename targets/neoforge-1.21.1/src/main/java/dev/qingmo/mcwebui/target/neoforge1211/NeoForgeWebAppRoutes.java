package dev.qingmo.mcwebui.target.neoforge1211;

import dev.qingmo.mcwebui.api.WebAppDefinition;
import dev.qingmo.mcwebui.api.WebAppId;
import dev.qingmo.mcwebui.api.WebAppRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Internal, deterministic mapping between public app identities and trusted mcui origins. */
final class NeoForgeWebAppRoutes {
    private static final String SUFFIX = ".mcwebui";

    private NeoForgeWebAppRoutes() { }

    static String host(WebAppId id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(id.toString().getBytes(StandardCharsets.UTF_8));
            return "app-" + HexFormat.of().formatHex(digest) + SUFFIX;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String entryUrl(WebAppDefinition app) {
        return "mcui://" + host(app.id()) + "/" + app.entry();
    }

    static WebAppDefinition resolveHost(String host) {
        if (host == null) return null;
        for (WebAppDefinition definition : WebAppRegistry.process().snapshot().values()) {
            if (host(definition.id()).equalsIgnoreCase(host)) return definition;
        }
        return null;
    }
}
