[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Path,
    [string]$LockPath = ''
)

# Read-only upload precheck. This script never builds, extracts, repacks, or
# modifies the supplied archive. The tracked lock defines the expected bytes;
# runtime.json is validated against that lock, never the other way around.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($LockPath)) {
    $LockPath = Join-Path $PSScriptRoot '..\..\plans\direct-cef-runtime-r1-lock.json'
}

function Fail([string]$Code, [string]$Detail) {
    throw "${Code}: $Detail"
}

function Normalize-Hash([object]$Value, [string]$Field) {
    $hash = ([string]$Value).ToUpperInvariant()
    if ($hash -notmatch '^[0-9A-F]{64}$') { Fail 'LOCK_INVALID' "$Field is not SHA-256" }
    return $hash
}

function Assert-SafePath([string]$Value, [string]$Context) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Contains('\') -or
        $Value.StartsWith('/') -or $Value.Contains(':') -or $Value.Contains([char]0)) {
        Fail 'UNSAFE_ENTRY_PATH' "$Context path is unsafe: $Value"
    }
    $parts = $Value.Split('/')
    if ($parts.Count -lt 1 -or @($parts | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
        Fail 'UNSAFE_ENTRY_PATH' "$Context path is unsafe: $Value"
    }
}

function Get-EntryHash([System.IO.Compression.ZipArchiveEntry]$Entry) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    $stream = $Entry.Open()
    try {
        return ([System.BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '')
    } finally {
        $stream.Dispose()
        $algorithm.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $LockPath -PathType Leaf)) { Fail 'LOCK_NOT_FOUND' $LockPath }
if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail 'FILE_NOT_FOUND' $Path }

try { $lock = Get-Content -Raw -LiteralPath $LockPath -Encoding UTF8 | ConvertFrom-Json }
catch { Fail 'LOCK_INVALID' $_.Exception.Message }
if ([int]$lock.lockVersion -ne 1 -or [string]$lock.status -ne 'FROZEN') {
    Fail 'LOCK_INVALID' 'Expected lockVersion=1 and status=FROZEN'
}

$item = Get-Item -LiteralPath $Path
if ($item.Name -cne [string]$lock.artifact.filename) {
    Fail 'FILENAME_MISMATCH' "expected $($lock.artifact.filename), got $($item.Name)"
}
if ([int64]$item.Length -ne [int64]$lock.artifact.size) {
    Fail 'SIZE_MISMATCH' "expected $($lock.artifact.size), got $($item.Length)"
}
$packageHash = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
$expectedPackageHash = Normalize-Hash $lock.artifact.sha256 'artifact.sha256'
if ($packageHash -ne $expectedPackageHash) {
    Fail 'PACKAGE_HASH_MISMATCH' "expected $expectedPackageHash, got $packageHash"
}

Add-Type -AssemblyName System.IO.Compression
$file = [System.IO.File]::Open($item.FullName, [System.IO.FileMode]::Open,
    [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
try {
    $archive = [System.IO.Compression.ZipArchive]::new($file,
        [System.IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        $entries = New-Object 'System.Collections.Generic.Dictionary[string,System.IO.Compression.ZipArchiveEntry]' ([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $archive.Entries) {
            Assert-SafePath $entry.FullName 'ZIP'
            if ($entry.FullName.EndsWith('/')) { Fail 'ENTRY_SET_MISMATCH' "directory entry is not allowed: $($entry.FullName)" }
            if ($entries.ContainsKey($entry.FullName)) { Fail 'DUPLICATE_ENTRY' $entry.FullName }
            $entries.Add($entry.FullName, $entry)
        }
        if (-not $entries.ContainsKey('runtime.json')) { Fail 'MANIFEST_MISSING' 'runtime.json' }

        $manifestEntry = $entries['runtime.json']
        if ($manifestEntry.Length -gt 16777216) { Fail 'MANIFEST_INVALID' 'runtime.json exceeds 16 MiB' }
        $reader = [System.IO.StreamReader]::new($manifestEntry.Open(), [System.Text.Encoding]::UTF8,
            $true, 4096, $false)
        try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
        try { $manifest = $manifestText | ConvertFrom-Json }
        catch { Fail 'MANIFEST_INVALID' $_.Exception.Message }

        $identityMatches = [int]$manifest.schemaVersion -eq 1 -and
            [string]$manifest.runtimeId -ceq [string]$lock.runtime.runtimeId -and
            [int]$manifest.mcwebuiRuntimeAbi -eq [int]$lock.runtime.runtimeAbi -and
            [string]$manifest.cefVersion -ceq [string]$lock.runtime.cefVersion -and
            [string]$manifest.chromiumVersion -ceq [string]$lock.runtime.chromiumVersion -and
            [string]$manifest.platform -ceq [string]$lock.runtime.platform -and
            [string]$manifest.arch -ceq [string]$lock.runtime.arch
        if (-not $identityMatches) { Fail 'MANIFEST_IDENTITY_MISMATCH' 'runtime.json differs from the tracked lock' }

        $records = @($manifest.files)
        if ($records.Count -ne [int]$lock.artifact.fileCount) {
            Fail 'FILE_COUNT_MISMATCH' "manifest payload count expected $($lock.artifact.fileCount), got $($records.Count)"
        }
        # fileCount means manifest files[] payload records. runtime.json is the
        # one additional ZIP entry and is intentionally excluded from that count.
        if ($entries.Count -ne ($records.Count + 1)) {
            Fail 'ENTRY_SET_MISMATCH' "expected $($records.Count + 1) ZIP entries, got $($entries.Count)"
        }

        $manifestPaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
        [int64]$payloadSize = 0
        foreach ($record in $records) {
            $relative = [string]$record.path
            Assert-SafePath $relative 'manifest'
            if (-not $manifestPaths.Add($relative)) { Fail 'DUPLICATE_MANIFEST_PATH' $relative }
            if (-not $entries.ContainsKey($relative)) { Fail 'PAYLOAD_MISSING' $relative }
            $entry = $entries[$relative]
            if ([int64]$entry.Length -ne [int64]$record.size) {
                Fail 'PAYLOAD_SIZE_MISMATCH' "$relative expected $($record.size), got $($entry.Length)"
            }
            $expectedHash = Normalize-Hash $record.sha256 "manifest.files[$relative].sha256"
            $actualHash = Get-EntryHash $entry
            if ($actualHash -ne $expectedHash) {
                Fail 'PAYLOAD_HASH_MISMATCH' "$relative expected $expectedHash, got $actualHash"
            }
            $payloadSize += [int64]$record.size
        }
        if ($payloadSize -ne [int64]$lock.artifact.unpackedSize) {
            Fail 'UNPACKED_SIZE_MISMATCH' "expected $($lock.artifact.unpackedSize), got $payloadSize"
        }

        foreach ($notice in @($lock.requiredNotices)) {
            $noticePath = [string]$notice
            if (-not $entries.ContainsKey($noticePath) -or -not $manifestPaths.Contains($noticePath)) {
                Fail 'NOTICE_MISSING' $noticePath
            }
        }

        foreach ($property in $lock.entrypoints.PSObject.Properties) {
            $expected = $property.Value
            $entryPath = [string]$expected.path
            if (-not $entries.ContainsKey($entryPath) -or -not $manifestPaths.Contains($entryPath)) {
                Fail 'ENTRYPOINT_MISSING' $entryPath
            }
            $entry = $entries[$entryPath]
            if ([int64]$entry.Length -ne [int64]$expected.size) {
                Fail 'ENTRYPOINT_SIZE_MISMATCH' "$entryPath expected $($expected.size), got $($entry.Length)"
            }
            $expectedHash = Normalize-Hash $expected.sha256 "entrypoints.$($property.Name).sha256"
            $actualHash = Get-EntryHash $entry
            if ($actualHash -ne $expectedHash) {
                Fail 'ENTRYPOINT_HASH_MISMATCH' "$entryPath expected $expectedHash, got $actualHash"
            }
            $manifestPath = [string]$manifest.entrypoints.($property.Name)
            if ($manifestPath -cne $entryPath) {
                Fail 'ENTRYPOINT_IDENTITY_MISMATCH' "$($property.Name) expected $entryPath, manifest has $manifestPath"
            }
        }
    } finally { $archive.Dispose() }
} finally { $file.Dispose() }

Write-Host 'RUNTIME R1 RELEASE INPUT VERIFIED'
Write-Host "Filename: $($lock.artifact.filename)"
Write-Host "Size: $($lock.artifact.size)"
Write-Host "SHA-256: $expectedPackageHash"
Write-Host "Runtime ID: $($lock.runtime.runtimeId)"
Write-Host "Artifact revision: $($lock.artifact.revision)"
Write-Host "Files: $($lock.artifact.fileCount)"
Write-Host "Source SHA: $($lock.sourceGitSha)"
Write-Host 'UPLOAD BYTES ARE FROZEN'
