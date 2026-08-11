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

/** Private event used by the target bootstrap to wake a client that started early. */
export const MCWEBUI_BRIDGE_READY_EVENT = "__MCWEBUI_BRIDGE_READY__";

interface WindowBridgeGlobal {
  send?: (message: Record<string, unknown>) => void | Promise<void>;
  subscribe?: (listener: (message: BridgeMessage) => void) => () => void;
  connect?: () => Promise<BridgeHandshake>;
  close?: () => void;
}

interface BridgeRoot {
  __MCWEBUI_BRIDGE__?: WindowBridgeGlobal;
  addEventListener?: (type: string, listener: () => void) => void;
  removeEventListener?: (type: string, listener: () => void) => void;
}

/**
 * Resolves the host bridge at send/connect time instead of capturing a missing global during
 * application startup. The browser bootstrap emits MCWEBUI_BRIDGE_READY_EVENT after installing
 * the global; bounded checks cover hosts that cannot dispatch the private event.
 */
export class DeferredWindowBridgeTransport implements BridgeTransport {
  private readonly root: BridgeRoot;
  private readonly listeners = new Set<(message: BridgeMessage) => void>();
  private readonly waiters = new Set<{
    resolve: (bridge: WindowBridgeGlobal) => void;
    reject: (error: Error) => void;
  }>();
  private readonly timers = new Set<ReturnType<typeof setTimeout>>();
  private readonly readyListener = () => this.bindCurrentHost();
  private host: WindowBridgeGlobal | undefined;
  private hostStop: (() => void) | undefined;
  private closed = false;

  constructor(root: BridgeRoot = globalThis as unknown as BridgeRoot) {
    this.root = root;
    this.root.addEventListener?.(MCWEBUI_BRIDGE_READY_EVENT, this.readyListener);
    this.bindCurrentHost();
    // This is deliberately bounded and sparse; the event is the normal path.
    [0, 16, 64, 256, 1_024, 2_048].forEach((delay) => {
      const timer = setTimeout(() => {
        this.timers.delete(timer);
        this.bindCurrentHost();
      }, delay);
      this.timers.add(timer);
    });
  }

  send(message: Record<string, unknown>): Promise<void> {
    return this.waitForHost().then((host) => {
      if (!host.send) throw new McBridgeError({ code: "TRANSPORT_ERROR", message: "MCWebUI bridge send is unavailable" });
      return host.send(message);
    }).then(() => undefined);
  }

  subscribe(listener: (message: BridgeMessage) => void): () => void {
    if (this.closed) return () => undefined;
    this.listeners.add(listener);
    this.bindCurrentHost();
    return () => this.listeners.delete(listener);
  }

  connect(): Promise<BridgeHandshake> {
    return this.waitForHost().then((host) => {
      if (!host.connect) throw new McBridgeError({ code: "TRANSPORT_ERROR", message: "MCWebUI bridge handshake is unavailable" });
      return host.connect();
    });
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    try { this.host?.close?.(); } catch { /* host cleanup is best effort */ }
    this.root.removeEventListener?.(MCWEBUI_BRIDGE_READY_EVENT, this.readyListener);
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
    this.detachHost();
    const error = new McBridgeError({ code: "VIEW_CLOSED", message: "Bridge closed" });
    for (const waiter of this.waiters) waiter.reject(error);
    this.waiters.clear();
    this.listeners.clear();
  }

  private waitForHost(): Promise<WindowBridgeGlobal> {
    this.bindCurrentHost();
    if (this.closed) return Promise.reject(new McBridgeError({ code: "VIEW_CLOSED", message: "Bridge closed" }));
    if (this.host?.send && this.host.subscribe) return Promise.resolve(this.host);
    return new Promise((resolve, reject) => this.waiters.add({ resolve, reject }));
  }

  private bindCurrentHost(): void {
    if (this.closed) return;
    const candidate = this.root.__MCWEBUI_BRIDGE__;
    const next = candidate?.send && candidate.subscribe ? candidate : undefined;
    if (next === this.host) return;
    this.detachHost();
    this.host = next;
    if (!next) return;
    try {
      this.hostStop = next.subscribe!((message) => {
        for (const listener of this.listeners) {
          try { listener(message); } catch { /* isolate transport consumers */ }
        }
      });
    } catch {
      this.hostStop = undefined;
    }
    const waiters = [...this.waiters];
    this.waiters.clear();
    for (const waiter of waiters) waiter.resolve(next);
  }

  private detachHost(): void {
    this.hostStop?.();
    this.hostStop = undefined;
    this.host = undefined;
  }
}

export class DefaultMcWebClient implements McWebClient {
  private readonly transport: BridgeTransport;
  private readonly pending = new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void }>();
  private readonly events = new Map<string, Set<EventListener>>();
  private readonly states = new Map<string, Set<StateListener<unknown>>>();
  private readonly remoteStates = new Set<string>();
  private readonly activatingStates = new Map<string, Promise<void>>();
  private readonly connectionListeners = new Set<ConnectionListener>();
  private readonly requestPrefix = Math.random().toString(36).slice(2);
  private requestSequence = 0;
  private stopListening: (() => void) | undefined;
  private handshakeValue: BridgeHandshake | null = null;
  private connecting: Promise<BridgeHandshake> | undefined;
  private stateValue: ConnectionState = "disconnected";
  private errorValue: McBridgeError | null = null;
  private closed = false;

  constructor(transport: BridgeTransport = createDefaultTransport()) {
    this.transport = transport;
    this.stopListening = transport.subscribe((message) => this.receive(message));
  }
  get handshake(): BridgeHandshake | null { return this.handshakeValue; }
  get connectionState(): ConnectionState { return this.stateValue; }
  get connectionError(): McBridgeError | null { return this.errorValue; }

  async connect(): Promise<BridgeHandshake> {
    if (this.closed) throw new McBridgeError({ code: "VIEW_CLOSED", message: "Bridge closed" });
    if (this.handshakeValue) return this.handshakeValue;
    if (this.connecting) return this.connecting;
    this.setConnectionState("connecting");
    this.connecting = (async () => {
      const handshake = this.transport.connect
        ? await this.transport.connect()
        : await this.sendRequest<BridgeHandshake>("mcwebui.handshake", {});
      if (!handshake || handshake.type !== "handshake") {
        throw new McBridgeError({ code: "TRANSPORT_ERROR", message: "Invalid bridge handshake" });
      }
      this.handshakeValue = handshake;
      await Promise.all([...this.states.keys()].map((channel) => this.ensureRemoteSubscription(channel)));
      this.setConnectionState("connected");
      return handshake;
    })();
    try { return await this.connecting; }
    catch (cause) {
      this.handshakeValue = null;
      const error = this.asError(cause);
      this.setConnectionState("error", error);
      throw error;
    } finally { this.connecting = undefined; }
  }

  invoke<T = Record<string, unknown>>(method: string, payload: Record<string, unknown> = {}): Promise<T> {
    if (!method || !method.trim()) return Promise.reject(new McBridgeError({ code: "MALFORMED_REQUEST", message: "method is required" }));
    if (this.closed) return Promise.reject(new McBridgeError({ code: "VIEW_CLOSED", message: "Bridge closed" }));
    if (!this.handshakeValue) return Promise.reject(new McBridgeError({ code: "NOT_CONNECTED", message: "Bridge handshake is required" }));
    if (!this.handshakeValue.capabilities.includes("RPC")) {
      return Promise.reject(new McBridgeError({ code: "CAPABILITY_DENIED", message: "RPC capability was not negotiated" }));
    }
    return this.sendRequest<T>(method, payload);
  }

  on(channel: string, listener: EventListener): () => void {
    if (!channel) throw new Error("channel is required");
    const listeners = this.events.get(channel) ?? new Set<EventListener>();
    listeners.add(listener); this.events.set(channel, listeners);
    return () => { listeners.delete(listener); if (!listeners.size) this.events.delete(channel); };
  }

  subscribe<T = unknown>(channel: string, listener: StateListener<T>): () => void {
    if (!channel) throw new Error("channel is required");
    if (this.closed) return () => undefined;
    const listeners = this.states.get(channel) ?? new Set<StateListener<unknown>>();
    listeners.add(listener as StateListener<unknown>); this.states.set(channel, listeners);
    if (this.handshakeValue) void this.ensureRemoteSubscription(channel).catch(() => undefined);
    let active = true;
    return () => {
      if (!active) return;
      active = false;
      listeners.delete(listener as StateListener<unknown>);
      if (!listeners.size) {
        this.states.delete(channel);
        if (this.remoteStates.delete(channel)) void this.sendControl({ version: 1, type: "unsubscribe", channel }).catch(() => undefined);
      }
    };
  }

  onConnectionState(listener: ConnectionListener): () => void {
    this.connectionListeners.add(listener);
    listener(this.stateValue, this.errorValue ?? undefined);
    return () => this.connectionListeners.delete(listener);
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    // Send best-effort unsubscriptions before closing the transport so synchronous hosts can
    // release their session subscriptions. The host close path also performs deterministic cleanup.
    for (const channel of this.remoteStates) void this.sendControl({ version: 1, type: "unsubscribe", channel }).catch(() => undefined);
    this.remoteStates.clear();
    this.stopListening?.(); this.stopListening = undefined;
    for (const pending of this.pending.values()) pending.reject(new McBridgeError({ code: "VIEW_CLOSED", message: "Bridge closed" }));
    this.pending.clear(); this.events.clear(); this.states.clear(); this.handshakeValue = null;
    this.setConnectionState("disconnected");
    this.transport.close?.();
  }

  private async ensureRemoteSubscription(channel: string): Promise<void> {
    if (this.closed || !this.handshakeValue || !this.states.has(channel) || this.remoteStates.has(channel)) return;
    if (!this.handshakeValue.capabilities.includes("STATE")) return;
    const active = this.activatingStates.get(channel);
    if (active) return active;
    const task = (async () => {
      await this.sendControl({ version: 1, type: "subscribe", channel });
      if (this.closed || !this.handshakeValue || !this.states.has(channel)) {
        await this.sendControl({ version: 1, type: "unsubscribe", channel });
        return;
      }
      this.remoteStates.add(channel);
    })();
    this.activatingStates.set(channel, task);
    try { await task; } finally { this.activatingStates.delete(channel); }
  }

  private sendControl(message: Record<string, unknown>): Promise<void> {
    try { return Promise.resolve(this.transport.send(message)).then(() => undefined); }
    catch (error) { return Promise.reject(this.asError(error)); }
  }

  private sendRequest<T>(method: string, payload: Record<string, unknown>): Promise<T> {
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

export function createDefaultTransport(): BridgeTransport {
  return new DeferredWindowBridgeTransport();
}
