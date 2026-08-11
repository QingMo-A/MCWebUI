# MCWebUI

MCWebUI is a Minecraft web UI application framework: build modern in-game interfaces with web frontend technologies while keeping Minecraft and loader APIs behind version-specific adapters.

The project is intentionally **common-first from day one**. UI runtime contracts, bridge protocols, state synchronization, security policy, resource loading semantics, and performance policy belong in `common`; Minecraft/loader integration belongs in `targets/*`.

## Repository layout

```text
common/                       Java runtime contracts and loader-independent logic
targets/forge-1.20.1/         Forge 1.20.1 adapter
targets/neoforge-1.21.1/      NeoForge 1.21.1 adapter
frontend/packages/core/       Framework-independent TypeScript client
frontend/packages/vue/        Vue bindings
frontend/packages/components/ MC-oriented web components
frontend/playground/          Development/demo application
gradle/mcwebui-targets.json   Supported-target registry
plans/                        Architecture and execution plans
```

## Branches

- `main`: stable checkpoints and releases.
- `bridge`: active multiversion integration and development.

The first implementation milestone is a bundled Vue page rendered inside a Minecraft screen with input, typed Java/JavaScript RPC, Java-to-web reactive state, and offline JAR resources.

See `plans/` on the `bridge` branch for the architecture and execution plan.
