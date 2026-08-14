[CmdletBinding()]
param(
    [string]$CefRoot = '',
    [string]$BuildRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-runtime-build'),
    [string]$InstanceRoot = (Join-Path $env:TEMP ('mcwebui-direct-cef-import-instance-' + [guid]::NewGuid().ToString('N'))),
    [string]$PackageDirectory = (Join-Path $env:TEMP 'mcwebui-runtime-packages'),
    [string]$LogPath = (Join-Path $env:TEMP 'mcwebui-direct-cef-import-neoforge-run.log'),
    [int]$DurationMs = 90000,
    [int]$StartupTimeoutMs = 120000,
    [int]$TargetHz = 60,
    [switch]$SkipBuild,
    [switch]$DeterminismCheck,
    [switch]$CollectEvidence
)

# Phase B proof: a fresh instance with no Direct CEF runtime receives an offline
# package ZIP, imports it through the Java importer (JUnit proof), then starts a
# real NeoForge Direct client from the STANDARD directory with NO runtimeDir
# override, proving package -> staging -> publish -> Phase A rediscovery -> Direct CEF.
$ErrorActionPreference = 'Stop'
if ($DurationMs -lt 0) { throw 'DurationMs must be zero (manual run) or positive (bounded run)' }
if ($StartupTimeoutMs -lt 1000) { throw 'StartupTimeoutMs must be at least 1000' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($CefRoot)) { $CefRoot = $env:MCWEBUI_CEF_ROOT }
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
foreach ($required in @((Join-Path $bin 'mcwebui-direct-cef.dll'), (Join-Path $bin 'mcwebui-cef-helper.exe'), (Join-Path $bin 'libcef.dll'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Direct CEF runtime artifact missing: $required" }
}

# 1. Prepare a Phase A validated runtime tree and package it deterministically.
$prepared = Join-Path $env:TEMP ('mcwebui-direct-cef-prepared-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $prepared | Out-Null
foreach ($item in @(Get-ChildItem -LiteralPath $bin -Force)) {
    if ($item.Name -in @('runtime.json', 'mcwebui_direct_cef_smoke.exe')) { continue }
    Copy-Item -LiteralPath $item.FullName -Destination $prepared -Recurse -Force
}
& (Join-Path $repo 'scripts\direct-cef-runtime\generate-runtime-manifest.ps1') -RuntimeRoot $prepared
if ($LASTEXITCODE -ne 0) { throw 'Runtime manifest generation failed' }
$prepared = (Resolve-Path -LiteralPath $prepared).Path

$packageOutput = Join-Path $PackageDirectory ('mcwebui-direct-cef-runtime-proof-' + [guid]::NewGuid().ToString('N') + '.zip')
$packageParams = @{
    RuntimeRoot = $prepared
    OutputPath = $packageOutput
}
if ($DeterminismCheck) { $packageParams.DeterminismCheck = $true }
& (Join-Path $repo 'scripts\direct-cef-runtime\package-runtime.ps1') @packageParams
if ($LASTEXITCODE -ne 0) { throw 'Runtime package creation failed' }
$package = (Resolve-Path -LiteralPath $packageOutput).Path
$packageHash = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToUpperInvariant()
$packageSize = (Get-Item -LiteralPath $package).Length

# 2. Import into a fresh instance through the Java importer proof test.
$instanceRoot = [System.IO.Path]::GetFullPath($InstanceRoot)
$standardDir = Join-Path $instanceRoot "mcwebui\runtime\cef\cef-144.0.33-cb4715c\windows-x86_64"
if (Test-Path -LiteralPath $standardDir) {
    throw "Proof instance must start without a standard runtime: $standardDir"
}
New-Item -ItemType Directory -Force -Path $instanceRoot | Out-Null
$evidencePath = Join-Path $repo 'direct-cef-proof-import.json'
Remove-Item -LiteralPath $evidencePath -Force -ErrorAction SilentlyContinue
& (Join-Path $repo 'gradlew.bat') ':targets:neoforge-1.21.1:test' `
    --tests 'dev.qingmo.mcwebui.nativecef.DirectCefRuntimeImportProofTest' `
    "-PmcwebuiProofPackage=$package" "-PmcwebuiProofInstanceRoot=$instanceRoot" `
    "-PmcwebuiProofEvidence=$evidencePath" --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Phase B importer proof test failed' }
if (-not (Test-Path -LiteralPath $evidencePath)) {
    throw "Import proof evidence was not written: $evidencePath"
}
$importEvidence = Get-Content -Raw -LiteralPath $evidencePath | ConvertFrom-Json
if ($importEvidence.status -ne 'PASS') {
    throw "Import proof evidence is not PASS: $evidencePath"
}

# 3. Start the real NeoForge Direct client from the SAME instance (standard
#    discovery only; no mcwebui.directCef.runtimeDir anywhere).
$runnerParams = @{
    CefRoot = $CefRoot
    BuildRoot = $BuildRoot
    RuntimeRoot = $instanceRoot
    RuntimeSource = 'Standard'
    SkipBuild = $true
    SkipRuntimePrepare = $true
    DurationMs = $DurationMs
    StartupTimeoutMs = $StartupTimeoutMs
    TargetHz = $TargetHz
    LogPath = $LogPath
}
if ($CollectEvidence) { $runnerParams.CollectEvidence = $true }
& (Join-Path $repo 'scripts\direct-cef-proof\run-neoforge-webscreen.ps1') @runnerParams
if ($LASTEXITCODE -ne 0) { throw 'NeoForge Direct startup proof failed' }

# 4. Summarize: import PASS + NeoForge startup + bridge handshake + prewarm.
$runLog = "$LogPath"
$runError = "$LogPath.err"
$allLines = @()
foreach ($path in @($runLog, $runError)) {
    if (Test-Path -LiteralPath $path) { $allLines += @(Get-Content -LiteralPath $path -ErrorAction SilentlyContinue) }
}
$bridgeHandshake = [bool]($allLines | Where-Object { $_ -match '\[MCWebUI\] Direct CEF bridge handshake completed' })
$prewarm = [bool]($allLines | Where-Object { $_ -match 'MCWebUI Direct CEF prewarm completed with an accelerated texture and bridge handshake' })
$bundledPage = [bool]($allLines | Where-Object { $_ -match 'bundled page server started' })
$startupPass = $bridgeHandshake -and $prewarm -and $bundledPage

$summary = [ordered]@{
    schemaVersion = 1
    status = if ($startupPass) { 'PASS' } else { 'FAIL' }
    phase = 'B'
    package = $package
    packageSize = $packageSize
    packageSha256 = $packageHash
    packageManifestFiles = [int]$importEvidence.manifestFiles
    instanceRoot = $instanceRoot
    standardRuntimeDirectory = $standardDir
    import = [ordered]@{
        status = $importEvidence.status
        importStatus = $importEvidence.importStatus
        runtimeId = $importEvidence.runtimeId
        manifestFiles = [int]$importEvidence.manifestFiles
    }
    neoforge = [ordered]@{
        bundledPage = $bundledPage
        bridgeHandshake = $bridgeHandshake
        hiddenPrewarm = $prewarm
        passed = $startupPass
    }
    runtimeDirOverrideUsed = $false
    logPath = $runLog
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
Write-Host "MCWebUI Phase B import proof: $($summary.status) (package $package)"
Write-Host "  import=$($importEvidence.status) bundledPage=$bundledPage bridgeHandshake=$bridgeHandshake prewarm=$prewarm"
Write-Host "  package sha256=$packageHash"
if (-not $startupPass) { throw 'Phase B proof: NeoForge startup markers missing; inspect the run log' }
