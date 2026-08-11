export type BridgeCapability = "HANDSHAKE" | "RPC" | "EVENTS" | "STATE" | "INPUT" | "CLIPBOARD";
export type ConnectionState = "disconnected" | "connecting" | "connected" | "error";

export interface BridgeErrorShape { code: string; message: string; details?: Record<string, unknown>; }
export interface BridgeHandshake {
  version: number;
  type: "handshake";
  runtime: string;
  capabilities: BridgeCapability[];
  runtimeInfo?: Record<string, unknown>;
}
export interface BridgeResponse {
  version: number; type: "response"; id: string; success: boolean;
  payload?: Record<string, unknown>; error?: BridgeErrorShape;
}
export interface BridgeEvent { version: number; type: "event"; channel: string; payload: Record<string, unknown>; }
export interface BridgeStateUpdate<T = unknown> { version: number; type: "state"; channel: string; value: T; revision: number; }
export type BridgeMessage = BridgeHandshake | BridgeResponse | BridgeEvent | BridgeStateUpdate;

export interface BridgeRequest {
  [key: string]: unknown;
  version: 1; type: "request"; id: string; method: string; payload: Record<string, unknown>;
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
    super(error.message); this.name = "McBridgeError"; this.code = error.code; this.details = error.details ?? {};
  }
}

type EventListener = (payload: Record<string, unknown>) => void;
type StateListener<T> = (update: BridgeStateUpdate<T>) => void;
type ConnectionListener = (state: ConnectionState, error?: McBridgeError) => void;

export interface McWebClient {
  readonly handshake: BridgeHandshake | null;
  readonly connectionState: ConnectionState;
  readonly connectionError: McBridgeError | null;
  connect(): Promise<BridgeHandshake>;
  invoke<T = Record<string, unknown>>(method: string, payload?: Record<string, unknown>): Promise<T>;
  on(channel: string, listener: EventListener): () => void;
  subscribe<T = unknown>(channel: string, listener: StateListener<T>): () => void;
  onConnectionState(listener: ConnectionListener): () => void;
  close(): void;
}

export class DefaultMcWebClient implements McWebClient {
  private readonly transport: BridgeTransport;
  private readonly pending = new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void }>();
  private readonly events = new Map<string, Set<EventListener>>();
  private readonly states = new Map<string, Set<StateListener<unknown>>>();
  private readonly connectionListeners = new Set<ConnectionListener>();
  private readonly requestPrefix = Math.random().toString(36).slice(2);
  private requestSequence = 0;
  private stopListening: (() => void) | undefined;
  private handshakeValue: BridgeHandshake | null = null;
  private connecting: Promise<BridgeHandshake> | undefined;
  private stateValue: ConnectionState = "disconnected";
  private errorValue: McBridgeError | null = null;

  constructor(transport: BridgeTransport = createDefaultTransport()) {
    this.transport = transport;
    this.stopListening = transport.subscribe((message) => this.receive(message));
  }
  get handshake(): BridgeHandshake | null { return this.handshakeValue; }
  get connectionState(): ConnectionState { return this.stateValue; }
  get connectionError(): McBridgeError | null { return this.errorValue; }

  async connect(): Promise<BridgeHandshake> {
    if (this.handshakeValue) return this.handshakeValue;
    if (this.connecting) return this.connecting;
    this.setConnectionState("connecting");
    this.connecting = (async () => {
      const handshake = this.transport.connect
        ? await this.transport.connect()
        : await this.invoke<BridgeHandshake>("mcwebui.handshake", {});
      this.handshakeValue = handshake;
      this.setConnectionState("connected");
      return handshake;
    })();
    try { return await this.connecting; }
    catch (cause) {
      const error = cause instanceof McBridgeError ? cause : new McBridgeError({ code: "TRANSPORT_ERROR", message: String(cause) });
      this.setConnectionState("error", error);
      throw error;
    } finally { this.connecting = undefined; }
  }

  async invoke<T = Record<string, unknown>>(method: string, payload: Record<string, unknown> = {}): Promise<T> {
    if (!method || !method.trim()) throw new McBridgeError({ code: "MALFORMED_REQUEST", message: "method is required" });
    const id = `${this.requestPrefix}-${++this.requestSequence}`;
    const request: BridgeRequest = { version: 1, type: "request", id, method, payload };
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject });
      try {
        const result = this.transport.send(request);
        if (result instanceof Promise) result.catch((error: unknown) => this.rejectPending(id, this.asError(error)));
      } catch (error) { this.rejectPending(id, this.asError(error)); }
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
    const first = listeners.size === 0;
    listeners.add(listener as StateListener<unknown>); this.states.set(channel, listeners);
    if (first) this.sendControl({ version: 1, type: "subscribe", channel });
    let active = true;
    return () => {
      if (!active) return;
      active = false;
      listeners.delete(listener as StateListener<unknown>);
      if (!listeners.size) {
        this.states.delete(channel);
        this.sendControl({ version: 1, type: "unsubscribe", channel });
      }
    };
  }

  onConnectionState(listener: ConnectionListener): () => void {
    this.connectionListeners.add(listener);
    listener(this.stateValue, this.errorValue ?? undefined);
    return () => this.connectionListeners.delete(listener);
  }

  close(): void {
    this.stopListening?.(); this.stopListening = undefined;
    for (const pending of this.pending.values()) pending.reject(new McBridgeError({ code: "VIEW_CLOSED", message: "Bridge closed" }));
    this.pending.clear(); this.events.clear(); this.states.clear(); this.handshakeValue = null;
    this.setConnectionState("disconnected");
    this.transport.close?.();
  }

  private receive(message: BridgeMessage): void {
    if (!message || typeof message !== "object") return;
    if (message.type === "response") {
      const pending = this.pending.get(message.id); if (!pending) return;
      this.pending.delete(message.id);
      if (message.success) pending.resolve(message.payload ?? {});
      else pending.reject(new McBridgeError(message.error ?? { code: "INTERNAL_ERROR", message: "Bridge request failed" }));
    } else if (message.type === "event") {
      for (const listener of this.events.get(message.channel) ?? []) { try { listener(message.payload); } catch { /* isolate clients */ } }
    } else if (message.type === "state") {
      for (const listener of this.states.get(message.channel) ?? []) { try { listener(message as BridgeStateUpdate<unknown>); } catch { /* isolate clients */ } }
    }
  }
  private sendControl(message: Record<string, unknown>): void {
    try {
      const result = this.transport.send(message);
      if (result instanceof Promise) result.catch(() => undefined);
    } catch { /* a later connect/reload can recreate the subscription */ }
  }
  private rejectPending(id: string, error: McBridgeError): void {
    const pending = this.pending.get(id); if (!pending) return;
    this.pending.delete(id); pending.reject(error);
  }
  private asError(error: unknown): McBridgeError {
    return error instanceof McBridgeError ? error : new McBridgeError({ code: "TRANSPORT_ERROR", message: String(error) });
  }
  private setConnectionState(state: ConnectionState, error: McBridgeError | null = null): void {
    this.stateValue = state; this.errorValue = error;
    for (const listener of this.connectionListeners) { try { listener(state, error ?? undefined); } catch { /* isolate observers */ } }
  }
}

export function connect(transport?: BridgeTransport): McWebClient { return new DefaultMcWebClient(transport); }

interface WindowBridgeGlobal {
  send?: (message: Record<string, unknown>) => void | Promise<void>;
  subscribe?: (listener: (message: BridgeMessage) => void) => () => void;
  connect?: () => Promise<BridgeHandshake>;
  close?: () => void;
}
export function createDefaultTransport(): BridgeTransport {
  const globalBridge = (globalThis as { __MCWEBUI_BRIDGE__?: WindowBridgeGlobal }).__MCWEBUI_BRIDGE__;
  if (!globalBridge?.send || !globalBridge.subscribe) {
    return { send: () => { throw new Error("MCWebUI bridge is not installed"); }, subscribe: () => () => { /* no-op */ } };
  }
  return { send: globalBridge.send, subscribe: globalBridge.subscribe, connect: globalBridge.connect, close: globalBridge.close };
}
