import { computed, inject, onBeforeUnmount, onMounted, provide, ref, type InjectionKey, type Ref } from "vue";
import { connect, type BridgeTransport, type ConnectionState, type McWebClient } from "@mcwebui/core";

const clientKey: InjectionKey<McWebClient> = Symbol("mcwebui-client");
let sharedClient: McWebClient | undefined;

export function provideMcBridge(client: McWebClient): void { provide(clientKey, client); }
export function useMcBridge(transport?: BridgeTransport): McWebClient {
  const provided = inject(clientKey, undefined);
  if (provided) return provided;
  if (!sharedClient) sharedClient = connect(transport);
  return sharedClient;
}

export function useMcConnection() {
  const client = useMcBridge();
  const state = ref<ConnectionState>(client.connectionState);
  const error = ref(client.connectionError);
  let stop: (() => void) | undefined;
  const attach = () => { stop = client.onConnectionState((next, cause) => { state.value = next; error.value = cause ?? null; }); };
  if (typeof window === "undefined") attach(); else onMounted(attach);
  onBeforeUnmount(() => stop?.());
  return { client, state, error, connected: computed(() => state.value === "connected") };
}

export function useMcState<T>(channel: string, initialValue: T): Ref<T> {
  const client = useMcBridge();
  const value = ref(initialValue) as Ref<T>;
  let unsubscribe: (() => void) | undefined;
  const subscribe = () => {
    unsubscribe = client.subscribe<T>(channel, (update) => { value.value = update.value; });
    // Register the local listener first; core activates the host subscription only after
    // handshake negotiation. Multiple composables share that lifecycle through the client.
    void client.connect().catch(() => undefined);
  };
  if (typeof window === "undefined") subscribe(); else onMounted(subscribe);
  onBeforeUnmount(() => unsubscribe?.());
  return value;
}

export function useMcRpc() {
  const connection = useMcConnection();
  return { ...connection, invoke: connection.client.invoke.bind(connection.client) };
}
