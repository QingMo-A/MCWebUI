<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { McBridgeError } from "@mcwebui/core";
import { useMcRpc, useMcState } from "@mcwebui/vue";

const { client, state: connectionState, error: connectionError, connected, invoke } = useMcRpc();
const counter = useMcState<number>("demo.counter", 0);
const activeTab = ref("overview");
const message = ref("hello from the showcase");
const pingResult = ref("No request yet");
const bridgeError = ref<{ code: string; message: string } | null>(null);
const inputText = ref("");
const notes = ref("Type English, numbers, or Chinese here. Try keyboard navigation and clipboard shortcuts.");
const checked = ref(true);
const choice = ref("bridge");
const enabled = ref(true);
const selectValue = ref("balanced");
const selectOpen = ref(false);
const selectRoot = ref<HTMLElement | null>(null);
const selectTrigger = ref<HTMLButtonElement | null>(null);
const selectOptions = [
  { value: "focused", label: "Focused" },
  { value: "balanced", label: "Balanced" },
  { value: "expressive", label: "Expressive" },
];
const rangeValue = ref(62);
const webscreenOpacity = ref(100);
const webscreenOpacityValue = computed(() => webscreenOpacity.value / 100);
const progress = ref(72);
const selectedSegment = ref("Vue");
const showModal = ref(false);
const toast = ref("");
const loading = ref(false);
const diagnostics = ref<Record<string, unknown>>({});
const transparentView = new URLSearchParams(window.location.search).get("view");
const transparentLab = transparentView === "transparent-lab" ||
  new URLSearchParams(window.location.search).has("transparent-lab") ||
  window.location.hash === "#transparent-lab";
// The root element owns the canvas background when the body is transparent.
// Mark it before the first Vue render so CEF's OSR surface keeps real alpha
// instead of propagating the normal showcase background through the canvas.
if (transparentLab) document.documentElement.classList.add("transparent-mode");
const labRange = ref(48);
const labChecked = ref(true);
const labChoice = ref("balanced");
const labText = ref("");
const labScroll = ref<HTMLElement | null>(null);
const labModal = ref(false);
const labInputEvents = ref(0);
function recordLabInput(kind = "control") {
  labInputEvents.value++;
  const state = { kind, range: labRange.value, checked: labChecked.value, choice: labChoice.value, text: labText.value, scrollTop: labScroll.value?.scrollTop ?? 0, modal: labModal.value };
  console.info(`MCWEBUI_INPUT ${JSON.stringify(state)}`);
}
function closeLabModal(event?: KeyboardEvent) {
  if (!event) {
    labModal.value = false;
    recordLabInput("modalClose");
    return;
  }
  if (event.key !== "Escape") return;
  if (labModal.value) {
    labModal.value = false;
    recordLabInput("modalEscape");
  } else {
    recordLabInput("escape");
  }
}
type AnimationMode = "css" | "direct" | "vue";
const animationMode = ref<AnimationMode>("css");
const animationRunning = ref(false);
const animationTarget = ref<HTMLElement | null>(null);
const animationPhase = ref(0);
const animationCallbacks = ref(0);
const animationRateHz = ref(0);
const animationMedianMs = ref(0);
const animationP95Ms = ref(0);
const animationMaxMs = ref(0);
const animationJankCount = ref(0);
// Compatibility aliases keep the existing diagnostics grid stable while the lab adds
// mode-specific smoothness metrics below it.
const rafRateHz = animationRateHz;
const rafFrames = animationCallbacks;
const animationModeLabel = computed(() => ({ css: "CSS compositor", direct: "Direct rAF", vue: "Vue reactive" }[animationMode.value]));
const scrollItems = Array.from({ length: 14 }, (_, index) => ({
  title: `Runtime signal ${String(index + 1).padStart(2, "0")}`,
  detail: index % 2 ? "state channel is idle" : "paint observer is ready",
}));
let toastTimer: number | undefined;
let animationHandle: number | undefined;
let animationDiagnosticTimer: number | undefined;
let animationWindowStart = 0;
let animationWindowFrames = 0;
let animationLastTimestamp = 0;
let animationJankTotal = 0;
let directPhase = 0;
let animationTravel = 0;
const animationDeltas: number[] = [];
const ANIMATION_SAMPLE_LIMIT = 240;

const statusLabel = computed(() => ({ disconnected: "Disconnected", connecting: "Connecting", connected: "Connected", error: "Error" }[connectionState.value]));
const statusClass = computed(() => `status-${connectionState.value}`);
const displayError = computed(() => bridgeError.value ?? (connectionError.value ? { code: connectionError.value.code, message: connectionError.value.message } : null));
const runtimeRows = computed(() => [
  ["Target", diagnostics.value.targetId ?? "Waiting for runtime"],
  ["Loader", diagnostics.value.loader ?? "—"],
  ["Minecraft", diagnostics.value.minecraftVersion ?? "—"],
  ["Browser", diagnostics.value.browserBackend ? `${diagnostics.value.browserBackend} ${diagnostics.value.browserVersion ?? ""}` : "—"],
  ["Browser viewport", diagnostics.value.browserViewportWidth ? `${diagnostics.value.browserViewportWidth} × ${diagnostics.value.browserViewportHeight} px` : "—"],
  ["Viewport mode", diagnostics.value.viewportMode ?? "—"],
  ["GUI scale", diagnostics.value.guiScale ?? "—"],
]);
const selectLabel = computed(() => selectOptions.find((option) => option.value === selectValue.value)?.label ?? "Select an option");

async function pingJava() {
  bridgeError.value = null;
  try {
    const response = await invoke<{ message?: string; timestamp?: number; echo?: unknown }>("demo.ping", { message: message.value });
    pingResult.value = `${response.message ?? "pong"} · ${String(response.echo ?? message.value)} · ${response.timestamp ?? "—"}`;
    notify("Ping crossed the CEF bridge");
  } catch (cause) { setError(cause); }
}
async function incrementJavaState() {
  bridgeError.value = null;
  try { await invoke("demo.counter.increment", { amount: 1 }); notify("Java published a new counter value"); }
  catch (cause) { setError(cause); }
}
async function triggerBridgeError() {
  bridgeError.value = null;
  try { await invoke("demo.error", {}); }
  catch (cause) { setError(cause); }
}
async function refreshDiagnostics() {
  if (!connected.value) return;
  try { diagnostics.value = await invoke<Record<string, unknown>>("runtime.diagnostics", {}); }
  catch (cause) { setError(cause); }
}
function resetAnimationMetrics() {
  animationCallbacks.value = 0;
  animationRateHz.value = 0;
  animationMedianMs.value = 0;
  animationP95Ms.value = 0;
  animationMaxMs.value = 0;
  animationJankCount.value = 0;
  animationPhase.value = 0;
  animationWindowStart = 0;
  animationWindowFrames = 0;
  animationLastTimestamp = 0;
  animationJankTotal = 0;
  animationDeltas.length = 0;
  directPhase = 0;
}
function percentile(values: number[], fraction: number) {
  if (!values.length) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * fraction) - 1));
  return sorted[index];
}
function publishAnimationWindow(timestamp: number) {
  const elapsed = timestamp - animationWindowStart;
  if (elapsed < 1000) return;
  animationRateHz.value = animationWindowFrames * 1000 / elapsed;
  animationCallbacks.value += animationWindowFrames;
  animationMedianMs.value = percentile(animationDeltas, 0.5);
  animationP95Ms.value = percentile(animationDeltas, 0.95);
  animationMaxMs.value = animationDeltas.length ? Math.max(...animationDeltas) : 0;
  animationJankCount.value = animationJankTotal;
  animationWindowStart = timestamp;
  animationWindowFrames = 0;
  animationJankTotal = 0;
}
function animationTick(timestamp: number) {
  if (!animationRunning.value) return;
  if (animationLastTimestamp > 0) {
    const delta = timestamp - animationLastTimestamp;
    animationDeltas.push(delta);
    if (animationDeltas.length > ANIMATION_SAMPLE_LIMIT) animationDeltas.shift();
    const rollingMedian = percentile(animationDeltas, 0.5);
    if (rollingMedian > 0 && delta > Math.max(rollingMedian * 1.5, rollingMedian + 8)) animationJankTotal++;
  }
  animationLastTimestamp = timestamp;
  animationWindowFrames++;
  if (animationMode.value === "direct") {
    directPhase = (directPhase + 0.7) % 100;
    if (animationTarget.value) animationTarget.value.style.transform = `translate3d(${animationTravel * directPhase / 100}px, 0, 0)`;
  } else if (animationMode.value === "vue") {
    animationPhase.value = (animationPhase.value + 0.7) % 100;
  }
  publishAnimationWindow(timestamp);
  animationHandle = window.requestAnimationFrame(animationTick);
}
function startAnimationDiagnostics() {
  void refreshDiagnostics();
  animationDiagnosticTimer = window.setInterval(() => { void refreshDiagnostics(); }, 1000);
}
function stopAnimation() {
  if (animationHandle !== undefined) window.cancelAnimationFrame(animationHandle);
  animationHandle = undefined;
  if (animationDiagnosticTimer !== undefined) window.clearInterval(animationDiagnosticTimer);
  animationDiagnosticTimer = undefined;
  animationRunning.value = false;
  if (animationTarget.value && animationMode.value === "direct") animationTarget.value.style.transform = "";
}
function startAnimation() {
  if (animationRunning.value) return;
  resetAnimationMetrics();
  animationTravel = Math.max(0, (animationTarget.value?.parentElement?.clientWidth ?? 16) - 16);
  animationRunning.value = true;
  startAnimationDiagnostics();
  animationHandle = window.requestAnimationFrame(animationTick);
}
function setAnimationMode(mode: AnimationMode) {
  if (mode === animationMode.value) return;
  const wasRunning = animationRunning.value;
  stopAnimation();
  animationMode.value = mode;
  if (wasRunning) startAnimation();
}
function setError(cause: unknown) {
  if (cause instanceof McBridgeError) bridgeError.value = { code: cause.code, message: cause.message };
  else bridgeError.value = { code: "CLIENT_ERROR", message: cause instanceof Error ? cause.message : String(cause) };
}
function notify(messageText: string) {
  toast.value = messageText;
  if (toastTimer) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => { toast.value = ""; }, 2600);
}
function toggleSelect() { selectOpen.value = !selectOpen.value; }
function chooseSelect(value: string) {
  selectValue.value = value;
  selectOpen.value = false;
  selectTrigger.value?.focus();
}
function closeSelectOnOutside(event: PointerEvent) {
  if (selectOpen.value && !selectRoot.value?.contains(event.target as Node)) selectOpen.value = false;
}
function closeSelectOnEscape(event: KeyboardEvent) {
  if (event.key === "Escape" && selectOpen.value) {
    event.preventDefault();
    selectOpen.value = false;
    selectTrigger.value?.focus();
  }
}
function stopAnimationWhenHidden() {
  // CEF WasHidden drives the Page Visibility API. If the user leaves the F8
  // screen while the lab is running, stop its rAF and diagnostics interval so
  // the retained warm browser returns to its near-idle state.
  if (document.hidden) stopAnimation();
}
watch(activeTab, (tab) => {
  if (tab === "runtime") void refreshDiagnostics();
});
onMounted(async () => {
  if (transparentLab) document.body.classList.add("transparent-mode");
  document.addEventListener("pointerdown", closeSelectOnOutside);
  document.addEventListener("keydown", closeSelectOnEscape);
  document.addEventListener("visibilitychange", stopAnimationWhenHidden);
  try {
    await client.connect();
    // Diagnostics are intentionally sampled on connect, when the Runtime tab is opened, or
    // by the explicit refresh controls.  A timer here would mutate the DOM continuously and
    // make an otherwise idle CEF surface generate paint callbacks forever.
    await refreshDiagnostics();
  } catch { /* connectionState/connectionError expose the failure to the UI */ }
});
onBeforeUnmount(() => {
  document.body.classList.remove("transparent-mode");
  document.documentElement.classList.remove("transparent-mode");
  document.removeEventListener("pointerdown", closeSelectOnOutside);
  document.removeEventListener("keydown", closeSelectOnEscape);
  document.removeEventListener("visibilitychange", stopAnimationWhenHidden);
  if (toastTimer) window.clearTimeout(toastTimer);
  stopAnimation();
});
</script>

<template>
  <main v-if="transparentLab" class="transparent-lab" @keydown="closeLabModal">
    <div data-test="world-reveal" class="world-reveal" aria-label="World reveal aperture"></div>
    <header class="transparent-lab-header">
      <div><p class="eyebrow">MCWebUI · Direct CEF acceptance</p><h1>Transparent WebScreen Lab</h1><p>Real HTML controls over a moving GPU background. No JavaScript-generated input.</p></div>
      <span class="lab-live">INTERACTIVE</span>
    </header>
    <section class="alpha-grid" aria-label="Alpha patches">
      <article v-for="patch in [{alpha:0,label:'0%',test:'alpha0'},{alpha:.25,label:'25%',test:'alpha25'},{alpha:.5,label:'50%',test:'alpha50'},{alpha:.75,label:'75%',test:'alpha75'},{alpha:1,label:'100%',test:'alpha100'}]" :key="patch.label" :data-test="patch.test" class="alpha-patch" :style="{ backgroundColor: `rgba(214, 74, 88, ${patch.alpha})` }">
        <i class="alpha-sample" aria-hidden="true"></i><strong>{{ patch.label }}</strong><small>fixed RGB</small>
      </article>
    </section>
    <section class="transparent-panel" aria-label="Native HTML controls">
      <div class="lab-panel-heading"><div><p class="eyebrow">Native controls</p><h2>Operate the browser surface</h2></div><span class="lab-readout">events {{ labInputEvents }}</span></div>
      <div class="lab-control-grid">
        <label>Range <output>{{ labRange }}</output><input data-test="range" v-model="labRange" type="range" min="0" max="100" @input="recordLabInput('range')" /></label>
        <button data-test="button" class="lab-button" type="button" @click="recordLabInput('button')">Native button</button>
        <label class="lab-check"><input data-test="checkbox" v-model="labChecked" type="checkbox" @change="recordLabInput('checkbox')" /> Checkbox</label>
        <label>Select <select data-test="select" v-model="labChoice" @change="recordLabInput('select')"><option value="focused">Focused</option><option value="balanced">Balanced</option><option value="expressive">Expressive</option></select></label>
        <label class="lab-text">Text input<input data-test="text" v-model="labText" type="text" placeholder="Type basic Latin text" @focus="recordLabInput('focus')" @input="recordLabInput('text')" /></label>
      </div>
      <div class="lab-lower-grid">
        <div data-test="scroll" ref="labScroll" class="lab-scroll" tabindex="0" aria-label="Scrollable acceptance area" @scroll="recordLabInput('scroll')"><p v-for="index in 18" :key="index">Runtime signal {{ String(index).padStart(2, "0") }} · wheel and scrollbar target</p></div>
        <div class="lab-actions"><button data-test="modal" class="lab-button" type="button" @click="labModal = true; recordLabInput('modal')">Open modal</button><svg class="alpha-svg" viewBox="0 0 64 44" aria-label="SVG alpha sample"><circle cx="22" cy="22" r="18" fill="rgba(105,230,173,.45)"/><circle cx="42" cy="22" r="18" fill="rgba(140,169,255,.65)"/></svg></div>
      </div>
    </section>
    <div v-if="labModal" class="lab-modal-backdrop" @click.self="closeLabModal()"><section class="lab-modal" role="dialog" aria-modal="true" aria-labelledby="lab-modal-title"><h2 id="lab-modal-title">Transparent modal</h2><p>Opacity, transform, rounded corners, shadow, and focus are all browser-rendered.</p><button data-test="modal-close" class="lab-button" type="button" @click="closeLabModal()">Close</button></section></div>
    <p class="transparent-lab-footer">ESC closes the modal or the native window · IME is not tested in this proof</p>
  </main>
  <main v-else class="shell" :style="{ '--webscreen-opacity': webscreenOpacityValue }">
    <label class="webscreen-opacity-control" for="webscreen-opacity">
      <span>WebScreen opacity</span>
      <strong>{{ webscreenOpacity }}%</strong>
      <input id="webscreen-opacity" v-model.number="webscreenOpacity" data-test="webscreen-opacity" type="range" min="0" max="100" step="1" />
    </label>
    <header class="topbar">
      <div class="brand"><span class="brand-mark">M</span><div><p class="eyebrow">MCWebUI</p><h1>Runtime showcase</h1></div></div>
      <div class="topbar-meta"><span class="live-dot" :class="statusClass"></span><span>{{ statusLabel }}</span><span class="separator">·</span><span>{{ diagnostics.loader ?? "Target adapter" }}</span></div>
    </header>

    <div class="workspace">
      <aside class="sidebar" aria-label="Showcase sections">
        <p class="side-label">Explore</p>
        <button v-for="item in [{id:'overview',label:'Overview',icon:'◈'},{id:'controls',label:'Controls',icon:'◌'},{id:'bridge',label:'Bridge Lab',icon:'↔'},{id:'runtime',label:'Runtime',icon:'⌁'},{id:'input',label:'Input Lab',icon:'⌨'}]" :key="item.id" class="nav-item" :class="{ active: activeTab === item.id }" @click="activeTab = item.id">
          <span class="nav-icon">{{ item.icon }}</span>{{ item.label }}
        </button>
        <div class="sidebar-spacer"></div>
        <div class="side-note"><span class="status-dot" :class="statusClass"></span><div><strong>{{ statusLabel }}</strong><small>CEF session</small></div></div>
      </aside>

      <section class="content">
        <section class="hero panel">
          <div><p class="eyebrow">One shared Vue bundle · live target data</p><h2>Web UI that feels at home in Minecraft.</h2><p class="hero-copy">A compact integration laboratory for controls, browser rendering, and the Java bridge. Everything below is regular semantic HTML.</p></div>
          <div class="hero-orbit" aria-hidden="true"><span></span><span></span><span></span></div>
        </section>

        <div v-if="displayError" class="error-banner" role="alert"><span class="error-code">{{ displayError.code }}</span><span>{{ displayError.message }}</span><button class="icon-button" aria-label="Dismiss error" @click="bridgeError = null">×</button></div>

        <template v-if="activeTab === 'overview'">
          <div class="metric-grid">
            <article class="metric-card panel"><span class="metric-kicker">Connection</span><strong :class="statusClass">{{ statusLabel }}</strong><small>Reactive Vue binding</small></article>
            <article class="metric-card panel"><span class="metric-kicker">Java state</span><strong>{{ counter }}</strong><small>demo.counter · latest value</small></article>
            <article class="metric-card panel"><span class="metric-kicker">Paint callbacks</span><strong>{{ diagnostics.paintCallbacks ?? "—" }}</strong><small>Native texture path</small></article>
          </div>
          <div class="two-column">
            <article class="panel section-card"><div class="section-heading"><div><p class="eyebrow">Bridge Lab</p><h3>Call Java, watch state move</h3></div><span class="badge badge-green">LIVE</span></div><p class="muted">Try a typed RPC and a Java-published counter update without refreshing the page.</p><div class="inline-form"><label for="overview-message">Message</label><input id="overview-message" v-model="message" autocomplete="off" @keyup.enter="pingJava" /><button class="button primary" @click="pingJava">Ping Java</button></div><output class="result-line">{{ pingResult }}</output><div class="counter-row"><span class="counter-value">{{ counter }}</span><button class="button secondary" @click="incrementJavaState">Increment state</button></div></article>
            <article class="panel section-card"><div class="section-heading"><div><p class="eyebrow">Runtime identity</p><h3>Actual adapter diagnostics</h3></div><button class="icon-button" aria-label="Refresh diagnostics" @click="refreshDiagnostics">↻</button></div><dl class="detail-list"><div v-for="row in runtimeRows" :key="row[0]"><dt>{{ row[0] }}</dt><dd>{{ row[1] }}</dd></div></dl></article>
          </div>
        </template>

        <template v-else-if="activeTab === 'controls'">
          <div class="section-heading page-heading"><div><p class="eyebrow">Component sampler</p><h2>Controls with real interaction states</h2><p class="muted">Experimental showcase composition, not a frozen component API.</p></div></div>
          <div class="control-grid">
            <article class="panel section-card"><p class="eyebrow">Actions</p><h3>Buttons</h3><div class="button-stack"><button class="button primary" @click="notify('Primary action received')">Primary action</button><button class="button secondary">Secondary</button><button class="button danger" @click="triggerBridgeError">Danger / error</button><button class="button secondary" :disabled="!enabled">Disabled</button><button class="button primary" :disabled="loading" @click="loading = !loading">{{ loading ? 'Loading…' : 'Toggle loading' }}</button></div></article>
            <article class="panel section-card"><p class="eyebrow">Forms</p><h3>Inputs</h3><label class="field">Text input<input v-model="message" placeholder="Your message" /></label><label class="field">Textarea<textarea v-model="notes" rows="3"></textarea></label><div class="check-row"><label class="check"><input v-model="checked" type="checkbox" /> Enable notifications</label><label class="check"><input v-model="enabled" type="checkbox" /> Enable actions</label></div></article>
            <article class="panel section-card"><p class="eyebrow">Choice</p><h3>Selection</h3><div class="field"><span>Select</span><div ref="selectRoot" class="custom-select"><button ref="selectTrigger" type="button" class="select-trigger" aria-haspopup="listbox" :aria-expanded="selectOpen" aria-controls="playground-select-options" @click="toggleSelect"><span>{{ selectLabel }}</span><span class="select-chevron" aria-hidden="true">⌄</span></button><div v-if="selectOpen" id="playground-select-options" class="select-menu" role="listbox" aria-label="Select an emphasis"><button v-for="option in selectOptions" :key="option.value" type="button" class="select-option" role="option" :aria-selected="selectValue === option.value" @click="chooseSelect(option.value)">{{ option.label }}</button></div></div></div><div class="radio-list"><label class="check"><input v-model="choice" type="radio" value="bridge" /> Bridge first</label><label class="check"><input v-model="choice" type="radio" value="visual" /> Visual first</label></div><label class="switch-row"><span>Live updates</span><button class="switch" :class="{ on: enabled }" role="switch" :aria-checked="enabled" @click="enabled = !enabled"><span></span></button></label></article>
            <article class="panel section-card"><p class="eyebrow">Feedback</p><h3>Progress & status</h3><div class="progress-label"><span>Bundle readiness</span><strong>{{ progress }}%</strong></div><progress :value="progress" max="100">{{ progress }}%</progress><label class="field">Range <input v-model="rangeValue" type="range" min="0" max="100" /></label><div class="badge-row"><span class="badge badge-green">Connected</span><span class="badge badge-amber">Preview</span><span class="badge badge-red">Error</span></div><div class="segmented" role="tablist"><button v-for="segment in ['Vue','Core','Browser']" :key="segment" :class="{ selected: selectedSegment === segment }" role="tab" @click="selectedSegment = segment">{{ segment }}</button></div></article>
            <article class="panel section-card scroll-card"><div class="section-heading"><div><p class="eyebrow">Scrollable region</p><h3>Signal feed</h3></div><span class="badge">14 items</span></div><ul class="signal-list"><li v-for="item in scrollItems" :key="item.title"><span class="signal-icon">·</span><div><strong>{{ item.title }}</strong><small>{{ item.detail }}</small></div></li></ul></article>
            <article class="panel section-card"><p class="eyebrow">Overlay</p><h3>Dialog & toast</h3><p class="muted">Feedback stays in context and remains keyboard reachable.</p><div class="button-row"><button class="button secondary" @click="showModal = true">Open dialog</button><button class="button secondary" @click="notify('This is a lightweight toast')">Show toast</button></div></article>
          </div>
        </template>

        <template v-else-if="activeTab === 'bridge'">
          <div class="section-heading page-heading"><div><p class="eyebrow">Real browser transport</p><h2>Bridge Lab</h2><p class="muted">Every action below crosses <code>window.__MCWEBUI_BRIDGE__</code> through the native browser query channel.</p></div><span class="badge badge-green">{{ statusLabel }}</span></div>
          <div class="two-column bridge-grid"><article class="panel section-card"><div class="lab-number">01</div><h3>Ping</h3><p class="muted">Calls <code>demo.ping</code> and renders Java's pong, timestamp, and echo.</p><label class="field">Payload<input v-model="message" /></label><button class="button primary" @click="pingJava">Send ping</button><output class="result-box">{{ pingResult }}</output></article><article class="panel section-card"><div class="lab-number">02</div><h3>Reactive counter</h3><p class="muted">Subscribe to <code>demo.counter</code>; Java publishes the latest value.</p><div class="big-counter">{{ counter }}</div><button class="button primary" @click="incrementJavaState">Increment Java state</button><span class="muted tiny">No polling · state update message</span></article><article class="panel section-card"><div class="lab-number">03</div><h3>Structured error</h3><p class="muted">The safe demo handler throws and becomes a typed error envelope.</p><button class="button danger" @click="triggerBridgeError">Trigger error</button><div v-if="bridgeError" class="error-detail"><strong>{{ bridgeError.code }}</strong><span>{{ bridgeError.message }}</span></div></article></div>
        </template>

        <template v-else-if="activeTab === 'runtime'">
          <div class="section-heading page-heading"><div><p class="eyebrow">Target adapter telemetry</p><h2>Runtime diagnostics</h2><p class="muted">Sampled on connect, when opened, or while Animation Lab is running.</p></div><div class="button-row"><button class="button secondary" @click="refreshDiagnostics">Refresh now</button><button class="button secondary" @click="animationRunning ? stopAnimation() : startAnimation()">{{ animationRunning ? 'Stop lab' : 'Start lab' }}</button></div></div>
          <article class="panel diagnostics-panel">
            <div class="animation-lab-heading"><div><p class="eyebrow">Animation Lab</p><h3>One clock, three browser paths</h3><p class="muted tiny">Choose one mode at a time. CSS and direct rAF avoid Vue state writes in the hot path; Vue reactive is an intentional stress path.</p></div><span class="badge" :class="{ 'badge-green': animationRunning }">{{ animationRunning ? 'RUNNING' : 'IDLE' }}</span></div>
            <div class="animation-controls" role="group" aria-label="Animation Lab controls"><button class="button secondary" :class="{ selected: animationMode === 'css' }" @click="setAnimationMode('css')">CSS compositor</button><button class="button secondary" :class="{ selected: animationMode === 'direct' }" @click="setAnimationMode('direct')">Direct rAF</button><button class="button secondary" :class="{ selected: animationMode === 'vue' }" @click="setAnimationMode('vue')">Vue reactive</button></div>
            <div class="raf-stage" :class="{ running: animationRunning, 'css-mode': animationMode === 'css' }" aria-label="Animation Lab visual probe"><span><i ref="animationTarget" :style="animationMode === 'vue' ? { transform: `translate3d(${animationTravel * animationPhase / 100}px, 0, 0)` } : undefined"></i></span></div>
            <div class="animation-summary"><strong>{{ animationModeLabel }}</strong><span>{{ animationRunning ? 'Measuring' : 'Press Start lab to measure' }}</span></div>
            <div class="diagnostics-grid"><div v-for="row in runtimeRows" :key="row[0]" class="diagnostic-cell"><span>{{ row[0] }}</span><strong>{{ row[1] }}</strong></div></div>
            <div class="diagnostics-grid animation-metrics-grid">
              <div class="diagnostic-cell"><span>Callback rate</span><strong>{{ animationRateHz.toFixed(1) }} Hz</strong></div>
              <div class="diagnostic-cell"><span>Median frame</span><strong>{{ animationMedianMs.toFixed(1) }} ms</strong></div>
              <div class="diagnostic-cell"><span>P95 frame</span><strong>{{ animationP95Ms.toFixed(1) }} ms</strong></div>
              <div class="diagnostic-cell"><span>Max frame</span><strong>{{ animationMaxMs.toFixed(1) }} ms</strong></div>
              <div class="diagnostic-cell"><span>Jank frames</span><strong>{{ animationJankCount }}</strong></div>
            </div>
            <div class="diagnostics-grid secondary-grid">
              <div class="diagnostic-cell"><span>Frame mode</span><strong>{{ diagnostics.frameMode ?? "BACKEND_DEFAULT" }}</strong></div><div class="diagnostic-cell"><span>Proof runtime</span><strong>{{ diagnostics.proofRuntime ?? "STOCK" }}</strong></div><div class="diagnostic-cell"><span>Game render signals/s</span><strong>{{ Number(diagnostics.gameSignalsPerSecond ?? 0).toFixed(1) }} Hz</strong></div><div class="diagnostic-cell"><span>External BeginFrames/s</span><strong>{{ Number(diagnostics.externalBeginFramesPerSecond ?? 0).toFixed(1) }} Hz</strong></div><div class="diagnostic-cell"><span>Browser rAF rate</span><strong>{{ rafRateHz.toFixed(1) }} Hz</strong></div><div class="diagnostic-cell"><span>Browser rAF callbacks</span><strong>{{ rafFrames }}</strong></div><div class="diagnostic-cell"><span>Paint callbacks</span><strong>{{ diagnostics.paintCallbacks ?? "—" }}</strong></div><div class="diagnostic-cell"><span>Paint rate</span><strong>{{ diagnostics.paintRateHz ? `${Number(diagnostics.paintRateHz).toFixed(1)} Hz` : "—" }}</strong></div><div class="diagnostic-cell"><span>Estimated paint bytes</span><strong>{{ diagnostics.estimatedPaintBytes ?? "—" }}</strong></div><div class="diagnostic-cell"><span>Frame capability</span><strong>{{ diagnostics.externalFramePacing ? "EXTERNAL_BEGIN_FRAME" : "UNSUPPORTED" }}</strong></div><div class="diagnostic-cell"><span>Framebuffer</span><strong>{{ diagnostics.framebufferWidth ? `${diagnostics.framebufferWidth} × ${diagnostics.framebufferHeight} px` : "—" }}</strong></div><div class="diagnostic-cell"><span>View state</span><strong>{{ diagnostics.viewState ?? "—" }}</strong></div><div class="diagnostic-cell"><span>Session state</span><strong>{{ diagnostics.sessionState ?? "—" }}</strong></div>
            </div>
          </article>
        </template>

        <template v-else>
          <div class="section-heading page-heading"><div><p class="eyebrow">Input routing</p><h2>Input Lab</h2><p class="muted">Focus, selection, keyboard shortcuts, wheel, and text routing are forwarded by the active target screen.</p></div></div>
          <div class="two-column"><article class="panel section-card input-lab"><h3>Single-line input</h3><input v-model="inputText" class="large-input" aria-label="Single line input" placeholder="Try English, numbers, 中文输入法" /><p class="input-readout">{{ inputText || "Nothing typed yet" }}</p><h3>Multiline textarea</h3><textarea v-model="notes" rows="7" aria-label="Multiline input"></textarea><p class="muted tiny">Try Backspace, Delete, Arrow keys, Tab, Shift+Tab, Ctrl+A/C/V. Chinese IME: implemented in the input model, not manually verified in this environment.</p></article><article class="panel section-card"><p class="eyebrow">Routing checklist</p><h3>What to try</h3><ul class="checklist"><li><span>01</span><div><strong>Mouse & wheel</strong><small>Hover, click, and scroll this panel.</small></div></li><li><span>02</span><div><strong>Keyboard</strong><small>Tab through controls; Escape closes the screen.</small></div></li><li><span>03</span><div><strong>Clipboard</strong><small>Use Ctrl+A, Ctrl+C, and Ctrl+V inside the fields.</small></div></li><li><span>04</span><div><strong>Composition</strong><small>Native char events are forwarded; IME remains not manually verified.</small></div></li></ul></article></div>
        </template>
        <footer class="footer">MCWebUI · common semantics · one shared target-neutral bundle</footer>
      </section>
    </div>
    <div v-if="toast" class="toast" role="status"><span class="toast-mark">✓</span>{{ toast }}</div>
    <div v-if="showModal" class="modal-backdrop" @click.self="showModal = false"><section class="modal panel" role="dialog" aria-modal="true" aria-labelledby="modal-title"><button class="icon-button modal-close" aria-label="Close dialog" @click="showModal = false">×</button><span class="modal-icon">✦</span><h2 id="modal-title">A focused overlay</h2><p class="muted">This dialog is rendered by the same Vue bundle and stays inside the browser surface.</p><button class="button primary" @click="showModal = false">Continue</button></section></div>
  </main>
</template>
