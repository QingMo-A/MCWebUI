package dev.qingmo.mcwebui.input;

public record WebTextInputEvent(String text, boolean composition, boolean committed) implements WebInputEvent {
    public WebTextInputEvent {
        if (text == null) throw new NullPointerException("text");
        if (text.length() > 4096) throw new IllegalArgumentException("text input exceeds 4096 characters");
    }
}
