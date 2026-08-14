[CmdletBinding()]
param(
    [ValidateSet('LOCAL_FIXTURE', 'REAL_RELEASE')]
    [string]$Mode = 'LOCAL_FIXTURE',
    [string]$CefRoot = '',
    [string]$BuildRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-runtime-build'),
    [string]$InstanceRoot = (Join-Path $env:TEMP ('mcwebui-direct-cef-first-run-' + [guid]::NewGuid().ToString('N'))),
    [string]$DescriptorPath = '',
    [string]$ReleaseStagingDirectory = (Join-Path $env:TEMP ('mcwebui-direct-cef-release-' + [guid]::NewGuid().ToString('N'))),
    [string]$ArtifactRevision = 'local-fixture-r1',
    [string]$LogPath = (Join-Path $env:TEMP 'mcwebui-direct-cef-first-run-neoforge.log'),
    [int]$DurationMs = 90000,
    [int]$StartupTimeoutMs = 120000,
    [int]$TargetHz = 60,
    [switch]$SkipBuild,
    [switch]$SkipNeoForge
)

# End-to-end release acceptance harness. LOCAL_FIXTURE injects a local package
# stream into the real downloader without inventing a production URL.
# REAL_RELEASE intentionally refuses to run unless a configured, JAR-ready
# descriptor with an actual HTTPS asset URL is supplied.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$instance = [System.IO.Path]::GetFullPath($InstanceRoot)
$standard = Join-Path $instance 'mcwebui\runtime\cef\cef-144.0.33-cb4715c\windows-x86_64'
if (Test-Path -LiteralPath $standard) {
    throw "Fresh-instance acceptance requires no installed runtime: $standard"
}

$package = ''
$descriptor = ''
if ($Mode -eq 'LOCAL_FIXTURE') {
    if ([string]::IsNullOrWhiteSpace($CefRoot)) { $CefRoot = $env:MCWEBUI_CEF_ROOT }
    if ([string]::IsNullOrWhiteSpace($CefRoot) -or -not (Test-Path -LiteralPath $CefRoot)) {
        throw 'LOCAL_FIXTURE requires -CefRoot or MCWEBUI_CEF_ROOT for the pinned CEF144 SDK'
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
    $prepared = Join-Path $env:TEMP ('mcwebui-direct-cef-prepared-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $prepared | Out-Null
    foreach ($item in @(Get-ChildItem -LiteralPath $bin -Force)) {
        if ($item.Name -in @('runtime.json', 'mcwebui_direct_cef_smoke.exe')) { continue }
        Copy-Item -LiteralPath $item.FullName -Destination $prepared -Recurse -Force
    }
    & (Join-Path $PSScriptRoot 'generate-runtime-manifest.ps1') -RuntimeRoot $prepared
    & (Join-Path $PSScriptRoot 'prepare-runtime-release.ps1') -RuntimeRoot $prepared `
        -ArtifactRevision $ArtifactRevision -OutputDirectory $ReleaseStagingDirectory
    $package = (Get-ChildItem -LiteralPath $ReleaseStagingDirectory -Filter '*.zip' -File | Select-Object -First 1).FullName
    $descriptor = $package + '.release.json'
} else {
    if ([string]::IsNullOrWhiteSpace($DescriptorPath) -or -not (Test-Path -LiteralPath $DescriptorPath -PathType Leaf)) {
        Write-Host 'MCWebUI fresh-instance REAL_RELEASE: NOT CONFIGURED (no production descriptor)'
        return
    }
    $descriptor = (Resolve-Path -LiteralPath $DescriptorPath).Path
    $candidate = Get-Content -Raw -LiteralPath $descriptor -Encoding UTF8 | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace([string]$candidate.downloadUri)) {
        Write-Host 'MCWebUI fresh-instance REAL_RELEASE: NOT CONFIGURED (descriptor has no HTTPS asset URL)'
        return
    }
    if ([string]::IsNullOrWhiteSpace($CefRoot)) { $CefRoot = $env:MCWEBUI_CEF_ROOT }
}

New-Item -ItemType Directory -Force -Path $instance | Out-Null
$evidence = Join-Path ([System.IO.Path]::GetDirectoryName($descriptor)) "first-run-$($Mode.ToLowerInvariant()).json"
$testArgs = @(
    ':targets:neoforge-1.21.1:test',
    '--tests', 'dev.qingmo.mcwebui.nativecef.DirectCefRuntimeFirstRunProofTest',
    "-PmcwebuiProofMode=$Mode",
    "-PmcwebuiProofDescriptor=$descriptor",
    "-PmcwebuiProofInstanceRoot=$instance",
    "-PmcwebuiProofEvidence=$evidence",
    '--no-daemon'
)
if ($Mode -eq 'LOCAL_FIXTURE') { $testArgs += "-PmcwebuiProofPackage=$package" }
& (Join-Path $repo 'gradlew.bat') @testArgs
if ($LASTEXITCODE -ne 0) { throw "$Mode downloader/import/discovery proof failed" }
if (-not (Test-Path -LiteralPath $evidence)) { throw "Fresh-install evidence is missing: $evidence" }
$proof = Get-Content -Raw -LiteralPath $evidence -Encoding UTF8 | ConvertFrom-Json
if ($proof.status -ne 'PASS') { throw "Fresh-install evidence is not PASS: $evidence" }

if ($SkipNeoForge) {
    Write-Host "MCWebUI fresh-instance ${Mode}: PASS (download/import/discovery; NeoForge skipped explicitly)"
    Write-Host "  evidence: $evidence"
    return
}
if ([string]::IsNullOrWhiteSpace($CefRoot)) {
    throw 'NeoForge acceptance requires -CefRoot or MCWEBUI_CEF_ROOT'
}
$runner = @{
    CefRoot = $CefRoot
    BuildRoot = $BuildRoot
    RuntimeRoot = $instance
    RuntimeSource = 'Standard'
    SkipBuild = $true
    SkipRuntimePrepare = $true
    DurationMs = $DurationMs
    StartupTimeoutMs = $StartupTimeoutMs
    TargetHz = $TargetHz
    LogPath = $LogPath
}
& (Join-Path $repo 'scripts\direct-cef-proof\run-neoforge-webscreen.ps1') @runner
if ($LASTEXITCODE -ne 0) { throw 'NeoForge Direct startup acceptance failed' }

$lines = @()
foreach ($path in @($LogPath, "$LogPath.err")) {
    if (Test-Path -LiteralPath $path) { $lines += @(Get-Content -LiteralPath $path -ErrorAction SilentlyContinue) }
}
$bundled = [bool]($lines | Where-Object { $_ -match 'bundled page server started' })
$bridge = [bool]($lines | Where-Object { $_ -match 'Direct CEF bridge handshake completed' })
$prewarm = [bool]($lines | Where-Object { $_ -match 'prewarm completed with an accelerated texture and bridge handshake' })
if (-not ($bundled -and $bridge -and $prewarm)) {
    throw 'Fresh-instance NeoForge markers are incomplete; inspect the run log'
}
Write-Host "MCWebUI fresh-instance ${Mode}: PASS"
Write-Host '  download=true import=true discovery=true bundledPage=true bridge=true hiddenPrewarm=true'
Write-Host "  evidence: $evidence"
