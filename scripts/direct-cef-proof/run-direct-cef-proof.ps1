[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Executable,
    [string]$ResultRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-results'),
    [int]$DurationMs = 6000,
    [int]$Width = 1280,
[int]$Height = 720,
[switch]$IncludeAccelerated,
[switch]$IncludeSimulator
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$dist = Join-Path $repo 'frontend\playground\dist\index.html'
if (-not (Test-Path -LiteralPath $dist)) {
    & (Join-Path $repo 'gradlew.bat') frontendBuild --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
}
$exe = (Resolve-Path -LiteralPath $Executable).Path
New-Item -ItemType Directory -Force -Path $ResultRoot | Out-Null

$runs = @(
    @{ Name = 'windowed-baseline'; Args = @('--mode=windowed-baseline') },
    @{ Name = 'backend-default'; Args = @('--mode=backend-default') }
)
foreach ($hz in @(30, 60, 120, 144)) {
    $runs += @{ Name = "external-$hz"; Args = @('--mode=external-begin-frame', "--target-hz=$hz") }
}
if ($IncludeAccelerated) {
    $runs += @{ Name = 'accelerated-60'; Args = @('--mode=external-begin-frame', '--target-hz=60', '--accelerated') }
}
if ($IncludeSimulator) {
    $runs += @{ Name = 'simulator-60'; Args = @('--mode=external-begin-frame', '--target-hz=60', '--accelerated', '--simulator') }
}
$runs += @{ Name = 'idle-external-144'; Args = @('--mode=external-begin-frame', '--target-hz=144', '--idle') }

foreach ($run in $runs) {
    $output = Join-Path $ResultRoot ($run.Name + '.json')
    & $exe @($run.Args) "--duration-ms=$DurationMs" "--width=$Width" "--height=$Height" "--dist=$dist" "--output=$output"
    if ($LASTEXITCODE -ne 0) { throw "Proof run $($run.Name) failed with exit code $LASTEXITCODE" }
    $result = Get-Content -Raw -LiteralPath $output | ConvertFrom-Json
    # WINDOWED_BASELINE intentionally has no CefRenderHandler surface. It is
    # valid when the browser loaded and browser-side rAF was observed even
    # though actualSize/cpuPaint remain zero. All OSR modes must report a
    # non-zero render surface.
    $hasOsrSurface = $result.actualSize.width -gt 0 -and $result.actualSize.height -gt 0
    $hasAcceleratedSurface = $result.acceleratedPaint.callbacks -gt 0
    $missingSurface = $run.Name -ne 'windowed-baseline' -and
        -not ($hasOsrSurface -or $hasAcceleratedSurface)
    if (-not $result.load.success -or $missingSurface) {
        throw "Proof run $($run.Name) completed without a loaded browser surface"
    }
    if ($run.Name -eq 'simulator-60' -and $result.presentedFrames -le 0) {
        throw 'Simulator requested but no GPU-presented frames were recorded'
    }
}

Get-ChildItem -LiteralPath $ResultRoot -Filter '*.json' | Sort-Object Name | ForEach-Object {
    $result = Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json
    [pscustomobject]@{
        Run = $_.BaseName
        RequestsHz = $result.frameRequests.rateHz
        RafHz = $result.browserRaf.rateHz
        CpuPaintHz = $result.cpuPaint.rateHz
        AcceleratedPaintHz = $result.acceleratedPaint.rateHz
        PresentedFrames = $result.presentedFrames
        D3D11Opened = $result.d3d11.opened
        Load = $result.load.success
    }
} | Format-Table -AutoSize
