[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$McefSource,
    [Parameter(Mandatory)][string]$McefPatch,
    [Parameter(Mandatory)][string]$JcefPatch,
    [string]$OutputJar,
    [string]$Gradle = './gradlew.bat'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $McefSource '.git'))) {
    throw "MCEF source is not a git checkout: $McefSource"
}
foreach ($patch in @($McefPatch, $JcefPatch)) {
    if (-not (Test-Path -LiteralPath $patch -PathType Leaf)) { throw "Patch not found: $patch" }
}
Write-Host "MCEF proof source: $McefSource"
Push-Location $McefSource
try {
    git submodule update --init --recursive common/java-cef
    if ($LASTEXITCODE -ne 0) { throw 'Unable to initialize the pinned java-cef submodule' }
    git apply --check $McefPatch
    if ($LASTEXITCODE -ne 0) { throw 'MCEF patch does not apply cleanly' }
    git -C common/java-cef apply --check $JcefPatch
    if ($LASTEXITCODE -ne 0) { throw 'JCEF patch does not apply cleanly' }
    git apply $McefPatch
    git -C common/java-cef apply $JcefPatch
    & $Gradle --no-daemon :common:jar :neoforge:jar
    if ($LASTEXITCODE -ne 0) {
        throw 'MCEF proof build failed. The pinned upstream currently combines Fabric Loom 1.7-SNAPSHOT with Gradle 8.8; verify a compatible Loom/Gradle toolchain instead of silently changing source revisions.'
    }
    if ($OutputJar) {
        $candidate = Get-ChildItem -LiteralPath (Join-Path $McefSource 'neoforge\build\libs') -Filter '*.jar' |
            Where-Object { $_.Name -notmatch 'sources|dev|shadow' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $candidate) { throw 'MCEF build completed without a NeoForge jar' }
        Copy-Item -LiteralPath $candidate.FullName -Destination $OutputJar -Force
        Get-FileHash -LiteralPath $OutputJar -Algorithm SHA256
    }
}
finally { Pop-Location }
