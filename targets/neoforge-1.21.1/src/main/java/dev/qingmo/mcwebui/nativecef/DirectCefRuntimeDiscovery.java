package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Path;
import java.util.Objects;

/** Resolves and validates the instance-scoped Direct CEF runtime. */
public final class DirectCefRuntimeDiscovery {
    public static final String INSTANCE_ROOT_PROPERTY = "mcwebui.directCef.instanceRoot";
    public static final String RUNTIME_OVERRIDE_PROPERTY = "mcwebui.directCef.runtimeDir";
    public static final String CACHE_OVERRIDE_PROPERTY = "mcwebui.directCef.cacheDir";

    private DirectCefRuntimeDiscovery() { }

    /** The canonical runtime location when no explicit override is configured. */
    public static Path standardDirectory(Path instanceRoot) {
        return standardDirectory(instanceRoot, DirectCefRuntimeRequirement.required());
    }

    public static Path standardDirectory(Path instanceRoot, DirectCefRuntimeRequirement requirement) {
        requirePath(instanceRoot, "instanceRoot");
        Objects.requireNonNull(requirement, "requirement");
        return instanceRoot.toAbsolutePath().normalize()
                .resolve("mcwebui").resolve("runtime").resolve("cef")
                .resolve(requirement.runtimeId()).resolve(requirement.platform() + "-" + requirement.arch());
    }

    /** The separate CEF disk cache location for a validated identity. */
    public static Path standardCacheDirectory(Path instanceRoot) {
        return standardCacheDirectory(instanceRoot, DirectCefRuntimeRequirement.required());
    }

    public static Path standardCacheDirectory(Path instanceRoot, DirectCefRuntimeRequirement requirement) {
        requirePath(instanceRoot, "instanceRoot");
        Objects.requireNonNull(requirement, "requirement");
        return instanceRoot.toAbsolutePath().normalize()
                .resolve("mcwebui").resolve("cache").resolve("cef").resolve(requirement.runtimeId());
    }

    /** Resolve a path from system properties, preserving the override's explicit precedence. */
    public static ValidatedDirectCefRuntime discover(Path instanceRoot, Path explicitOverride) {
        return discover(instanceRoot, explicitOverride, DirectCefRuntimeRequirement.required());
    }

    public static ValidatedDirectCefRuntime discover(Path instanceRoot, Path explicitOverride,
                                                     DirectCefRuntimeRequirement requirement) {
        Path root = requirePath(instanceRoot, "instanceRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(requirement, "requirement");
        boolean override = explicitOverride != null;
        Path selected = !override ? standardDirectory(root, requirement)
                : normalizeOverride(explicitOverride);
        // An explicitly supplied path is authoritative. A corrupt override must not silently
        // select a different standard runtime, which would make debugging and security policy
        // dependent on directory ordering.
        try {
            ValidatedDirectCefRuntime validated = DirectCefRuntimeValidator.validate(selected, requirement);
            return new ValidatedDirectCefRuntime(validated.directory(), validated.manifest(),
                    override ? ValidatedDirectCefRuntime.Source.OVERRIDE : ValidatedDirectCefRuntime.Source.STANDARD);
        } catch (DirectCefRuntimeException failure) {
            String message = "Direct CEF runtime is unavailable. Required runtime "
                    + requirement.runtimeId() + " (CEF " + requirement.cefVersion() + ", Chromium "
                    + requirement.chromiumVersion() + ", Windows " + requirement.arch() + "). Selected path: "
                    + selected + ". Automatic installation is not implemented yet; install a validated runtime manually."
                    + " Cause: " + failure.getMessage();
            throw new DirectCefRuntimeException(failure.reason(), message, failure, selected);
        }
    }

    public static ValidatedDirectCefRuntime discover(Path instanceRoot, String explicitOverride) {
        Path override = explicitOverride == null || explicitOverride.isBlank() ? null : Path.of(explicitOverride.trim());
        return discover(instanceRoot, override);
    }

    public static ValidatedDirectCefRuntime discoverFromProperties(Path instanceRoot) {
        String value = System.getProperty(RUNTIME_OVERRIDE_PROPERTY, "").trim();
        return discover(instanceRoot, value.isEmpty() ? null : Path.of(value));
    }

    /** A non-throwing probe used by setup UI/tests while retaining the typed failure. */
    public static Probe probe(Path instanceRoot, Path explicitOverride) {
        return probe(instanceRoot, explicitOverride, DirectCefRuntimeRequirement.required());
    }

    public static Probe probe(Path instanceRoot, Path explicitOverride, DirectCefRuntimeRequirement requirement) {
        try {
            return new Probe(discover(instanceRoot, explicitOverride, requirement), null);
        } catch (DirectCefRuntimeException failure) {
            return new Probe(null, failure);
        }
    }

    public record Probe(ValidatedDirectCefRuntime runtime, DirectCefRuntimeException failure) {
        public boolean valid() { return runtime != null && failure == null; }
    }

    private static Path normalizeOverride(Path path) {
        if (path == null) throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                "Direct CEF runtime override must not be null");
        String text = path.toString().trim();
        if (text.isEmpty()) throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                "Direct CEF runtime override must not be blank");
        return path.toAbsolutePath().normalize();
    }

    private static Path requirePath(Path path, String name) {
        if (path == null) throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.INVALID_ARGUMENT,
                "Direct CEF " + name + " must not be null");
        return path;
    }
}
