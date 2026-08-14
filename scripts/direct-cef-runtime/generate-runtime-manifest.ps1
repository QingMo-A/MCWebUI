[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$RuntimeRoot,
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $RuntimeRoot).Path.TrimEnd('\')
$rootItem = Get-Item -LiteralPath $root -Force
if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Runtime root cannot be a reparse point: $root"
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $root 'runtime.json'
}
$output = [System.IO.Path]::GetFullPath($OutputPath)
if (-not $output.Equals((Join-Path $root 'runtime.json'), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'The Phase A manifest must be written as <runtime-root>\runtime.json'
}

$entrypoints = [ordered]@{
    native = 'mcwebui-direct-cef.dll'
    helper = 'mcwebui-cef-helper.exe'
    cef = 'libcef.dll'
    chromeElf = 'chrome_elf.dll'
}

foreach ($relative in $entrypoints.Values) {
    $candidate = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Required Direct CEF entrypoint is missing: $relative"
    }
}

$forbiddenDirectories = @('cache', 'tmp', 'temp', '.staging')
$directories = @(Get-ChildItem -LiteralPath $root -Directory -Recurse -Force)
foreach ($directory in $directories) {
    if (($directory.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Runtime manifests cannot include a reparse-point directory: $($directory.FullName)"
    }
    if ($forbiddenDirectories -contains $directory.Name.ToLowerInvariant()) {
        throw "Runtime manifests cannot include cache/temp directory: $($directory.FullName)"
    }
}

$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
$records = @()
foreach ($file in @(Get-ChildItem -LiteralPath $root -File -Recurse -Force)) {
    if ($file.FullName.Equals($output, [System.StringComparison]::OrdinalIgnoreCase)) { continue }
    if (($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Runtime manifests cannot include a reparse-point file: $($file.FullName)"
    }
    $full = [System.IO.Path]::GetFullPath($file.FullName)
    $prefix = $root + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Runtime file escaped the runtime root: $full"
    }
    $relative = $full.Substring($prefix.Length).Replace('\', '/')
    if (-not $seen.Add($relative)) {
        throw "Runtime contains a case-insensitive duplicate path: $relative"
    }
    $records += [pscustomobject]@{
        path = $relative
        size = [int64]$file.Length
        sha256 = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToUpperInvariant()
    }
}

$recordList = [System.Collections.Generic.List[object]]::new()
foreach ($record in $records) { $recordList.Add($record) }
$recordList.Sort([System.Comparison[object]]{
    param($left, $right)
    return [System.StringComparer]::Ordinal.Compare($left.path, $right.path)
})
$records = @($recordList)
foreach ($relative in $entrypoints.Values) {
    if (-not $seen.Contains($relative)) {
        throw "Required entrypoint is not part of the manifest file set: $relative"
    }
}

$manifest = [ordered]@{
    schemaVersion = 1
    runtimeId = 'cef-144.0.33-cb4715c'
    mcwebuiRuntimeAbi = 1
    cefVersion = '144.0.33'
    chromiumVersion = '144.0.7559.259'
    platform = 'windows'
    arch = 'x86_64'
    entrypoints = $entrypoints
    files = $records
}

$json = $manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($output, $json + [Environment]::NewLine,
    (New-Object System.Text.UTF8Encoding($false)))
Write-Output $output
