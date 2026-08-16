[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('fresh-download', 'offline-import', 'invalid-override', 'installed-continue', 'cancel-retry')]
    [string]$Scenario,
    [string]$RuntimePackage = '',
    [string]$AcceptanceRoot = '',
    [string]$SessionId = '',
    [switch]$PrepareOnly,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($AcceptanceRoot)) {
    $AcceptanceRoot = Join-Path $repo 'build\manual-acceptance'
}
if ([string]::IsNullOrWhiteSpace($SessionId)) {
    $SessionId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
}
if ($SessionId -notmatch '^[A-Za-z0-9._-]+$') { throw 'SessionId contains unsafe characters' }

$scenarioRoot = Join-Path ([System.IO.Path]::GetFullPath($AcceptanceRoot)) (Join-Path $Scenario $SessionId)
if (Test-Path -LiteralPath $scenarioRoot) {
    throw "Manual acceptance session already exists; choose a new SessionId: $scenarioRoot"
}
$instanceRoot = Join-Path $scenarioRoot 'instance'
$gameDirectory = Join-Path $scenarioRoot 'minecraft'
$logDirectory = Join-Path $scenarioRoot 'logs'
New-Item -ItemType Directory -Force -Path $instanceRoot,$gameDirectory,$logDirectory | Out-Null

$descriptor = (Resolve-Path (Join-Path $repo 'gradle\direct-cef-runtime-r1.release.json')).Path
$descriptorJson = Get-Content -Raw -LiteralPath $descriptor -Encoding UTF8 | ConvertFrom-Json
$expectedRuntime = Join-Path $instanceRoot 'mcwebui\runtime\cef\cef-144.0.33-cb4715c\windows-x86_64'
$requiresPackage = $Scenario -in @('offline-import', 'installed-continue')
$package = $null
if ($requiresPackage) {
    if ([string]::IsNullOrWhiteSpace($RuntimePackage)) {
        throw "$Scenario requires -RuntimePackage pointing to the frozen Runtime R1 ZIP"
    }
    $package = (Resolve-Path -LiteralPath $RuntimePackage).Path
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $repo 'scripts\direct-cef-runtime\verify-frozen-runtime-r1.ps1') -Path $package
    if ($LASTEXITCODE -ne 0) { throw 'Frozen Runtime R1 package verification failed' }
}

if (Test-Path -LiteralPath $expectedRuntime) {
    throw "Isolated scenario unexpectedly contains a standard runtime: $expectedRuntime"
}
$invalidOverride = Join-Path $scenarioRoot 'known-invalid-runtime-do-not-create'

if (-not $SkipBuild) {
    & (Join-Path $repo 'gradlew.bat') frontendBuild ':targets:neoforge-1.21.1:compileJava' --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Manual acceptance build preparation failed' }
}

if ($Scenario -eq 'installed-continue') {
    $installEvidence = Join-Path $scenarioRoot 'preinstall-evidence.json'
    # The existing LOCAL_FIXTURE proof deliberately requires descriptor bytes
    # without a production URL. Derive that temporary test input from the
    # project-owned descriptor while preserving every pinned identity field.
    $fixtureDescriptor = Join-Path $scenarioRoot 'local-fixture.release.json'
    $fixtureJson = Get-Content -Raw -LiteralPath $descriptor -Encoding UTF8 | ConvertFrom-Json
    $fixtureJson.PSObject.Properties.Remove('downloadUri')
    [System.IO.File]::WriteAllText($fixtureDescriptor,
        (($fixtureJson | ConvertTo-Json -Depth 6) + [Environment]::NewLine),
        [System.Text.UTF8Encoding]::new($false))
    $installArgs = @(
        ':targets:neoforge-1.21.1:test',
        '--tests', 'dev.qingmo.mcwebui.nativecef.DirectCefRuntimeFirstRunProofTest',
        '-PmcwebuiProofMode=LOCAL_FIXTURE',
        "-PmcwebuiProofDescriptor=$fixtureDescriptor",
        "-PmcwebuiProofPackage=$package",
        "-PmcwebuiProofInstanceRoot=$instanceRoot",
        "-PmcwebuiProofEvidence=$installEvidence",
        '--no-daemon'
    )
    & (Join-Path $repo 'gradlew.bat') @installArgs
    if ($LASTEXITCODE -ne 0) { throw 'Validated installed-continue preinstallation failed' }
    $proof = Get-Content -Raw -LiteralPath $installEvidence -Encoding UTF8 | ConvertFrom-Json
    if ($proof.status -ne 'PASS' -or -not (Test-Path -LiteralPath (Join-Path $expectedRuntime 'runtime.json'))) {
        throw 'installed-continue did not produce a validated standard runtime'
    }
}

$instructions = switch ($Scenario) {
    'fresh-download' { @('Inspect the initial missing-runtime state.', 'Click Download & Install.', 'Observe progress and button states.', 'Click Continue after completion.', 'Verify the Direct WebScreen, transparency, input, Escape, and Bridge.') }
    'offline-import' { @('Confirm the frozen ZIP path is prefilled.', 'Click Import Package.', 'Observe validation/extraction progress and button states.', 'Click Continue after completion.', 'Verify the Direct WebScreen and Bridge.') }
    'invalid-override' { @('Confirm the invalid override path and repair guidance are visible.', 'Confirm Download and Import are unavailable.', 'Check text at small and normal window sizes.', 'Close with the button and repeat with Escape.') }
    'installed-continue' { @('Confirm the installed state and Continue button.', 'Click Continue without bypassing validation.', 'Verify the Direct WebScreen, transparency, input, Escape, and Bridge.') }
    'cancel-retry' { @('Click Download & Install.', 'Cancel while download or install is active.', 'Confirm the cancelled state and cleanup.', 'Click Retry, then Download & Install again.', 'Continue after success and verify the Direct WebScreen.') }
}

$sourceSha = (& git -C $repo rev-parse HEAD).Trim()
$manualChecks = [ordered]@{}
foreach ($name in @('Fresh Setup','Download button','Progress','Cancel','Retry','Offline import',
        'Invalid override','Continue','ESC / Close','1280x720 layout','1920x1080 layout',
        'Small-window layout','Final WebScreen','Transparency','Input','Bridge')) {
    $manualChecks[$name] = 'NOT_RUN'
}
$info = [ordered]@{
    schemaVersion = 1
    status = 'READY_FOR_USER_ACCEPTANCE'
    scenario = $Scenario
    sessionId = $SessionId
    createdUtc = [DateTime]::UtcNow.ToString('o')
    instanceRoot = $instanceRoot
    gameDirectory = $gameDirectory
    expectedRuntimePath = $expectedRuntime
    sourceGitSha = $sourceSha
    modVersion = '0.1.0-preview.1'
    backend = 'DIRECT_CEF'
    runtimeId = $descriptorJson.runtime.runtimeId
    descriptorPath = $descriptor
    descriptorSha256 = (Get-FileHash -LiteralPath $descriptor -Algorithm SHA256).Hash
    runtimeReleaseUrl = $descriptorJson.downloadUri
    runtimePackage = if ($package) { $package } else { $null }
    invalidOverride = if ($Scenario -eq 'invalid-override') { $invalidOverride } else { $null }
    instructions = $instructions
    manualChecks = $manualChecks
}
$infoPath = Join-Path $scenarioRoot 'acceptance-info.json'
[System.IO.File]::WriteAllText($infoPath,
    (($info | ConvertTo-Json -Depth 6) + [Environment]::NewLine),
    [System.Text.UTF8Encoding]::new($false))

Write-Host ''
Write-Host "MCWebUI manual acceptance scenario prepared: $Scenario"
Write-Host "  session:  $scenarioRoot"
Write-Host "  instance: $instanceRoot"
Write-Host "  game dir: $gameDirectory"
Write-Host "  evidence: $infoPath"
Write-Host 'Manual checks remain NOT_RUN until you inspect and record them.'
Write-Host ''
for ($index = 0; $index -lt $instructions.Count; $index++) {
    Write-Host ("  {0}. {1}" -f ($index + 1), $instructions[$index])
}
Write-Host ''
if ($PrepareOnly) {
    Write-Host 'Preparation complete; Minecraft was not launched.'
    return
}

$runArgs = @(
    ':targets:neoforge-1.21.1:runClient',
    '--no-daemon',
    '-PmcwebuiBrowserBackend=direct-cef',
    '-PmcwebuiDirectCefProof=true',
    '-PmcwebuiManualSetupAcceptance=true',
    "-PmcwebuiDirectCefInstanceRoot=$instanceRoot",
    "-PmcwebuiGameDirectory=$gameDirectory"
)
if ($Scenario -eq 'offline-import') { $runArgs += "-PmcwebuiDirectCefPackage=$package" }
if ($Scenario -eq 'invalid-override') { $runArgs += "-PmcwebuiDirectCefRuntimeDir=$invalidOverride" }

Write-Host 'Launching the isolated NeoForge client. The Setup Screen opens automatically after compatibility probing.'
Write-Host 'Close Minecraft normally when this scenario is finished; the isolated files are retained for review.'
& (Join-Path $repo 'gradlew.bat') @runArgs
if ($LASTEXITCODE -ne 0) {
    throw "Isolated NeoForge client exited with code $LASTEXITCODE; inspect $gameDirectory\logs"
}
