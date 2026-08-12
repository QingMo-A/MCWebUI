[CmdletBinding()]
param(
    [string]$CacheRoot = (Join-Path $env:TEMP 'mcwebui-frame-proof'),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$shared = Join-Path $PSScriptRoot '..\frame-proof\prepare-cef-sdk.ps1'
if (-not (Test-Path -LiteralPath $shared)) {
    throw "Shared CEF preparation script is missing: $shared"
}

& $shared -CacheRoot $CacheRoot -Force:$Force

