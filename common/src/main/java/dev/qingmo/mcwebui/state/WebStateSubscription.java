package dev.qingmo.mcwebui.state;

@FunctionalInterface
public interface WebStateSubscription extends AutoCloseable {
    @Override
    void close();
}
