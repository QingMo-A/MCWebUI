# Direct CEF manual release acceptance

Status: **HARNESS READY / USER ACCEPTANCE PENDING**

This checklist is the final manual gate for the NeoForge Developer Preview. It
does not publish or modify Runtime R1, and it does not publish the MCWebUI mod.
Every run uses a unique directory below `build/manual-acceptance/`; it never
uses the player's normal `.minecraft` directory. Generated instances, logs,
screenshots and `acceptance-info.json` files are local evidence and must not be
committed.

## Harness

Run from the repository root with Windows PowerShell. The target-local
`mcwebui.dev.manualSetupAcceptance` hook is enabled only by this script. It
waits for the normal Direct compatibility probe, then opens the existing
`DirectCefRuntimeSetupScreen`; it does not bypass the release catalog, runtime
discovery, importer, validator, backend compatibility, or Direct rendering.

```powershell
$script = '.\scripts\direct-cef-runtime\prepare-manual-acceptance.ps1'
$runtimeZip = 'F:\path\to\mcwebui-direct-cef-runtime-cef-144.0.33-cb4715c-windows-x86_64-1.zip'

powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Scenario fresh-download
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Scenario cancel-retry
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Scenario offline-import -RuntimePackage $runtimeZip
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Scenario invalid-override
powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -Scenario installed-continue -RuntimePackage $runtimeZip
```

`offline-import` and `installed-continue` verify that the supplied ZIP is the
frozen Runtime R1 before doing anything else. Offline import leaves the ZIP in
its original external location and only pre-fills the Setup path. The installed
scenario invokes the existing LOCAL_FIXTURE downloader/importer proof to create
a validated standard installation; it never copies an unvalidated runtime
directory. Use `-PrepareOnly` to inspect generated metadata without launching
Minecraft, and `-SkipBuild` only after the relevant sources are already built.

Each invocation prints its isolated instance/game paths and writes
`acceptance-info.json` containing the source SHA, descriptor SHA, runtime ID,
expected install path, backend and scenario instructions. All manual result
fields begin as `NOT_RUN` and the script never changes them to PASS.

## Scenario expectations

| Scenario | Expected manual flow |
| --- | --- |
| `fresh-download` | Missing standard runtime; Download & Install is available; progress reaches installed; Continue opens Direct WebScreen. |
| `cancel-retry` | Real Runtime R1 download can be cancelled; cancellation settles cleanly; Retry and a new Download & Install can complete. |
| `offline-import` | Frozen ZIP path is prefilled; Import Package validates/extracts/publishes; Continue opens Direct WebScreen. |
| `invalid-override` | Exact invalid override and repair guidance are visible; download/import do not pretend to repair it; Close and Escape work. |
| `installed-continue` | Runtime is valid through standard discovery; installed state and Continue are available; Direct WebScreen opens. |

For the final Direct WebScreen portion, use the registered showcase/control
panel and verify the transparent background reveals the world, the opacity
slider works, mouse wheel and slider dragging work, keyboard input and
Bridge/RPC state work, Escape releases input and closes the Screen, and no
obvious stale frame remains.

## Manual results

Do not mark a row PASS unless a human actually observed it. Record resolution,
GUI scale and concise notes for any failure.

| Scenario / check | Expected | Observed | PASS / FAIL | Notes |
| --- | --- | --- | --- | --- |
| Fresh Setup | Setup opens in a new isolated instance | NOT RUN | NOT RUN | |
| Download button | Available only for repairable standard-runtime state | NOT RUN | NOT RUN | |
| Progress | Status and byte/extraction progress remain readable | NOT RUN | NOT RUN | |
| Cancel | Active operation cancels without poisoning final runtime | NOT RUN | NOT RUN | |
| Retry | Cancel/failure can be retried | NOT RUN | NOT RUN | |
| Offline import | Frozen ZIP validates and installs | NOT RUN | NOT RUN | |
| Invalid override | Override path, reason and repair guidance are clear | NOT RUN | NOT RUN | |
| Continue | Enabled only after valid discovery; opens requested WebScreen | NOT RUN | NOT RUN | |
| ESC / Close | Returns control without crash or stuck capture | NOT RUN | NOT RUN | |
| 1280x720 | No clipped/overlapping Setup text or controls | NOT RUN | NOT RUN | |
| 1920x1080 | No clipped/overlapping Setup text or controls | NOT RUN | NOT RUN | |
| Small window | Essential status/actions remain understandable | NOT RUN | NOT RUN | |
| Two GUI scales | Layout and pointer mapping remain usable | NOT RUN | NOT RUN | |
| Final WebScreen | Direct CEF surface is visible and current | NOT RUN | NOT RUN | |
| Transparency | World remains visible at partial opacity | NOT RUN | NOT RUN | |
| Input | Wheel, drag, text and buttons work | NOT RUN | NOT RUN | |
| Bridge | Connected state, RPC and Java state work | NOT RUN | NOT RUN | |

## Release boundary

Runtime R1 Release, tag, asset and production descriptor are immutable inputs
to this acceptance. The harness must not update them. Completion of this table
is necessary before publishing the Developer Preview mod, but completing it is
not itself authorization to create a mod tag, GitHub Release, Modrinth,
CurseForge or Maven publication.
