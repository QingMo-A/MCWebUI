package dev.qingmo.mcwebui.state;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Thread-safe authoritative state store with latest-value subscription semantics. */
public final class WebStateStore {
    private static final class Channel {
        volatile WebStateUpdate latest;
        final CopyOnWriteArrayList<Consumer<WebStateUpdate>> subscribers = new CopyOnWriteArrayList<>();
    }

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    public WebStateUpdate publish(String channel, Object value) {
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel must not be blank");
        Channel state = channels.computeIfAbsent(channel, ignored -> new Channel());
        WebStateUpdate update = new WebStateUpdate(channel, value, revision.incrementAndGet());
        state.latest = update;
        for (Consumer<WebStateUpdate> subscriber : state.subscribers) {
            try {
                subscriber.accept(update);
            } catch (RuntimeException ignored) {
                // One subscriber must not prevent publication to the remaining clients.
            }
        }
        return update;
    }

    public WebStateUpdate latest(String channel) {
        Channel state = channels.get(channel);
        return state == null ? null : state.latest;
    }

    public WebStateSubscription subscribe(String channel, Consumer<WebStateUpdate> subscriber) {
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel must not be blank");
        Objects.requireNonNull(subscriber, "subscriber");
        Channel state = channels.computeIfAbsent(channel, ignored -> new Channel());
        state.subscribers.add(subscriber);
        WebStateUpdate current = state.latest;
        if (current != null) {
            subscriber.accept(current);
        }
        return () -> state.subscribers.remove(subscriber);
    }

    public int subscriberCount(String channel) {
        Channel state = channels.get(channel);
        return state == null ? 0 : state.subscribers.size();
    }

    public void clear() {
        channels.clear();
    }
}
