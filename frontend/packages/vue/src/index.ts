import { computed, inject, onBeforeUnmount, onMounted, provide, ref, type InjectionKey, type Ref } from "vue";
import { connect, type BridgeTransport, type McWebClient } from "@mcwebui/core";

const clientKey: InjectionKey<McWebClient> = Symbol("mcwebui-client");
let sharedClient: McWebClient | undefined;

export function provideMcBridge(client: McWebClient): void {
  provide(clientKey, client);
}

export function useMcBridge(transport?: BridgeTransport): McWebClient {
  const provided = inject(clientKey, undefined);
  if (provided) return provided;
  if (!sharedClient) sharedClient = connect(transport);
  return sharedClient;
}

export function useMcState<T>(channel: string, initialValue: T): Ref<T> {
  const client = useMcBridge();
  const value = ref(initialValue) as Ref<T>;
  let unsubscribe: (() => void) | undefined;
  const subscribe = () => {
    unsubscribe = client.subscribe<T>(channel, (update) => { value.value = update.value; });
    void client.connect().catch(() => { /* state remains at the documented initial value */ });
  };
  if (typeof window === "undefined") subscribe();
  else onMounted(subscribe);
  onBeforeUnmount(() => unsubscribe?.());
  return value;
}

export function useMcRpc() {
  const client = useMcBridge();
  return {
    client,
    invoke: client.invoke.bind(client),
    connected: computed(() => client.handshake !== null),
  };
}
