package dev.qingmo.mcwebui.nativecef;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Process-global Direct CEF native loader. CEF cannot safely host two identities in one
 * process, so the first validated runtime pins both its identity and canonical directory.
 */
public final class DirectCefNativeLoader {
    private static State state;

    private DirectCefNativeLoader() { }

    /** Validate process-global compatibility without touching native code. */
    public static synchronized void guard(ValidatedDirectCefRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        State requested = stateFor(runtime);
        if (state == null) {
            state = requested;
            return;
        }
        if (!state.identity().sameIdentity(requested.identity()) || !state.root().equals(requested.root())) {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.CONFLICTING_RUNTIME,
                    "A different Direct CEF runtime is already selected in this process (loaded="
                            + state.root() + ", requested=" + requested.root() + ")", requested.root());
        }
    }

    /** Load CEF dependencies only after the supplied directory has passed validation. */
    public static synchronized void load(ValidatedDirectCefRuntime runtime) {
        guard(runtime);
        if (state.loaded()) return;
        try {
            // CEF's imported helper must be loaded before libcef and the MCWebUI JNI bridge.
            System.load(runtime.chromeElfLibrary().toString());
            System.load(runtime.cefLibrary().toString());
            System.load(runtime.nativeLibrary().toString());
            state = state.loaded(true);
        } catch (LinkageError | SecurityException ex) {
            // Keep the process pinned after a partial load: attempting to swap CEF identities
            // after a failed dependency load is no safer than doing so after success.
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.NATIVE_LOAD_FAILED,
                    "Unable to load Direct CEF native runtime " + runtime.identity().runtimeId(), ex,
                    runtime.directory());
        }
    }

    public static synchronized boolean isLoaded() { return state != null && state.loaded(); }

    public static synchronized DirectCefRuntimeRequirement loadedIdentity() {
        return state == null ? null : state.identity();
    }

    public static synchronized Path loadedRoot() { return state == null ? null : state.root(); }

    /** Test hook: reset only the Java guard; tests must not invoke native loading concurrently. */
    static synchronized void resetForTests() { state = null; }

    private static State stateFor(ValidatedDirectCefRuntime runtime) {
        Path root;
        try {
            root = runtime.directory().toRealPath().toAbsolutePath().normalize();
        } catch (java.io.IOException ex) {
            throw new DirectCefRuntimeException(DirectCefRuntimeFailureReason.IO_ERROR,
                    "Unable to resolve Direct CEF runtime root: " + runtime.directory(), ex, runtime.directory());
        }
        return new State(runtime.identity(), root, false);
    }

    private record State(DirectCefRuntimeRequirement identity, Path root, boolean loaded) {
        State loaded(boolean value) { return new State(identity, root, value); }
    }
}
