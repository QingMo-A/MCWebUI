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

- CEF $($Lock.runtime.cefVersion)
- Chromium $($Lock.runtime.chromiumVersion)
- $($Lock.runtime.platform) $($Lock.runtime.arch)
- Artifact revision $($Lock.artifact.revision)
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

function Resolve-RuntimeR1RemoteTagTarget {
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$LsRemoteOutput,
        [Parameter(Mandatory)][string]$Tag
    )
    $directRef = "refs/tags/$Tag"
    $peeledRef = "$directRef^{}"
    $direct = @()
    $peeled = @()
    foreach ($line in @($LsRemoteOutput -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
        if ($line -notmatch '^([0-9a-fA-F]{40})\s+(.+)$') {
            throw "REMOTE_TAG_TARGET_UNRESOLVED: invalid ls-remote output: $line"
        }
        $sha = $Matches[1].ToLowerInvariant()
        $ref = $Matches[2]
        if ($ref -ceq $directRef) { $direct += $sha }
        elseif ($ref -ceq $peeledRef) { $peeled += $sha }
        else { throw "REMOTE_TAG_TARGET_UNRESOLVED: unexpected ref $ref" }
    }
    if ($direct.Count -ne 1 -or $peeled.Count -gt 1) {
        throw "REMOTE_TAG_TARGET_UNRESOLVED: direct=$($direct.Count) peeled=$($peeled.Count)"
    }
    if ($peeled.Count -eq 1) { return $peeled[0] }
    return $direct[0]
}

function Assert-RuntimeR1PublishedState {
    param(
        [Parameter(Mandatory)]$ReleaseData,
        [Parameter(Mandatory)]$Plan,
        [Parameter(Mandatory)][string]$RemoteTagCommit
    )
    if ($RemoteTagCommit -notmatch '^[0-9a-fA-F]{40}$') {
        throw 'REMOTE_TAG_TARGET_UNRESOLVED: resolved value is not a commit SHA'
    }
    if ($RemoteTagCommit -cne ([string]$Plan.Target).ToLowerInvariant()) {
        throw "REMOTE_TAG_TARGET_MISMATCH: expected $($Plan.Target), got $RemoteTagCommit"
    }
    if ([string]$ReleaseData.tagName -cne [string]$Plan.Tag) {
        throw "RELEASE_METADATA_MISMATCH: expected tag $($Plan.Tag), got $($ReleaseData.tagName)"
    }
    $assets = @($ReleaseData.assets | Where-Object { $_.name -ceq $Plan.Asset })
    if ($assets.Count -ne 1) {
        throw "REMOTE_ASSET_VERIFICATION_FAILED: expected exactly one $($Plan.Asset), got $($assets.Count)"
    }
    if ([int64]$assets[0].size -ne [int64]$Plan.Size) {
        throw "REMOTE_ASSET_VERIFICATION_FAILED: expected size $($Plan.Size), got $($assets[0].size)"
    }
    return $assets[0]
}

function Get-RuntimeR1OperatorMode {
    param([Parameter(Mandatory)][bool]$PublishRequested)
    if ($PublishRequested) { return 'PUBLISH' }
    return 'DRY_RUN'
}

Export-ModuleMember -Function Assert-RuntimeR1OperatorState, New-RuntimeR1PublishPlan, `
    Get-RuntimeR1OperatorMode, Resolve-RuntimeR1RemoteTagTarget, Assert-RuntimeR1PublishedState
