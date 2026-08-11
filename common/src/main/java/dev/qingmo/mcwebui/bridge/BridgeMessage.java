package dev.qingmo.mcwebui.bridge;

/** Marker interface for protocol envelopes. */
public sealed interface BridgeMessage permits BridgeRequest, BridgeResponse, BridgeEvent, BridgeStateUpdate,
        BridgeHandshake, BridgeSubscribe, BridgeUnsubscribe {
    int version();
    String type();
}
