package dev.qingmo.mcwebui.bridge;

@FunctionalInterface
public interface BridgeHandler {
    Object handle(BridgeRequest request) throws Exception;
}
