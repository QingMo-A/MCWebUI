[CmdletBinding()]
param(
    [string]$CefRoot = 'F:\Temp\cef-modern-144\cef_binary_144.0.33+gcb4715c+chromium-144.0.7559.259_windows64',
    [int]$DurationMs = 0,
    [int]$Width = 1280,
    [int]$Height = 720,
    [int]$Port = 18765,
    [string]$BuildRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-build'),
    [switch]$AutoInput,
    [switch]$AlphaProof
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not (Test-Path -LiteralPath $CefRoot)) { throw "CEF 144 root not found: $CefRoot" }
& (Join-Path $repo 'gradlew.bat') frontendBuild --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'frontendBuild failed' }
$exe = & (Join-Path $PSScriptRoot 'build-direct-cef-proof.ps1') -CefRoot $CefRoot -BuildRoot $BuildRoot
$exe = ($exe | Select-Object -Last 1).Trim()
$dist = (Resolve-Path (Join-Path $repo 'frontend\playground\dist')).Path
$python = (Get-Command python.exe -ErrorAction Stop).Source
$serverArguments = "-m http.server $Port --bind 127.0.0.1 --directory `"$dist`""
$server = Start-Process -FilePath $python -ArgumentList $serverArguments -WindowStyle Hidden -PassThru
$args = @('--mode=external-begin-frame', '--target-hz=60', '--accelerated', '--simulator-mailbox', '--present-mode=vsync', '--interactive', "--width=$Width", "--height=$Height", "--dist=$(Join-Path $dist 'index.html')", "--url=http://127.0.0.1:$Port/?view=transparent-lab")
# Always pass the duration explicitly.  Zero is intentional: it leaves the
# interactive proof open until the second Escape closes the browser.  Bounded
# automation callers should provide a positive DurationMs.
$args += "--duration-ms=$DurationMs"
if ($AutoInput) { $args += '--auto-input' }
if ($AlphaProof) { $args += '--alpha-proof' }
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/" -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch {
            if ($server.HasExited) { throw "Transparent WebScreen local server exited with code $($server.ExitCode)" }
            Start-Sleep -Milliseconds 100
        }
    }
    if (-not $ready) { throw 'Transparent WebScreen local server did not start' }
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "Transparent WebScreen proof exited with code $LASTEXITCODE" }
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force }
}
