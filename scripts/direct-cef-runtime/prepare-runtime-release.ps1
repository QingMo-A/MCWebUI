[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$RuntimeRoot,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string]$ArtifactRevision,
    [string]$DownloadUri = '',
    [string]$OutputDirectory = (Join-Path $env:TEMP ('mcwebui-direct-cef-release-' + [guid]::NewGuid().ToString('N')))
)

# Release preparation is deliberately an external-staging operation. It
# composes the Phase A/B generators instead of carrying a second packaging
# dialect, and it never writes a descriptor into source resources.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$root = (Resolve-Path -LiteralPath $RuntimeRoot).Path
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) {
    if (@(Get-ChildItem -LiteralPath $output -Force).Count -gt 0) {
        throw "Release staging directory must be empty: $output"
    }
} else {
    New-Item -ItemType Directory -Force -Path $output | Out-Null
}

$manifestPath = Join-Path $root 'runtime.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Prepared runtime is missing runtime.json: $manifestPath"
}
$manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
$runtimeId = [string]$manifest.runtimeId
$platform = [string]$manifest.platform
$arch = [string]$manifest.arch
if ([string]::IsNullOrWhiteSpace($runtimeId) -or [string]::IsNullOrWhiteSpace($platform) -or
    [string]::IsNullOrWhiteSpace($arch)) {
    throw 'Prepared runtime manifest is missing release identity fields'
}

$packageName = "mcwebui-direct-cef-runtime-$runtimeId-$platform-$arch-$ArtifactRevision.zip"
$packagePath = Join-Path $output $packageName
& (Join-Path $PSScriptRoot 'package-runtime.ps1') -RuntimeRoot $root `
    -OutputPath $packagePath -DeterminismCheck

$descriptorPath = $packagePath + '.release.json'
$descriptorParams = @{
    PackagePath = $packagePath
    ArtifactRevision = $ArtifactRevision
    OutputPath = $descriptorPath
}
if (-not [string]::IsNullOrWhiteSpace($DownloadUri)) {
    $descriptorParams.DownloadUri = $DownloadUri
}
& (Join-Path $PSScriptRoot 'generate-release-descriptor.ps1') @descriptorParams

$descriptor = Get-Content -Raw -LiteralPath $descriptorPath -Encoding UTF8 | ConvertFrom-Json
$packageItem = Get-Item -LiteralPath $packagePath
$packageSha = (Get-FileHash -LiteralPath $packagePath -Algorithm SHA256).Hash.ToUpperInvariant()
$descriptorSha = (Get-FileHash -LiteralPath $descriptorPath -Algorithm SHA256).Hash.ToUpperInvariant()
$payloadSize = [int64](@($manifest.files) | Measure-Object -Property size -Sum).Sum
$sourceSha = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceSha -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'Unable to resolve the source Git SHA for the release report'
}

$checksumsPath = Join-Path $output 'checksums.txt'
$checksumLines = @(
    "$packageSha  $packageName",
    "$descriptorSha  $([System.IO.Path]::GetFileName($descriptorPath))"
)
[System.IO.File]::WriteAllLines($checksumsPath, $checksumLines,
    [System.Text.UTF8Encoding]::new($false))

$reportPath = Join-Path $output 'release-report.json'
$report = [ordered]@{
    schemaVersion = 1
    status = if ([string]::IsNullOrWhiteSpace($DownloadUri)) {
        'RELEASE_READY_WAITING_FOR_OFFICIAL_ASSET'
    } else {
        'RELEASE_DESCRIPTOR_CONFIGURED'
    }
    sourceGitSha = $sourceSha
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    deterministicPackage = $true
    runtime = [ordered]@{
        runtimeId = $runtimeId
        mcwebuiRuntimeAbi = [int]$manifest.mcwebuiRuntimeAbi
        cefVersion = [string]$manifest.cefVersion
        chromiumVersion = [string]$manifest.chromiumVersion
        platform = $platform
        arch = $arch
    }
    artifact = [ordered]@{
        artifactId = [string]$descriptor.artifactId
        artifactRevision = $ArtifactRevision
        fileName = $packageName
        compressedSize = [int64]$packageItem.Length
        runtimePayloadSize = $payloadSize
        sha256 = $packageSha
        fileCount = @($manifest.files).Count
    }
    entrypoints = [ordered]@{
        native = [string]$manifest.entrypoints.native
        helper = [string]$manifest.entrypoints.helper
        cef = [string]$manifest.entrypoints.cef
        chromeElf = [string]$manifest.entrypoints.chromeElf
    }
    descriptor = [ordered]@{
        path = [System.IO.Path]::GetFileName($descriptorPath)
        downloadUri = if ([string]::IsNullOrWhiteSpace($DownloadUri)) { 'UNCONFIGURED' } else { $DownloadUri }
    }
}
[System.IO.File]::WriteAllText($reportPath,
    ($report | ConvertTo-Json -Depth 6) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))

Write-Host 'MCWebUI Direct CEF runtime release preparation: PASS'
Write-Host "  package: $packagePath"
Write-Host "  descriptor candidate: $descriptorPath"
Write-Host "  checksums: $checksumsPath"
Write-Host "  report: $reportPath"
Write-Host "  compressed bytes: $($packageItem.Length)"
Write-Host "  unpacked payload bytes: $payloadSize"
Write-Host "  sha256: $packageSha"
Write-Host "  deterministic: true"
Write-Host "  source: $sourceSha"
Write-Host "  download: $(if ([string]::IsNullOrWhiteSpace($DownloadUri)) { 'UNCONFIGURED' } else { $DownloadUri })"
