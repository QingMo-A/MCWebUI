[CmdletBinding()]
param(
    [string]$CefRoot = '',
    [string]$BuildRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-runtime-build'),
    [string]$RuntimeRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-runtime'),
    [string]$CacheRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-cache'),
    [int]$DurationMs = 90000,
    [int]$StartupTimeoutMs = 120000,
    [int]$Port = 18765,
    [int]$TargetHz = 60,
    [switch]$ExternalPageServer,
    [string]$LogPath = (Join-Path $env:TEMP 'mcwebui-direct-cef-neoforge-run.log'),
    [switch]$SkipBuild,
    [switch]$DirectOnly,
    [switch]$AutoOpen,
    [switch]$CollectEvidence
)

$ErrorActionPreference = 'Stop'
if ($DurationMs -lt 0) { throw 'DurationMs must be zero (manual run) or positive (bounded run)' }
if ($StartupTimeoutMs -lt 1000) { throw 'StartupTimeoutMs must be at least 1000' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($CefRoot)) {
    $CefRoot = $env:MCWEBUI_CEF_ROOT
}
if ([string]::IsNullOrWhiteSpace($CefRoot) -or -not (Test-Path -LiteralPath $CefRoot)) {
    throw 'Pass -CefRoot or set MCWEBUI_CEF_ROOT to the pinned CEF144 SDK root'
}

if (-not $SkipBuild) {
    & (Join-Path $repo 'gradlew.bat') frontendBuild --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'frontendBuild failed' }
    & (Join-Path $repo 'gradlew.bat') ':targets:neoforge-1.21.1:compileJava' --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'NeoForge compileJava failed' }
    & (Join-Path $repo 'scripts\direct-cef-proof\build-direct-cef-runtime.ps1') `
        -CefRoot $CefRoot -BuildRoot $BuildRoot
    if ($LASTEXITCODE -ne 0) { throw 'Direct CEF runtime build failed' }
}

$bin = (Resolve-Path (Join-Path $BuildRoot 'bin')).Path
$native = Join-Path $bin 'mcwebui-direct-cef.dll'
$helper = Join-Path $bin 'mcwebui-cef-helper.exe'
foreach ($required in @($native, $helper, (Join-Path $bin 'libcef.dll'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Direct CEF runtime artifact missing: $required" }
}
New-Item -ItemType Directory -Force -Path $RuntimeRoot, $CacheRoot | Out-Null
$dist = (Resolve-Path (Join-Path $repo 'frontend\playground\dist')).Path
$python = if ($ExternalPageServer) { (Get-Command python.exe -ErrorAction Stop).Source } else { $null }
$server = $null
$client = $null
$wrapperPid = $null
$gamePid = $null
$errorLog = "$LogPath.err"
$metaLog = "$LogPath.meta"
$baselineGamePids = @(
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*net.neoforged.devlaunch.Main*' } |
        ForEach-Object { [int]$_.ProcessId }
)
if ($baselineGamePids.Count -gt 0) {
    throw "A NeoForge development client is already running (PID(s): $($baselineGamePids -join ',')). Close it before starting the Direct CEF runner."
}

function Get-RunnerGameProcess {
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.CommandLine -like '*net.neoforged.devlaunch.Main*' -and
            $baselineGamePids -notcontains [int]$_.ProcessId
        } |
        Select-Object -First 1)[0]
}

function Stop-RunnerProcesses {
    if ($gamePid) {
        Stop-Process -Id $gamePid -Force -ErrorAction SilentlyContinue
    }
    if ($client -and -not $client.HasExited) {
        Stop-Process -Id $wrapperPid -Force -ErrorAction SilentlyContinue
    }
    $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $remaining = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object {
                $_.CommandLine -like '*net.neoforged.devlaunch.Main*' -and
                $baselineGamePids -notcontains [int]$_.ProcessId
            })
        if (-not $remaining) { return }
        foreach ($process in $remaining) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $cleanupDeadline)
    throw "Direct CEF NeoForge run left a Java process after cleanup: $($remaining.ProcessId -join ',')"
}

function Write-RunnerEvidence {
    if (-not $CollectEvidence) { return }
    $allLines = @()
    foreach ($path in @($LogPath, $errorLog)) {
        if (Test-Path -LiteralPath $path) { $allLines += @(Get-Content -LiteralPath $path -ErrorAction SilentlyContinue) }
    }
    $snapshots = @()
    $latest = $null
    foreach ($line in $allLines) {
        if ($line -match '\[MCWebUI\] Direct CEF runtime evidence checkpoint=([^ ]+) diagnostics=(\{.*\})') {
            $snapshot = [ordered]@{
                checkpoint = $Matches[1]
                diagnosticsJson = $Matches[2]
            }
            try {
                $latest = $Matches[2] | ConvertFrom-Json
            } catch {
                $snapshot.parseError = $_.Exception.Message
            }
            $snapshots += [pscustomobject]$snapshot
        }
    }
    # Match only the one-line native markers. Diagnostics JSON also contains
    # checkpoint key names and must not be copied back into this compact list.
    $markers = @($allLines | ForEach-Object {
        if ($_ -match '(DIRECT_CEF_MC_(?:GL_CONTEXT_READY|INTEROP_READY|MAILBOX_REGISTERED|FIRST_LEASE|FIRST_DRAW)(?: .*)?)$') {
            $Matches[1]
        }
    } | Select-Object -Unique)
    $hasCounters = $null -ne $latest -and
        $null -ne $latest.renderBeginSuccesses -and $null -ne $latest.renderEnds -and
        $null -ne $latest.interopLocks -and $null -ne $latest.interopUnlocks
    $leaseBalanced = $hasCounters -and ([uint64]$latest.renderBeginSuccesses -eq [uint64]$latest.renderEnds)
    $interopBalanced = $hasCounters -and ([uint64]$latest.interopLocks -eq [uint64]$latest.interopUnlocks)
    $failuresZero = $hasCounters -and
        ([uint64]$latest.interopLockFailures -eq 0) -and
        ([uint64]$latest.interopUnlockFailures -eq 0) -and
        ([uint64]$latest.registrationFailures -eq 0)
    $firstDraw = [bool]($markers | Where-Object { $_ -match 'FIRST_DRAW' })
    $invariantsPass = $leaseBalanced -and $interopBalanced -and $failuresZero
    $status = if ($invariantsPass -and $firstDraw) { 'PASS' } else { 'USER_ACTION_REQUIRED' }
    $evidence = [ordered]@{
        schemaVersion = 1
        status = $status
        logPath = $LogPath
        targetHz = $TargetHz
        durationMs = $DurationMs
        runtimeSnapshots = $snapshots
        renderMarkers = $markers
        invariants = [ordered]@{
            countersPresent = $hasCounters
            renderLeaseBalanced = $leaseBalanced
            interopBalanced = $interopBalanced
            failuresZero = $failuresZero
            passed = $invariantsPass
        }
        manualAcceptanceStillRequired = (-not $firstDraw)
    }
    $evidencePath = "$LogPath.evidence.json"
    $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    Write-Host "MCWebUI Direct CEF evidence: $evidencePath ($status)"
}

try {
    $url = $null
    if ($ExternalPageServer) {
        $server = Start-Process -FilePath $python -ArgumentList @('-m','http.server',$Port,'--bind','127.0.0.1','--directory',("`"$dist`"")) -WindowStyle Hidden -PassThru
        $ready = $false
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            if ($server.HasExited) { throw "HTTP server exited with code $($server.ExitCode)" }
            try {
                if ((Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/" -TimeoutSec 1).StatusCode -eq 200) { $ready = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 100
        }
        if (-not $ready) { throw 'Direct CEF external HTTP proof server did not start' }
        $url = "http://127.0.0.1:$Port/?view=direct-cef"
    }
    $common = @(
        '--no-daemon',
        '-PmcwebuiBrowserBackend=direct-cef',
        '-PmcwebuiDirectCefProof=true',
        "-PmcwebuiDirectCefRuntimeDir=$bin",
        "-PmcwebuiDirectCefHelper=$helper",
        "-PmcwebuiDirectCefNative=$native",
        "-PmcwebuiDirectCefCacheDir=$CacheRoot",
        "-PmcwebuiDirectCefTargetHz=$TargetHz"
    )
    if ($url) { $common += "-PmcwebuiDirectCefUrl=$url" }
    # ModDev's client is a visible desktop process. The bounded duration is an
    # outer watchdog; the user can still close F8 with Escape during the run.
    $gradle = Join-Path $repo 'gradlew.bat'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null
    Remove-Item -LiteralPath $LogPath,$errorLog -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $metaLog -Force -ErrorAction SilentlyContinue
    $client = Start-Process -FilePath $gradle -ArgumentList (@(':targets:neoforge-1.21.1:runClient') + $common) -WorkingDirectory $repo -PassThru -WindowStyle Normal -RedirectStandardOutput $LogPath -RedirectStandardError $errorLog
    $wrapperPid = $client.Id
    Add-Content -LiteralPath $metaLog -Value "MCWebUI direct runner wrapper pid=$wrapperPid"

    # Gradle starts the ModDev Java process asynchronously. Do not start the
    # requested run duration while NeoForge is still booting: doing so used to
    # stop the HTTP server before the first F8 navigation and produced
    # ERR_CONNECTION_REFUSED while the detached game kept running.
    $startupDeadline = [DateTime]::UtcNow.AddMilliseconds($StartupTimeoutMs)
    $clientReady = $false
    while ([DateTime]::UtcNow -lt $startupDeadline) {
        $game = Get-RunnerGameProcess
        if ($game -and -not $gamePid) {
            $gamePid = [int]$game.ProcessId
            Add-Content -LiteralPath $metaLog -Value "MCWebUI direct runner game pid=$gamePid"
        }
        if (Test-Path -LiteralPath $LogPath) {
            $clientReady = [bool](Select-String -LiteralPath $LogPath -Pattern 'Sound engine started' -Quiet -ErrorAction SilentlyContinue)
        }
        if ($gamePid -and $clientReady) { break }
        if ($client.HasExited -and -not $gamePid) {
            throw "Gradle exited before the NeoForge client started (code $($client.ExitCode)); inspect $LogPath and $errorLog"
        }
        if ($server -and $server.HasExited) { throw "HTTP server exited during NeoForge startup with code $($server.ExitCode)" }
        Start-Sleep -Milliseconds 250
    }
    if (-not $gamePid -or -not $clientReady) {
        throw "NeoForge did not reach the client-ready state within $StartupTimeoutMs ms; inspect $LogPath"
    }

    if ($AutoOpen) {
        if (-not ('McWebUi.WindowInput' -as [type])) {
            Add-Type @'
using System;
using System.Runtime.InteropServices;
namespace McWebUi { public static class WindowInput {
  [DllImport("user32.dll", SetLastError=true)] public static extern bool PostMessage(IntPtr hWnd, uint msg, UIntPtr wParam, IntPtr lParam);
} }
'@
        }
        $openDeadline = [DateTime]::UtcNow.AddSeconds(30)
        $opened = $false
        while ([DateTime]::UtcNow -lt $openDeadline) {
            $window = Get-Process java -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.MainWindowHandle -ne 0 -and
                    $_.MainWindowTitle -match 'Minecraft' -and
                    $_.MainWindowTitle -notmatch 'Loading'
                } |
                Select-Object -First 1
            if ($window -and $clientReady) {
                Add-Content -LiteralPath $metaLog -Value "MCWebUI AutoOpen F8 hwnd=$($window.MainWindowHandle) pid=$($window.Id) title=$($window.MainWindowTitle)"
                [McWebUi.WindowInput]::PostMessage($window.MainWindowHandle, 0x0100, [UIntPtr]([uint64]119), [IntPtr]0) | Out-Null
                [McWebUi.WindowInput]::PostMessage($window.MainWindowHandle, 0x0101, [UIntPtr]([uint64]119), [IntPtr]0) | Out-Null
                $opened = $true
                break
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $opened) { Write-Warning 'AutoOpen requested but no final Minecraft window was found before the 30s F8 deadline' }
    }

    Add-Content -LiteralPath $metaLog -Value $(if ($DurationMs -eq 0) {
        'MCWebUI direct runner manual session started; close Minecraft to finish'
    } else {
        "MCWebUI direct runner bounded session started durationMs=$DurationMs"
    })
    if ($DurationMs -eq 0) {
        Write-Host 'MCWebUI Direct CEF manual acceptance:'
        Write-Host '  1. Enter any Minecraft world.'
        Write-Host '  2. Press F8.'
        Write-Host '  3. Set WebScreen opacity to 50% and confirm the world remains visible.'
        Write-Host '  4. Drag the range slider, scroll the list, and type text.'
        Write-Host '  5. Press ESC, reopen with F8, then switch fullscreen/windowed once.'
        Write-Host '  6. Close Minecraft when finished; the runner will collect available evidence.'
    }
    $deadline = if ($DurationMs -eq 0) { [DateTime]::MaxValue } else { [DateTime]::UtcNow.AddMilliseconds($DurationMs) }
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-Process -Id $gamePid -ErrorAction SilentlyContinue)) { break }
        if ($server -and $server.HasExited) { throw "HTTP server exited while Minecraft was running with code $($server.ExitCode)" }
        Start-Sleep -Milliseconds 500
    }
    if ($DurationMs -gt 0 -and (Get-Process -Id $gamePid -ErrorAction SilentlyContinue)) {
        Add-Content -LiteralPath $metaLog -Value 'MCWebUI direct runner watchdog reached; stopping the bounded client'
    }
} finally {
    try {
        Add-Content -LiteralPath $metaLog -Value 'MCWebUI direct runner cleanup begin'
        Stop-RunnerProcesses
        Add-Content -LiteralPath $metaLog -Value 'MCWebUI direct runner process cleanup complete'
    } finally {
        if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force }
        if ($wrapperPid -and (Get-Process -Id $wrapperPid -ErrorAction SilentlyContinue)) {
            try { Wait-Process -Id $wrapperPid -Timeout 5 -ErrorAction Stop } catch { }
        }
        # Evidence uses the completed runtime checkpoints and must not depend on
        # System.Diagnostics.Process.WaitForExit draining inherited Gradle/Java
        # handles. On Windows that call can outlive the already-stopped client.
        Add-Content -LiteralPath $metaLog -Value 'MCWebUI direct runner evidence collection begin'
        Write-RunnerEvidence
        Add-Content -LiteralPath $metaLog -Value 'MCWebUI direct runner cleanup complete'
    }
}
