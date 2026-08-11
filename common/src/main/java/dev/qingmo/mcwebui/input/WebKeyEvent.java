package dev.qingmo.mcwebui.input;

public record WebKeyEvent(Type type, int keyCode, int scanCode, int modifiers) implements WebInputEvent {
    public enum Type { DOWN, UP }
    public WebKeyEvent {
        if (type == null) throw new NullPointerException("type");
        if (keyCode < 0) throw new IllegalArgumentException("keyCode must not be negative");
        if (scanCode < 0) throw new IllegalArgumentException("scanCode must not be negative");
    }

    public WebKeyEvent(Type type, int keyCode, int modifiers) { this(type, keyCode, 0, modifiers); }
}
