[CmdletBinding()]
param(
    [string]$CacheRoot = (Join-Path $env:TEMP 'mcwebui-frame-proof'),
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$url = 'https://mcef-download.cinemamod.com/cef-builds/5845/cef_binary_116.0.27%2Bgd8c85ac%2Bchromium-116.0.5845.190_windows64.tar.bz2'
$expectedSha256 = '65FDB9117AE8578F2C3E208AB5EEE7A19A1E4F83EECB3D0CB712AB87E128D0A1'
$archive = Join-Path $CacheRoot 'cef_binary_116.0.27+gd8c85ac+chromium-116.0.5845.190_windows64.tar.bz2'
$sdk = Join-Path $CacheRoot 'cef_binary_116.0.27+gd8c85ac+chromium-116.0.5845.190_windows64'

New-Item -ItemType Directory -Force -Path $CacheRoot | Out-Null
if ($Force -or -not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri $url -Headers @{ 'User-Agent' = 'MCWebUI-frame-proof/1' } -OutFile $archive
}
$actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actual -ne $expectedSha256) {
    throw "CEF SDK SHA256 mismatch: expected $expectedSha256, got $actual"
}
if ($Force -and (Test-Path -LiteralPath $sdk)) {
    Remove-Item -LiteralPath $sdk -Recurse -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'include\cef_api_hash.h'))) {
    tar -xf $archive -C $CacheRoot
}
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'include\cef_api_hash.h'))) {
    throw "CEF SDK extraction did not produce $sdk"
}
Write-Output $sdk
