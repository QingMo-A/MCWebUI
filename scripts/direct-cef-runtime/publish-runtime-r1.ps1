[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Path,
    [switch]$Publish,
    [string]$Repository = 'QingMo-A/MCWebUI'
)

# Guarded Runtime R1 operator. Default execution is a side-effect-free dry run.
# Only an explicit -Publish reaches the release creation branch. This script
# consumes the frozen archive and can never build, manifest, or repackage it.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$expectedRepository = 'QingMo-A/MCWebUI'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$lockPath = Join-Path $repoRoot 'plans\direct-cef-runtime-r1-lock.json'
$verifier = Join-Path $PSScriptRoot 'verify-runtime-release-inputs.ps1'
$module = Join-Path $PSScriptRoot 'RuntimeR1ReleaseOperator.psm1'

# The frozen byte verifier is deliberately first, before tool/auth/repository
# checks, in both dry-run and publish modes.
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifier -Path $Path
if ($LASTEXITCODE -ne 0) { throw "FROZEN_RUNTIME_PRECHECK_FAILED: verifier exit code $LASTEXITCODE" }

Import-Module $module -Force
$lock = Get-Content -Raw -LiteralPath $lockPath -Encoding UTF8 | ConvertFrom-Json
$plan = New-RuntimeR1PublishPlan -Lock $lock -Path (Resolve-Path -LiteralPath $Path).Path -Repository $Repository
$mode = Get-RuntimeR1OperatorMode -PublishRequested ([bool]$Publish)

$gitCommand = Get-Command git -ErrorAction SilentlyContinue
$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) { throw 'GIT_NOT_FOUND' }
if ($null -eq $ghCommand) { throw 'GH_CLI_NOT_FOUND' }

function Invoke-Captured([string]$Command, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $Command @Arguments 2>&1) | Out-String
        return [pscustomobject]@{ ExitCode=$LASTEXITCODE; Output=$output.Trim() }
    } finally { $ErrorActionPreference = $previousPreference }
}

$tracked = Invoke-Captured $gitCommand.Source @('-C', $repoRoot, 'status', '--porcelain', '--untracked-files=no')
if ($tracked.ExitCode -ne 0) { throw "GIT_STATUS_FAILED: $($tracked.Output)" }
$operatorHeadResult = Invoke-Captured $gitCommand.Source @('-C', $repoRoot, 'rev-parse', 'HEAD')
if ($operatorHeadResult.ExitCode -ne 0) { throw "GIT_HEAD_FAILED: $($operatorHeadResult.Output)" }
$operatorHead = $operatorHeadResult.Output
$originResult = Invoke-Captured $gitCommand.Source @('-C', $repoRoot, 'remote', 'get-url', 'origin')
if ($originResult.ExitCode -ne 0) { throw "GIT_ORIGIN_FAILED: $($originResult.Output)" }
$normalizedOrigin = $originResult.Output.TrimEnd('/').ToLowerInvariant()
$originMatches = $normalizedOrigin -in @('https://github.com/qingmo-a/mcwebui.git',
    'https://github.com/qingmo-a/mcwebui', 'git@github.com:qingmo-a/mcwebui.git')

$auth = Invoke-Captured $ghCommand.Source @('auth', 'status', '--hostname', 'github.com')
$localTag = Invoke-Captured $gitCommand.Source @('-C', $repoRoot, 'tag', '--list', $plan.Tag)
$remoteTag = Invoke-Captured $gitCommand.Source @('-C', $repoRoot, 'ls-remote', '--tags', 'origin', "refs/tags/$($plan.Tag)", "refs/tags/$($plan.Tag)^{}")
if ($localTag.ExitCode -ne 0 -or $remoteTag.ExitCode -ne 0) { throw 'TAG_CHECK_FAILED' }

$release = Invoke-Captured $ghCommand.Source @('release', 'view', $plan.Tag, '--repo', $Repository,
    '--json', 'tagName,url,assets')
$releaseExists = $release.ExitCode -eq 0
$assetExists = $false
if ($releaseExists) {
    try {
        $releaseData = $release.Output | ConvertFrom-Json
        $assetExists = @($releaseData.assets | Where-Object { $_.name -ceq $plan.Asset }).Count -gt 0
    } catch { throw "RELEASE_QUERY_INVALID: $($_.Exception.Message)" }
}

Assert-RuntimeR1OperatorState -Repository $Repository -ExpectedRepository $expectedRepository `
    -GitAvailable $true -GhAvailable $true -GhAuthenticated ($auth.ExitCode -eq 0) `
    -TrackedClean ([string]::IsNullOrWhiteSpace($tracked.Output)) -OriginMatches $originMatches `
    -LocalTagExists (-not [string]::IsNullOrWhiteSpace($localTag.Output)) `
    -RemoteTagExists (-not [string]::IsNullOrWhiteSpace($remoteTag.Output)) `
    -ReleaseExists $releaseExists -AssetExists $assetExists

if ($mode -eq 'DRY_RUN') {
    Write-Host 'RUNTIME R1 RELEASE DRY RUN PASS'
    Write-Host "Repository: $Repository"
    Write-Host "Tag: $($plan.Tag)"
    Write-Host "Asset: $($plan.Asset)"
    Write-Host "Size: $($plan.Size)"
    Write-Host "SHA: $($plan.Sha256)"
    Write-Host "Runtime source SHA: $($plan.Target)"
    Write-Host "Operator HEAD: $operatorHead"
    Write-Host 'NO REMOTE CHANGES WERE MADE'
    return
}

# Last-moment byte identity check immediately before the first remote mutation.
$finalItem = Get-Item -LiteralPath $Path
$finalHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
if ($finalItem.Name -cne $plan.Asset -or [int64]$finalItem.Length -ne $plan.Size -or $finalHash -ne $plan.Sha256) {
    throw 'PRE_UPLOAD_IDENTITY_CHANGED: frozen file changed after preflight'
}

$create = Invoke-Captured $ghCommand.Source $plan.Arguments
if ($create.ExitCode -ne 0) { throw "RELEASE_CREATE_FAILED: $($create.Output)" }

$published = Invoke-Captured $ghCommand.Source @('release', 'view', $plan.Tag, '--repo', $Repository,
    '--json', 'tagName,url,assets')
if ($published.ExitCode -ne 0) { throw "REMOTE_RELEASE_VERIFICATION_FAILED: $($published.Output)" }
$publishedData = $published.Output | ConvertFrom-Json

$publishedTag = Invoke-Captured $gitCommand.Source @('-C', $repoRoot, 'ls-remote', '--tags', 'origin',
    "refs/tags/$($plan.Tag)", "refs/tags/$($plan.Tag)^{}")
if ($publishedTag.ExitCode -ne 0) { throw "REMOTE_TAG_TARGET_UNRESOLVED: $($publishedTag.Output)" }
$remoteTagCommit = Resolve-RuntimeR1RemoteTagTarget -LsRemoteOutput $publishedTag.Output -Tag $plan.Tag
$asset = Assert-RuntimeR1PublishedState -ReleaseData $publishedData -Plan $plan -RemoteTagCommit $remoteTagCommit

$downloadRoot = Join-Path $env:TEMP ('mcwebui-runtime-r1-remote-verify-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $downloadRoot | Out-Null
    $download = Invoke-Captured $ghCommand.Source @('release', 'download', $plan.Tag, '--repo', $Repository,
        '--pattern', $plan.Asset, '--dir', $downloadRoot)
    if ($download.ExitCode -ne 0) { throw "REMOTE_ASSET_VERIFICATION_FAILED: download failed: $($download.Output)" }
    $remoteFile = Join-Path $downloadRoot $plan.Asset
    $remoteHash = if (Test-Path -LiteralPath $remoteFile -PathType Leaf) {
        (Get-FileHash -LiteralPath $remoteFile -Algorithm SHA256).Hash.ToUpperInvariant()
    } else { '' }
    if ($remoteHash -ne $plan.Sha256) {
        throw 'REMOTE_ASSET_VERIFICATION_FAILED: downloaded SHA mismatch; investigate manually, do not delete or overwrite'
    }
} finally {
    if (Test-Path -LiteralPath $downloadRoot) {
        try { Remove-Item -LiteralPath $downloadRoot -Recurse -Force -ErrorAction Stop }
        catch { Write-Warning "Remote verification temp cleanup failed: $($_.Exception.Message)" }
    }
}

Write-Host 'OFFICIAL RUNTIME R1 RELEASE PUBLISHED'
Write-Host "Tag: $($plan.Tag)"
Write-Host "Remote tag target: $remoteTagCommit"
Write-Host 'Runtime source provenance: VERIFIED'
Write-Host "Release URL: $($publishedData.url)"
Write-Host "Asset URL: $($asset.url)"
Write-Host "Size: $($plan.Size)"
Write-Host "Asset SHA: $remoteHash"
