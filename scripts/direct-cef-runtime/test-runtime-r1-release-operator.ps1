[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$module = Join-Path $PSScriptRoot 'RuntimeR1ReleaseOperator.psm1'
$operator = Join-Path $PSScriptRoot 'publish-runtime-r1.ps1'
$lock = Get-Content -Raw -LiteralPath (Join-Path $repo 'plans\direct-cef-runtime-r1-lock.json') -Encoding UTF8 | ConvertFrom-Json
Import-Module $module -Force

function State([hashtable]$Overrides = @{}) {
    $state = @{
        Repository='QingMo-A/MCWebUI'; ExpectedRepository='QingMo-A/MCWebUI'
        GitAvailable=$true; GhAvailable=$true; GhAuthenticated=$true
        TrackedClean=$true; OriginMatches=$true; LocalTagExists=$false
        RemoteTagExists=$false; ReleaseExists=$false; AssetExists=$false
    }
    foreach ($key in $Overrides.Keys) { $state[$key] = $Overrides[$key] }
    return $state
}

function Expect-Throw([string]$Name, [string]$Code, [hashtable]$Overrides) {
    try {
        $values = State $Overrides
        Assert-RuntimeR1OperatorState @values
        throw "Expected $Code"
    } catch {
        if ($_.Exception.Message -notmatch [regex]::Escape($Code)) { throw }
    }
    Write-Host "PASS $Name -> $Code"
}

function Invoke-ExpectedFailure([string]$Name, [string]$Path, [string]$Code) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $operator -Path $Path 2>&1) | Out-String
        $exit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($exit -eq 0 -or $output -notmatch [regex]::Escape($Code)) {
        throw "$Name failed: exit=$exit expected=$Code output=$output"
    }
    Write-Host "PASS $Name -> $Code"
}

$temp = Join-Path $env:TEMP ('mcwebui-r1-operator-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $cleanState = State
    Assert-RuntimeR1OperatorState @cleanState
    Write-Host 'PASS clean preflight state'
    Expect-Throw 'wrong repository' 'WRONG_REPOSITORY' @{Repository='someone/fork'}
    Expect-Throw 'gh missing' 'GH_CLI_NOT_FOUND' @{GhAvailable=$false}
    Expect-Throw 'dirty tracked tree' 'TRACKED_WORKTREE_DIRTY' @{TrackedClean=$false}
    Expect-Throw 'tag exists' 'LOCAL_TAG_ALREADY_EXISTS' @{LocalTagExists=$true}
    Expect-Throw 'release exists' 'RELEASE_ALREADY_EXISTS' @{ReleaseExists=$true}

    if ((Get-RuntimeR1OperatorMode -PublishRequested $false) -ne 'DRY_RUN') { throw 'Default mode is not DRY_RUN' }
    if ((Get-RuntimeR1OperatorMode -PublishRequested $true) -ne 'PUBLISH') { throw 'Explicit publish mode planning failed' }
    Write-Host 'PASS default dry-run and explicit publish mode'

    $plan = New-RuntimeR1PublishPlan -Lock $lock -Path 'X:\frozen.zip' -Repository 'QingMo-A/MCWebUI'
    if ($plan.Tag -cne 'direct-cef-runtime-r1' -or
        $plan.Target -cne [string]$lock.sourceGitSha -or $plan.Asset -cne [string]$lock.artifact.filename -or
        $plan.Size -ne [int64]$lock.artifact.size -or $plan.Sha256 -cne [string]$lock.artifact.sha256 -or
        $plan.Arguments -contains '--clobber') { throw 'Frozen identity publish plan mismatch' }
    Write-Host 'PASS exact frozen identity publish plan without --clobber'

    Invoke-ExpectedFailure 'missing ZIP' (Join-Path $temp 'missing.zip') 'FILE_NOT_FOUND'
    $bad = Join-Path $temp ([string]$lock.artifact.filename)
    [IO.File]::WriteAllText($bad, 'not frozen bytes', [Text.UTF8Encoding]::new($false))
    Invoke-ExpectedFailure 'bad frozen ZIP' $bad 'SIZE_MISMATCH'
    Write-Host 'GUARDED RUNTIME RELEASE OPERATOR TESTS: PASS (10/10)'
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
