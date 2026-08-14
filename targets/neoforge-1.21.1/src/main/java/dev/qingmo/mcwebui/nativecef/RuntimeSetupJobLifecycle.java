package dev.qingmo.mcwebui.nativecef;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pure-Java lifecycle token for setup jobs; disposal never joins a worker. */
public final class RuntimeSetupJobLifecycle implements RuntimeDownloadCancellationToken {
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<AutoCloseable> activeResource = new AtomicReference<>();
    private final ExecutorService executor;

    public RuntimeSetupJobLifecycle() {
        this(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MCWebUI Direct CEF Setup Test Job");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public RuntimeSetupJobLifecycle(ExecutorService executor) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    public boolean begin() {
        return !disposed.get() && running.compareAndSet(false, true);
    }
    public void finish() { running.set(false); }
    public void execute(Runnable task) {
        if (disposed.get()) return;
        executor.execute(task);
    }
    public void cancel() {
        cancelled.set(true);
        closeActiveResource();
    }
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            cancelled.set(true);
            running.set(false);
            closeActiveResource();
            executor.shutdown();
        }
    }
    public boolean isDisposed() { return disposed.get(); }
    public boolean isRunning() { return running.get(); }
    public boolean isExecutorShutdown() { return executor.isShutdown(); }
    public boolean acceptsUiCallback() { return !disposed.get(); }
    @Override public boolean isCancelled() { return cancelled.get() || disposed.get(); }

    @Override public void registerResource(AutoCloseable resource) {
        if (resource == null) return;
        if (!activeResource.compareAndSet(null, resource)) {
            throw new IllegalStateException("a setup job already owns a blocking resource");
        }
        if (isCancelled() && activeResource.compareAndSet(resource, null)) closeQuietly(resource);
    }

    @Override public void clearResource(AutoCloseable resource) {
        if (resource != null) activeResource.compareAndSet(resource, null);
    }

    private void closeActiveResource() {
        closeQuietly(activeResource.getAndSet(null));
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception ignored) { }
    }
}
