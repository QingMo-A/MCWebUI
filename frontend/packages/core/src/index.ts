export type BridgeCapability = "HANDSHAKE" | "RPC" | "EVENTS" | "STATE" | "INPUT" | "CLIPBOARD";

export interface BridgeErrorShape {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}

export interface BridgeHandshake {
  version: number;
  type: "handshake";
  runtime: string;
  capabilities: BridgeCapability[];
}

export interface BridgeResponse {
  version: number;
  type: "response";
  id: string;
  success: boolean;
  payload?: Record<string, unknown>;
  error?: BridgeErrorShape;
}

export interface BridgeEvent {
  version: number;
  type: "event";
  channel: string;
  payload: Record<string, unknown>;
}

export interface BridgeStateUpdate<T = unknown> {
  version: number;
  type: "state";
  channel: string;
  value: T;
  revision: number;
}

export type BridgeMessage = BridgeHandshake | BridgeResponse | BridgeEvent | BridgeStateUpdate;

export interface BridgeRequest {
  [key: string]: unknown;
  version: 1;
  type: "request";
  id: string;
  method: string;
  payload: Record<string, unknown>;
}

export interface BridgeTransport {
  send(message: Record<string, unknown>): void | Promise<void>;
  subscribe(listener: (message: BridgeMessage) => void): () => void;
  connect?(): Promise<BridgeHandshake>;
  close?(): void;
}

export class McBridgeError extends Error {
  readonly code: string;
  readonly details: Record<string, unknown>;

  constructor(error: BridgeErrorShape) {
    super(error.message);
    this.name = "McBridgeError";
    this.code = error.code;
    this.details = error.details ?? {};
  }
}

type EventListener = (payload: Record<string, unknown>) => void;
type StateListener<T> = (update: BridgeStateUpdate<T>) => void;

export interface McWebClient {
  readonly handshake: BridgeHandshake | null;
  connect(): Promise<BridgeHandshake>;
  invoke<T = Record<string, unknown>>(method: string, payload?: Record<string, unknown>): Promise<T>;
  on(channel: string, listener: EventListener): () => void;
  subscribe<T = unknown>(channel: string, listener: StateListener<T>): () => void;
  close(): void;
}

export class DefaultMcWebClient implements McWebClient {
  private readonly transport: BridgeTransport;
  private readonly pending = new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void }>();
  private readonly events = new Map<string, Set<EventListener>>();
  private readonly states = new Map<string, Set<StateListener<unknown>>>();
  private readonly requestPrefix = Math.random().toString(36).slice(2);
  private requestSequence = 0;
  private stopListening: (() => void) | undefined;
  private handshakeValue: BridgeHandshake | null = null;
  private connecting: Promise<BridgeHandshake> | undefined;

  constructor(transport: BridgeTransport = createDefaultTransport()) {
    this.transport = transport;
    this.stopListening = transport.subscribe((message) => this.receive(message));
  }

  get handshake(): BridgeHandshake | null { return this.handshakeValue; }

  async connect(): Promise<BridgeHandshake> {
    if (this.handshakeValue) return this.handshakeValue;
    if (this.connecting) return this.connecting;
    this.connecting = (async () => {
      const handshake = this.transport.connect
        ? await this.transport.connect()
        : await this.invoke<BridgeHandshake>("mcwebui.handshake", {});
      this.handshakeValue = handshake;
      return handshake;
    })();
    try { return await this.connecting; } finally { this.connecting = undefined; }
  }

  async invoke<T = Record<string, unknown>>(method: string, payload: Record<string, unknown> = {}): Promise<T> {
    if (!method || !method.trim()) throw new McBridgeError({ code: "MALFORMED_REQUEST", message: "method is required" });
    const id = `${this.requestPrefix}-${++this.requestSequence}`;
    const request: BridgeRequest = { version: 1, type: "request", id, method, payload };
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject });
      try {
        const result = this.transport.send(request);
        if (result instanceof Promise) result.catch((error: unknown) => this.rejectPending(id, error));
      } catch (error) { this.rejectPending(id, error); }
    });
  }

  on(channel: string, listener: EventListener): () => void {
    if (!channel) throw new Error("channel is required");
    const listeners = this.events.get(channel) ?? new Set<EventListener>();
    listeners.add(listener); this.events.set(channel, listeners);
    return () => { listeners.delete(listener); if (!listeners.size) this.events.delete(channel); };
  }

  subscribe<T = unknown>(channel: string, listener: StateListener<T>): () => void {
    if (!channel) throw new Error("channel is required");
    const listeners = this.states.get(channel) ?? new Set<StateListener<unknown>>();
    listeners.add(listener as StateListener<unknown>); this.states.set(channel, listeners);
    try { void this.transport.send({ version: 1, type: "subscribe", channel }); } catch { /* local subscription remains valid */ }
    return () => { listeners.delete(listener as StateListener<unknown>); if (!listeners.size) this.states.delete(channel); };
  }

  close(): void {
    this.stopListening?.(); this.stopListening = undefined;
    for (const pending of this.pending.values()) pending.reject(new McBridgeError({ code: "VIEW_CLOSED", message: "Bridge closed" }));
    this.pending.clear(); this.events.clear(); this.states.clear(); this.transport.close?.();
  }

  private receive(message: BridgeMessage): void {
    if (!message || typeof message !== "object") return;
    if (message.type === "response") {
      const pending = this.pending.get(message.id); if (!pending) return;
      this.pending.delete(message.id);
      if (message.success) pending.resolve(message.payload ?? {});
      else pending.reject(new McBridgeError(message.error ?? { code: "INTERNAL_ERROR", message: "Bridge request failed" }));
    } else if (message.type === "event") {
      for (const listener of this.events.get(message.channel) ?? []) listener(message.payload);
    } else if (message.type === "state") {
      for (const listener of this.states.get(message.channel) ?? []) listener(message as BridgeStateUpdate<unknown>);
    }
  }

  private rejectPending(id: string, error: unknown): void {
    const pending = this.pending.get(id); if (!pending) return;
    this.pending.delete(id);
    pending.reject(error instanceof Error ? error : new Error(String(error)));
  }
}

export function connect(transport?: BridgeTransport): McWebClient {
  return new DefaultMcWebClient(transport);
}

interface WindowBridgeGlobal {
  send?: (message: Record<string, unknown>) => void | Promise<void>;
  subscribe?: (listener: (message: BridgeMessage) => void) => () => void;
  connect?: () => Promise<BridgeHandshake>;
  close?: () => void;
}

export function createDefaultTransport(): BridgeTransport {
  const globalBridge = (globalThis as { __MCWEBUI_BRIDGE__?: WindowBridgeGlobal }).__MCWEBUI_BRIDGE__;
  if (!globalBridge?.send || !globalBridge.subscribe) {
    return {
      send: () => { throw new Error("MCWebUI bridge is not installed"); },
      subscribe: () => () => { /* no-op */ },
    };
  }
  return {
    send: globalBridge.send,
    subscribe: globalBridge.subscribe,
    connect: globalBridge.connect,
    close: globalBridge.close,
  };
}
