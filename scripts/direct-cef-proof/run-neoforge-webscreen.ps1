[CmdletBinding()]
param(
    [string]$CefRoot = '',
    [string]$BuildRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-runtime-build'),
    [string]$RuntimeRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-runtime'),
    [string]$CacheRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-cache'),
    [int]$DurationMs = 90000,
    [int]$Port = 18765,
    [int]$TargetHz = 60,
    [string]$LogPath = (Join-Path $env:TEMP 'mcwebui-direct-cef-neoforge-run.log'),
    [switch]$SkipBuild,
    [switch]$DirectOnly,
    [switch]$AutoOpen
)

$ErrorActionPreference = 'Stop'
if ($DurationMs -lt 1) { throw 'DurationMs must be positive for the bounded NeoForge runner' }
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
$python = (Get-Command python.exe -ErrorAction Stop).Source
$server = $null
try {
    $server = Start-Process -FilePath $python -ArgumentList @('-m','http.server',$Port,'--bind','127.0.0.1','--directory',("`"$dist`"")) -WindowStyle Hidden -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            if ((Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/" -TimeoutSec 1).StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        if ($server.HasExited) { throw "HTTP server exited with code $($server.ExitCode)" }
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) { throw 'Direct CEF HTTP proof server did not start' }
    $url = "http://127.0.0.1:$Port/?view=direct-cef"
    $common = @(
        '--no-daemon',
        '-PmcwebuiBrowserBackend=direct-cef',
        '-PmcwebuiDirectCefProof=true',
        "-PmcwebuiDirectCefUrl=$url",
        "-PmcwebuiDirectCefRuntimeDir=$bin",
        "-PmcwebuiDirectCefHelper=$helper",
        "-PmcwebuiDirectCefNative=$native",
        "-PmcwebuiDirectCefCacheDir=$CacheRoot",
        "-PmcwebuiDirectCefTargetHz=$TargetHz"
    )
    # ModDev's client is a visible desktop process. The bounded duration is an
    # outer watchdog; the user can still close F8 with Escape during the run.
    $gradle = Join-Path $repo 'gradlew.bat'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null
    $errorLog = "$LogPath.err"
    $metaLog = "$LogPath.meta"
    Remove-Item -LiteralPath $LogPath,$errorLog -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $metaLog -Force -ErrorAction SilentlyContinue
    $client = Start-Process -FilePath $gradle -ArgumentList (@(':targets:neoforge-1.21.1:runClient') + $common) -WorkingDirectory $repo -PassThru -WindowStyle Normal -RedirectStandardOutput $LogPath -RedirectStandardError $errorLog
    $wrapperPid = $client.Id
    $gamePid = $null
    Add-Content -LiteralPath $metaLog -Value "MCWebUI direct runner wrapper pid=$wrapperPid"
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
        $openDeadline = [DateTime]::UtcNow.AddSeconds(50)
        $opened = $false
        while ([DateTime]::UtcNow -lt $openDeadline) {
            $window = Get-Process java -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.MainWindowHandle -ne 0 -and
                    $_.MainWindowTitle -match 'Minecraft' -and
                    $_.MainWindowTitle -notmatch 'Loading'
                } |
                Select-Object -First 1
            if ($window) {
                Add-Content -LiteralPath $metaLog -Value "MCWebUI AutoOpen F8 hwnd=$($window.MainWindowHandle) pid=$($window.Id) title=$($window.MainWindowTitle)"
                [McWebUi.WindowInput]::PostMessage($window.MainWindowHandle, 0x0100, [UIntPtr]([uint64]119), [IntPtr]0) | Out-Null
                [McWebUi.WindowInput]::PostMessage($window.MainWindowHandle, 0x0101, [UIntPtr]([uint64]119), [IntPtr]0) | Out-Null
                $opened = $true
                break
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $opened) { Write-Warning 'AutoOpen requested but no Minecraft window was found before the 50s F8 deadline' }
    }
    $deadline = [DateTime]::UtcNow.AddMilliseconds($DurationMs)
    while ([DateTime]::UtcNow -lt $deadline) {
        $game = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object { $_.CommandLine -like '*net.neoforged.devlaunch.Main*' } |
            Select-Object -First 1
        if ($game) {
            if (-not $gamePid) {
                $gamePid = [int]$game.ProcessId
                Add-Content -LiteralPath $metaLog -Value "MCWebUI direct runner game pid=$gamePid"
            }
        } elseif ($gamePid) {
            # The Java process can disappear before the watchdog deadline.  Do
            # not mistake the Gradle wrapper's normal exit for a runner error.
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if ($gamePid) {
        Stop-Process -Id $gamePid -Force -ErrorAction SilentlyContinue
    }
    if ($client -and -not $client.HasExited) {
        Stop-Process -Id $wrapperPid -Force -ErrorAction SilentlyContinue
    }
    # Give Windows a short, bounded window to reap the child before checking
    # for leaks.  A just-terminated Java process may remain visible in the CIM
    # snapshot for a few hundred milliseconds.
    $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $remaining = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object { $_.CommandLine -like '*net.neoforged.devlaunch.Main*' })
        if (-not $remaining) { break }
        foreach ($process in $remaining) { Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $cleanupDeadline)
    if ($remaining) {
        Add-Content -LiteralPath $metaLog -Value "MCWebUI direct runner residual java pid(s)=$($remaining.ProcessId -join ',')"
        throw "Direct CEF NeoForge run left a Java process after bounded cleanup"
    }
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force }
}
