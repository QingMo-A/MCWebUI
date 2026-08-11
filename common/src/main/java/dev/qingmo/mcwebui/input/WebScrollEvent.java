package dev.qingmo.mcwebui.input;

public record WebScrollEvent(double x, double y, double deltaX, double deltaY) implements WebInputEvent {
    public WebScrollEvent {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(deltaX) || !Double.isFinite(deltaY)) {
            throw new IllegalArgumentException("scroll values must be finite");
        }
    }
}
