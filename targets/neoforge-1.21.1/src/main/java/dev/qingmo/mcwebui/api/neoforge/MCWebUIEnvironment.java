package dev.qingmo.mcwebui.api.neoforge;

import java.util.Objects;

/** Immutable environment facts; does not expose CEF or loader implementation objects. */
public record MCWebUIEnvironment(
        String target,
        String loader,
        String minecraftVersion,
        int javaVersion,
        String operatingSystem,
        boolean windows,
        boolean mcefInstalled
) {
    public MCWebUIEnvironment {
        target = Objects.requireNonNull(target, "target");
        loader = Objects.requireNonNull(loader, "loader");
        minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
    }
}
