[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Path
)

# Mandatory upload precheck entrypoint. No release/upload implementation belongs
# here; a successful return only proves that the local bytes match the R1 lock.
$ErrorActionPreference = 'Stop'
$verifier = Join-Path $PSScriptRoot 'verify-frozen-runtime-r1.ps1'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifier -Path $Path
if ($LASTEXITCODE -ne 0) {
    throw "FROZEN_RUNTIME_PRECHECK_FAILED: verifier exit code $LASTEXITCODE"
}
Write-Host 'FROZEN RUNTIME UPLOAD PRECHECK: PASS'
