Set-StrictMode -Version Latest

function Assert-RuntimeR1OperatorState {
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][string]$ExpectedRepository,
        [Parameter(Mandatory)][bool]$GitAvailable,
        [Parameter(Mandatory)][bool]$GhAvailable,
        [Parameter(Mandatory)][bool]$GhAuthenticated,
        [Parameter(Mandatory)][bool]$TrackedClean,
        [Parameter(Mandatory)][bool]$OriginMatches,
        [Parameter(Mandatory)][bool]$LocalTagExists,
        [Parameter(Mandatory)][bool]$RemoteTagExists,
        [Parameter(Mandatory)][bool]$ReleaseExists,
        [Parameter(Mandatory)][bool]$AssetExists
    )
    if ($Repository -cne $ExpectedRepository) { throw "WRONG_REPOSITORY: expected $ExpectedRepository, got $Repository" }
    if (-not $GitAvailable) { throw 'GIT_NOT_FOUND' }
    if (-not $GhAvailable) { throw 'GH_CLI_NOT_FOUND' }
    if (-not $GhAuthenticated) { throw 'GH_AUTH_REQUIRED' }
    if (-not $TrackedClean) { throw 'TRACKED_WORKTREE_DIRTY' }
    if (-not $OriginMatches) { throw "ORIGIN_REPOSITORY_MISMATCH: origin is not $ExpectedRepository" }
    if ($LocalTagExists) { throw 'LOCAL_TAG_ALREADY_EXISTS: direct-cef-runtime-r1' }
    if ($RemoteTagExists) { throw 'REMOTE_TAG_ALREADY_EXISTS: direct-cef-runtime-r1' }
    if ($AssetExists) { throw 'ASSET_ALREADY_EXISTS: frozen Runtime R1 asset already exists' }
    if ($ReleaseExists) { throw 'RELEASE_ALREADY_EXISTS: direct-cef-runtime-r1' }
}

function New-RuntimeR1PublishPlan {
    param(
        [Parameter(Mandatory)]$Lock,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Repository
    )
    $tag = 'direct-cef-runtime-r1'
    $title = 'MCWebUI Direct CEF Runtime R1'
    $notes = @"
Runtime-only external package for MCWebUI Direct CEF.

- CEF 144.0.33
- Chromium 144.0.7559.259
- Windows x86_64
- Artifact revision 1
- SHA-256: $($Lock.artifact.sha256)
- MCWebUI mod itself is not included
- Current automated GPU compatibility evidence is NVIDIA-only; GPU vendor is not a hardcoded support gate
"@
    $arguments = @('release', 'create', $tag, $Path, '--repo', $Repository,
        '--target', [string]$Lock.sourceGitSha, '--title', $title, '--notes', $notes)
    if ($arguments -contains '--clobber') { throw 'INTERNAL_PLAN_ERROR: --clobber is forbidden' }
    return [pscustomobject]@{
        Tag = $tag
        Target = [string]$Lock.sourceGitSha
        Title = $title
        Notes = $notes
        Arguments = $arguments
        Asset = [string]$Lock.artifact.filename
        Size = [int64]$Lock.artifact.size
        Sha256 = ([string]$Lock.artifact.sha256).ToUpperInvariant()
    }
}

function Get-RuntimeR1OperatorMode {
    param([Parameter(Mandatory)][bool]$PublishRequested)
    if ($PublishRequested) { return 'PUBLISH' }
    return 'DRY_RUN'
}

Export-ModuleMember -Function Assert-RuntimeR1OperatorState, New-RuntimeR1PublishPlan, Get-RuntimeR1OperatorMode
