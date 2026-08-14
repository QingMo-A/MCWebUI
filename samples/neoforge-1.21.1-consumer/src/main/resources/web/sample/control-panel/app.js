const status = document.querySelector('#status');
const echoButton = document.querySelector('#echo');
const bridge = window.__MCWEBUI_BRIDGE__;

async function start() {
  if (!bridge) {
    status.textContent = 'Bridge unavailable';
    return;
  }
  try {
    await bridge.connect();
    status.textContent = 'sample.ready';
    bridge.subscribe('sample.ready', value => {
      status.textContent = `ready: ${value?.status ?? 'yes'}`;
    });
    echoButton.addEventListener('click', async () => {
      const response = await bridge.invoke('sample.echo', { message: 'hello from consumer' });
      status.textContent = response.message ?? 'echo received';
    });
  } catch (error) {
    status.textContent = error?.message ?? String(error);
  }
}

start();
