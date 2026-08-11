package dev.qingmo.mcwebui.input;

public sealed interface WebInputEvent permits WebMouseEvent, WebScrollEvent, WebKeyEvent, WebTextInputEvent, WebFocusEvent {
}
