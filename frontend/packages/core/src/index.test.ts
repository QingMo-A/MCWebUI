import { describe, expect, it } from "vitest";
import {
  DeferredWindowBridgeTransport,
  MCWEBUI_BRIDGE_READY_EVENT,
  McBridgeError,
  connect,
  type BridgeHandshake,
  type BridgeMessage,
  type BridgeTransport,
} from "./index";

class FakeRoot {
  __MCWEBUI_BRIDGE__: FakeHost | undefined;
  private readonly events = new Map<string, Set<() => void>>();
  addEventListener(type: string, listener: () => void): void {
    const listeners = this.events.get(type) ?? new Set<() => void>();
    listeners.add(listener); this.events.set(type, listeners);
  }
  removeEventListener(type: string, listener: () => void): void { this.events.get(type)?.delete(listener); }
  dispatch(type: string): void { for (const listener of this.events.get(type) ?? []) listener(); }
  listenerCount(type: string): number { return this.events.get(type)?.size ?? 0; }
}

class FakeHost {
  readonly messages: Record<string, unknown>[] = [];
  readonly listeners = new Set<(message: BridgeMessage) => void>();
  readonly handshake: BridgeHandshake = { version: 1, type: "handshake", runtime: "mcwebui", capabilities: ["HANDSHAKE", "RPC", "STATE"] };
  closeCalls = 0;
  subscribeCalls = 0;
  failSend = false;
  failConnect = false;

  connect(): Promise<BridgeHandshake> {
    if (this.failConnect) return Promise.reject(new Error("host unavailable"));
    return Promise.resolve(this.handshake);
  }
  send(message: Record<string, unknown>): void {
    if (this.failSend) throw new Error("host send failed");
    this.messages.push(message);
    if (message.type === "request" && typeof message.id === "string") {
      queueMicrotask(() => this.respond(message.id as string, { message: "pong" }));
    }
  }
  subscribe(listener: (message: BridgeMessage) => void): () => void {
    this.subscribeCalls++;
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
  close(): void { this.closeCalls++; }
  state(channel: string, value: unknown, revision = 1): void {
    const message = { version: 1, type: "state", channel, value, revision } as BridgeMessage;
    for (const listener of this.listeners) listener(message);
  }
  respond(id: string, payload: Record<string, unknown> = {}): void {
    const message = { version: 1, type: "response", id, success: true, payload } as BridgeMessage;
    for (const listener of this.listeners) listener(message);
  }
}

describe("late-bound bridge bootstrap", () => {
  it("connects and subscribes when the host is installed after client creation", async () => {
    const root = new FakeRoot();
    const host = new FakeHost();
    const transport = new DeferredWindowBridgeTransport(root);
    const client = connect(transport);
    const updates: unknown[] = [];
    const unsubscribe = client.subscribe("demo.counter", (update) => updates.push(update.value));

    const connecting = client.connect();
    await Promise.resolve();
    expect(host.messages).toHaveLength(0);

    root.__MCWEBUI_BRIDGE__ = host;
    root.dispatch(MCWEBUI_BRIDGE_READY_EVENT);
    await expect(connecting).resolves.toMatchObject({ type: "handshake" });
    expect(host.messages.filter((message) => message.type === "subscribe")).toHaveLength(1);

    host.state("demo.counter", 7);
    expect(updates).toEqual([7]);
    await expect(client.invoke<{ message: string }>("demo.ping", {})).resolves.toEqual({ message: "pong" });

    unsubscribe();
    await Promise.resolve();
    expect(host.messages.filter((message) => message.type === "unsubscribe")).toHaveLength(1);
    client.close();
    expect(host.closeCalls).toBe(1);
  });

  it("keeps local subscriptions across a failed connection and reports transport errors", async () => {
    const root = new FakeRoot();
    const host = new FakeHost();
    host.failConnect = true;
    const client = connect(new DeferredWindowBridgeTransport(root));
    client.subscribe("demo.counter", () => undefined);
    root.__MCWEBUI_BRIDGE__ = host;
    root.dispatch(MCWEBUI_BRIDGE_READY_EVENT);
    await expect(client.connect()).rejects.toMatchObject({ code: "TRANSPORT_ERROR" });
    expect(client.connectionState).toBe("error");

    host.failConnect = false;
    await client.connect();
    expect(host.messages.filter((message) => message.type === "subscribe")).toHaveLength(1);

    host.failSend = true;
    await expect(client.invoke("demo.ping", {})).rejects.toBeInstanceOf(McBridgeError);
    client.close();
  });
});

describe("client lifecycle ordering", () => {
  it("does not emit host subscribe or RPC before handshake", async () => {
    const handshake: BridgeHandshake = { version: 1, type: "handshake", runtime: "mcwebui", capabilities: ["HANDSHAKE", "RPC", "STATE"] };
    const messages: Record<string, unknown>[] = [];
    let receive: ((message: BridgeMessage) => void) | undefined;
    const transport: BridgeTransport = {
      send(message) { messages.push(message); },
      subscribe(listener) { receive = listener; return () => undefined; },
      connect: () => Promise.resolve(handshake),
    };
    const client = connect(transport);
    client.subscribe("demo.counter", () => undefined);
    expect(messages).toHaveLength(0);
    await client.connect();
    expect(messages.map((message) => message.type)).toEqual(["subscribe"]);
    expect(() => receive?.({ version: 1, type: "state", channel: "demo.counter", value: 2, revision: 1 })).not.toThrow();
    client.close();
  });

  it("cancels a pending late-host connect and ignores a host installed after close", async () => {
    const root = new FakeRoot();
    const transport = new DeferredWindowBridgeTransport(root);
    const client = connect(transport);
    const connecting = client.connect();
    expect(client.connectionState).toBe("connecting");
    expect(root.listenerCount(MCWEBUI_BRIDGE_READY_EVENT)).toBe(1);

    client.close();
    await expect(connecting).rejects.toMatchObject({ code: "VIEW_CLOSED" });
    expect(client.connectionState).toBe("disconnected");
    expect(root.listenerCount(MCWEBUI_BRIDGE_READY_EVENT)).toBe(0);

    const host = new FakeHost();
    root.__MCWEBUI_BRIDGE__ = host;
    root.dispatch(MCWEBUI_BRIDGE_READY_EVENT);
    await Promise.resolve();
    expect(host.subscribeCalls).toBe(0);
    expect(host.messages).toHaveLength(0);
    await expect(client.connect()).rejects.toMatchObject({ code: "VIEW_CLOSED" });
    await expect(client.invoke("demo.ping", {})).rejects.toMatchObject({ code: "VIEW_CLOSED" });
  });
});
