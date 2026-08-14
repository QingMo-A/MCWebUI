[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$RuntimeRoot,
    [string]$OutputDirectory = (Join-Path $env:TEMP 'mcwebui-runtime-packages'),
    [string]$OutputPath = '',
    [switch]$RegenerateManifest,
    [switch]$DeterminismCheck
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$generator = Join-Path $PSScriptRoot 'generate-runtime-manifest.ps1'
$root = (Resolve-Path -LiteralPath $RuntimeRoot).Path.TrimEnd('\')
$rootItem = Get-Item -LiteralPath $root -Force
if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Runtime root cannot be a reparse point: $root"
}

$manifestPath = Join-Path $root 'runtime.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "runtime.json is missing at the runtime root: $manifestPath (generate it first or pass -RegenerateManifest)"
}
if ($RegenerateManifest) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $generator -RuntimeRoot $root
    if ($LASTEXITCODE -ne 0) { throw 'Runtime manifest regeneration failed' }
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.mcwebuiRuntimeAbi -ne 1) {
    throw "runtime.json is not the supported schema v1 / ABI 1 package: $manifestPath"
}
if ([string]::IsNullOrWhiteSpace($manifest.runtimeId) -or
    [string]::IsNullOrWhiteSpace($manifest.platform) -or [string]::IsNullOrWhiteSpace($manifest.arch)) {
    throw "runtime.json is missing identity fields: $manifestPath"
}
if ($null -eq $manifest.entrypoints -or $null -eq $manifest.files -or @($manifest.files).Count -lt 1) {
    throw "runtime.json must declare entrypoints and at least one file: $manifestPath"
}

# The packaged manifest must exactly match the tree: every listed file exists with
# the expected size and SHA-256, and no unlisted file may be present. The package
# never silently regenerates or alters the manifest unless -RegenerateManifest.
$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($record in @($manifest.files)) {
    if ([string]::IsNullOrWhiteSpace($record.path)) { throw 'runtime.json contains an empty file path' }
    $normalized = ([string]$record.path).Replace('\', '/')
    $full = [System.IO.Path]::GetFullPath((Join-Path $root ($normalized.Replace('/', '\'))))
    $prefix = $root + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest file escapes the runtime root: $($record.path)"
    }
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        throw "Manifest file is missing from the runtime tree: $($record.path)"
    }
    $item = Get-Item -LiteralPath $full -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Manifest file cannot be a reparse point: $($record.path)"
    }
    if ([int64]$item.Length -ne [int64]$record.size) {
        throw "Size mismatch for $($record.path): runtime.json says $($record.size), disk has $($item.Length)"
    }
    $actualHash = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualHash -ne ([string]$record.sha256).ToUpperInvariant()) {
        throw "SHA-256 mismatch for $($record.path); the tree does not match runtime.json"
    }
    if (-not $seen.Add($normalized)) {
        throw "runtime.json contains a case-insensitive duplicate path: $($record.path)"
    }
}
foreach ($file in @(Get-ChildItem -LiteralPath $root -File -Recurse -Force)) {
    if (($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Runtime tree cannot contain a reparse-point file: $($file.FullName)"
    }
    if ($file.FullName.Equals($manifestPath, [System.StringComparison]::OrdinalIgnoreCase)) { continue }
    $full = [System.IO.Path]::GetFullPath($file.FullName)
    $relative = $full.Substring(($root + [System.IO.Path]::DirectorySeparatorChar).Length).Replace('\', '/')
    if (-not $seen.Contains($relative)) {
        throw "Unexpected file not listed in runtime.json: $relative"
    }
}
foreach ($entrypoint in @($manifest.entrypoints.native, $manifest.entrypoints.helper,
    $manifest.entrypoints.cef, $manifest.entrypoints.chromeElf)) {
    if (-not $seen.Contains(([string]$entrypoint).Replace('\', '/'))) {
        throw "Required entrypoint is not part of the packaged file set: $entrypoint"
    }
}

$runtimeId = [string]$manifest.runtimeId
$platformArch = "$($manifest.platform)-$($manifest.arch)"
$defaultName = "mcwebui-direct-cef-runtime-$runtimeId-$platformArch.zip"
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $OutputDirectory $defaultName
}
$output = [System.IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
if (Test-Path -LiteralPath $output) {
    throw "Output package already exists; remove it first (deterministic repackaging must not guess): $output"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Write-DeterministicPackage([string]$TargetPath) {
    $fixedTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
    $fs = [System.IO.File]::Open($TargetPath, [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new($fs,
            [System.IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            # Lexicographic entry order, fixed '/' separators, fixed timestamp:
            # byte-identical output for the same tree on the same .NET runtime.
            $names = @(@($manifest.files | ForEach-Object { [string]$_.path }) + 'runtime.json') |
                Sort-Object -Unique
            foreach ($name in $names) {
                $entry = $archive.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = $fixedTime
                $source = if ($name -eq 'runtime.json') { $manifestPath } else { Join-Path $root ($name.Replace('/', '\')) }
                $input = [System.IO.File]::OpenRead($source)
                try {
                    $entryStream = $entry.Open()
                    try { $input.CopyTo($entryStream) } finally { $entryStream.Dispose() }
                } finally { $input.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally { $fs.Dispose() }
}

Write-DeterministicPackage -TargetPath $output
if ($DeterminismCheck) {
    $second = $output + '.determinism-check.zip'
    Write-DeterministicPackage -TargetPath $second
    $firstHash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
    $secondHash = (Get-FileHash -LiteralPath $second -Algorithm SHA256).Hash
    Remove-Item -LiteralPath $second -Force
    if ($firstHash -ne $secondHash) {
        throw 'Determinism check failed: repackaging the same tree produced different bytes'
    }
}

$size = (Get-Item -LiteralPath $output).Length
$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToUpperInvariant()
Write-Output "package: $output"
Write-Output "packageSize: $size"
Write-Output "packageSha256: $hash"
Write-Output "manifestFiles: $(@($manifest.files).Count)"
