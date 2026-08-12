[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Executable,
    [string]$ResultRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-results'),
    [int]$DurationMs = 6000,
    [int]$Width = 1280,
    [int]$Height = 720,
    [switch]$IncludeAccelerated
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

$runs = @(@{ Name = 'backend-default'; Args = @('--mode=backend-default') })
foreach ($hz in @(30, 60, 120, 144)) {
    $runs += @{ Name = "external-$hz"; Args = @('--mode=external-begin-frame', "--target-hz=$hz") }
}
if ($IncludeAccelerated) {
    $runs += @{ Name = 'accelerated-60'; Args = @('--mode=external-begin-frame', '--target-hz=60', '--accelerated') }
}
$runs += @{ Name = 'idle-external-144'; Args = @('--mode=external-begin-frame', '--target-hz=144', '--idle') }

foreach ($run in $runs) {
    $output = Join-Path $ResultRoot ($run.Name + '.json')
    & $exe @($run.Args) "--duration-ms=$DurationMs" "--width=$Width" "--height=$Height" "--dist=$dist" "--output=$output"
    if ($LASTEXITCODE -ne 0) { throw "Proof run $($run.Name) failed with exit code $LASTEXITCODE" }
    $result = Get-Content -Raw -LiteralPath $output | ConvertFrom-Json
    if (-not $result.load.success -or $result.actualSize.width -le 0 -or $result.actualSize.height -le 0) {
        throw "Proof run $($run.Name) completed without a loaded browser surface"
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
        D3D11Opened = $result.d3d11.opened
        Load = $result.load.success
    }
} | Format-Table -AutoSize
