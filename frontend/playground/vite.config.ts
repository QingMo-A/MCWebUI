import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [vue()],
  base: "./",
  resolve: {
    alias: {
      "@mcwebui/core": fileURLToPath(new URL("../packages/core/src", import.meta.url)),
      "@mcwebui/vue": fileURLToPath(new URL("../packages/vue/src", import.meta.url)),
    },
  },
  build: { outDir: "dist", emptyOutDir: true },
});
