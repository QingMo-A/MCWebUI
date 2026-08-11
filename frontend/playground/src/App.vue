<script setup lang="ts">
import { ref } from "vue";
import { useMcBridge, useMcState } from "@mcwebui/vue";

const bridge = useMcBridge();
const counter = useMcState<number>("demo.counter", 0);
const message = ref("hello");
const pingResult = ref("—");
const error = ref("");
const input = ref("");

async function pingJava() {
  error.value = "";
  try {
    const response = await bridge.invoke<{ message?: string; timestamp?: number }>("demo.ping", { message: message.value });
    pingResult.value = `${response.message ?? "pong"} @ ${response.timestamp ?? "—"}`;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause);
  }
}

async function incrementJavaState() {
  error.value = "";
  try {
    await bridge.invoke("demo.counter.increment", { amount: 1 });
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause);
  }
}
</script>

<template>
  <main class="page">
    <header>
      <p class="eyebrow">MCWebUI Runtime Demo</p>
      <h1>Browser runtime vertical slice</h1>
      <p class="lede">The same bundled Vue page is loaded through the <code>mcui://</code> resource protocol.</p>
    </header>

    <section class="grid">
      <article class="card status-card">
        <h2>Runtime</h2>
        <dl>
          <div><dt>Target</dt><dd>NeoForge 1.21.1</dd></div>
          <div><dt>Browser</dt><dd>MCEF / CEF off-screen</dd></div>
          <div><dt>Bridge</dt><dd :class="bridge.handshake ? 'ok' : 'pending'">{{ bridge.handshake ? 'Connected' : 'Connecting…' }}</dd></div>
        </dl>
      </article>

      <article class="card counter-card">
        <h2>Java state</h2>
        <div class="counter">{{ counter }}</div>
        <p class="muted">demo.counter · latest value subscription</p>
        <button type="button" @click="incrementJavaState">Increment Java state</button>
      </article>

      <article class="card">
        <h2>Typed RPC</h2>
        <label>Message <input v-model="message" autocomplete="off" /></label>
        <button type="button" @click="pingJava">Ping Java</button>
        <output>{{ pingResult }}</output>
      </article>

      <article class="card">
        <h2>Input / IME</h2>
        <label>Chinese input test
          <input v-model="input" placeholder="中文输入测试框" autocomplete="off" />
        </label>
        <p class="muted">Try English, numbers, 中文输入法, Backspace, Ctrl+A/C/V.</p>
      </article>
    </section>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <footer>paint callbacks · texture uploads · surface size are reported by the target adapter.</footer>
  </main>
</template>
