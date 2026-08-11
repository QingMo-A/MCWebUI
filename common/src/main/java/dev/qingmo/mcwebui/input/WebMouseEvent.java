package dev.qingmo.mcwebui.input;

public record WebMouseEvent(Type type, double x, double y, int button) implements WebInputEvent {
    public enum Type { MOVE, DOWN, UP }
    public WebMouseEvent {
        if (type == null) throw new NullPointerException("type");
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("coordinates must be finite");
        if (button < -1 || button > 8) throw new IllegalArgumentException("invalid mouse button");
    }
}
