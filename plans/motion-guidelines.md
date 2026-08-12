# MCWebUI motion guidelines

MCWebUI keeps Vue as the authoring and state-composition layer, while the browser owns ordinary visual interpolation.

## Choose the animation path

- Use CSS transitions/`@keyframes` for hover, press, open/close, slide, fade, scale, and navigation indicators.
- Use `transform` (preferably `translate3d`) and `opacity` for frequently animated elements.
- Use direct `requestAnimationFrame` plus an imperative style write only for bespoke continuous motion that cannot be expressed in CSS.
- Keep Vue reactive rAF as an explicit stress path, not the default way to animate every property.

The Runtime Animation Lab compares these three paths one at a time. It reports rAF rate, median/p95/max frame interval, and jank. A jank is a sampled interval greater than both 1.5× the rolling median and the median plus 8 ms. Samples are bounded to 240 intervals, and diagnostics are refreshed at approximately 1 Hz only while the lab is running.

## Keep bridge traffic semantic

Java should publish state transitions once (for example, `balance = 1200`), then let Vue and browser-native animation interpolate the presentation. Do not send one bridge update per visual frame. GAME_SYNC is a host paint clock for proof and diagnostics; it is not a replacement for local browser animation.

## Avoid layout work in hot loops

Do not continuously animate `width`, `height`, `top`, `left`, margins, padding, large shadows, blur, or backdrop filters without profiling evidence. Static use remains fine. Respect `prefers-reduced-motion` for nonessential effects.

CEF OSR still has its own scheduling and paint costs. A smooth CSS/direct-rAF result with a lower Vue-reactive result is useful guidance about the division of responsibilities, not evidence that Vue is unsuitable for application state.
