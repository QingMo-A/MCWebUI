[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PackagePath,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string]$ArtifactRevision,
    [string]$ArtifactId = '',
    [string]$DownloadUri = '',
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$package = (Resolve-Path -LiteralPath $PackagePath).Path
$packageItem = Get-Item -LiteralPath $package -Force
if (-not $packageItem.PSIsContainer -and
    ($packageItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Runtime package cannot be a reparse point: $package"
}
if ($packageItem.PSIsContainer) {
    throw "Runtime package must be a ZIP file: $package"
}
if (-not $packageItem.Name.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Runtime package must use the .zip extension: $package"
}

if (-not [string]::IsNullOrWhiteSpace($DownloadUri)) {
    $uri = [Uri]$DownloadUri
    if (-not $uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or -not [string]::IsNullOrWhiteSpace($uri.UserInfo) -or
        -not [string]::IsNullOrWhiteSpace($uri.Fragment)) {
        throw 'DownloadUri must be an absolute HTTPS URI without userinfo or a fragment'
    }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$archive = [System.IO.Compression.ZipFile]::OpenRead($package)
try {
    $manifestEntries = @($archive.Entries | Where-Object { $_.FullName -ceq 'runtime.json' })
    if ($manifestEntries.Count -ne 1) {
        throw "Runtime package must contain exactly one root runtime.json; found $($manifestEntries.Count)"
    }
    if ($manifestEntries[0].Length -gt 16MB) {
        throw 'runtime.json exceeds the 16 MiB package safety bound'
    }
    $stream = $manifestEntries[0].Open()
    try {
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.UTF8Encoding]::new($false, $true), $true)
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }
} finally { $archive.Dispose() }

foreach ($field in @('schemaVersion', 'runtimeId', 'mcwebuiRuntimeAbi', 'cefVersion',
        'chromiumVersion', 'platform', 'arch')) {
    if ($null -eq $manifest.$field -or [string]::IsNullOrWhiteSpace([string]$manifest.$field)) {
        throw "runtime.json is missing identity field: $field"
    }
}
if ([int]$manifest.schemaVersion -ne 1 -or [int]$manifest.mcwebuiRuntimeAbi -ne 1) {
    throw 'Only Direct CEF runtime schema v1 / ABI 1 packages can produce a descriptor'
}
if ($null -eq $manifest.files -or @($manifest.files).Count -lt 1) {
    throw 'runtime.json must declare at least one payload file'
}

# A release descriptor must never pin an archive merely because it happens to
# contain an identity-shaped runtime.json. Verify that the input is a complete
# Phase B package before recording its outer size and checksum.
$expected = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($record in @($manifest.files)) {
    $relative = [string]$record.path
    $segments = $relative.Split('/')
    if ([string]::IsNullOrWhiteSpace($relative) -or $relative.Contains([char]0) -or
        $relative.Contains('\') -or $relative.StartsWith('/') -or $relative.Contains(':') -or
        @($segments | Where-Object { [string]::IsNullOrEmpty($_) -or $_ -eq '.' -or $_ -eq '..' }).Count -gt 0) {
        throw "runtime.json contains an unsafe package path: $relative"
    }
    if ($expected.ContainsKey($relative)) {
        throw "runtime.json contains a case-insensitive duplicate path: $relative"
    }
    $expected.Add($relative, $record)
}

$archive = [System.IO.Compression.ZipFile]::OpenRead($package)
try {
    $entries = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName.EndsWith('/')) { throw "Phase B packages cannot contain directory entries: $($entry.FullName)" }
        if ($entries.ContainsKey($entry.FullName)) { throw "Package contains a case-insensitive duplicate entry: $($entry.FullName)" }
        $entries.Add($entry.FullName, $entry)
    }
    if ($entries.Count -ne ($expected.Count + 1) -or -not $entries.ContainsKey('runtime.json')) {
        throw 'Package entry set does not exactly match runtime.json plus its declared files'
    }
    foreach ($pair in $expected.GetEnumerator()) {
        if (-not $entries.ContainsKey($pair.Key)) { throw "Package is missing manifest file: $($pair.Key)" }
        $entry = $entries[$pair.Key]
        $record = $pair.Value
        if ([int64]$entry.Length -ne [int64]$record.size) {
            throw "Package entry size does not match runtime.json: $($pair.Key)"
        }
        $stream = $entry.Open()
        try {
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $actual = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '') } finally { $sha.Dispose() }
        } finally { $stream.Dispose() }
        if ($actual -ne ([string]$record.sha256).ToUpperInvariant()) {
            throw "Package entry SHA-256 does not match runtime.json: $($pair.Key)"
        }
    }
} finally { $archive.Dispose() }

if ([string]::IsNullOrWhiteSpace($ArtifactId)) {
    $ArtifactId = "mcwebui-direct-cef-$($manifest.runtimeId)-$($manifest.platform)-$($manifest.arch)-$ArtifactRevision"
}
if ($ArtifactId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
    throw 'ArtifactId must be 1-128 safe letters, digits, dot, underscore, and hyphen characters'
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path (Split-Path -Parent $package) ($packageItem.BaseName + '.release.json')
}
$output = [System.IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $output) {
    throw "Release descriptor output already exists; remove it explicitly before regenerating: $output"
}

$descriptor = [ordered]@{
    descriptorVersion = 1
    artifactId = $ArtifactId
    artifactRevision = $ArtifactRevision
    packageFileName = $packageItem.Name
    packageSize = [int64]$packageItem.Length
    runtimePayloadSize = [int64](@($manifest.files) | Measure-Object -Property size -Sum).Sum
    packageSha256 = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToUpperInvariant()
    runtime = [ordered]@{
        schemaVersion = [int]$manifest.schemaVersion
        runtimeId = [string]$manifest.runtimeId
        mcwebuiRuntimeAbi = [int]$manifest.mcwebuiRuntimeAbi
        cefVersion = [string]$manifest.cefVersion
        chromiumVersion = [string]$manifest.chromiumVersion
        platform = [string]$manifest.platform
        arch = [string]$manifest.arch
    }
}
if (-not [string]::IsNullOrWhiteSpace($DownloadUri)) {
    $descriptor['downloadUri'] = $DownloadUri
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
$json = $descriptor | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText($output, $json + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))

Write-Output "descriptor: $output"
Write-Output "artifactId: $ArtifactId"
Write-Output "artifactRevision: $ArtifactRevision"
Write-Output "packageSize: $($packageItem.Length)"
Write-Output "runtimePayloadSize: $($descriptor.runtimePayloadSize)"
Write-Output "packageSha256: $($descriptor.packageSha256)"
Write-Output "downloadConfigured: $(-not [string]::IsNullOrWhiteSpace($DownloadUri))"
