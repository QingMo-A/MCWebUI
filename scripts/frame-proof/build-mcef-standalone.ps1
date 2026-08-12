[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$McefSource,
    [Parameter(Mandatory)][string]$McefPatch,
    [Parameter(Mandatory)][string]$JcefPatch,
    [Parameter(Mandatory)][string]$OutputJar,
    [string]$WorkDir = '',
    [string]$Gradle = './gradlew.bat'
)

$ErrorActionPreference = 'Stop'

function Invoke-Git([string]$Directory, [string[]]$Arguments) {
    & git -C $Directory @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed in $Directory" }
}

function Ensure-Patch([string]$Directory, [string]$Patch, [string[]]$Markers, [string]$Name) {
    $oldErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & git -C $Directory apply --check -- $Patch 2>$null | Out-Null
    $checkCode = $LASTEXITCODE
    $ErrorActionPreference = $oldErrorAction
    if ($checkCode -eq 0) {
        Invoke-Git $Directory @('apply', '--', $Patch)
        return
    }
    if (-not (($Markers | Where-Object { Select-String -Path $_ -Pattern 'ExternalBeginFrame|externalBeginFrameEnabled|supportsExternalBeginFrame' -Quiet }).Count -gt 0)) {
        throw "$Name patch is neither applicable nor already present: $Patch"
    }
    Write-Host "$Name patch already applied in external checkout."
}

if (-not (Test-Path -LiteralPath (Join-Path $McefSource '.git'))) {
    throw "MCEF source is not a git checkout: $McefSource"
}
foreach ($patch in @($McefPatch, $JcefPatch)) {
    if (-not (Test-Path -LiteralPath $patch -PathType Leaf)) { throw "Patch not found: $patch" }
}

$mcefCommit = (& git -C $McefSource rev-parse HEAD).Trim()
if ($mcefCommit -ne 'c89e242092b11be9a10ee9ffebecc7f9f5b55c0a') {
    throw "Unexpected MCEF source revision: $mcefCommit"
}
$jcefSource = Join-Path $McefSource 'common/java-cef'
Invoke-Git $McefSource @('submodule', 'update', '--init', '--recursive', 'common/java-cef')
$jcefCommit = (& git -C $jcefSource rev-parse HEAD).Trim()
if ($jcefCommit -ne 'a78e832f9f13c2c688caea3d04d8b84fcd238d94') {
    throw "Unexpected java-cef source revision: $jcefCommit"
}

Ensure-Patch $McefSource $McefPatch @((Join-Path $McefSource 'common/src/main/java/com/cinemamod/mcef/MCEF.java')) 'MCEF'
Ensure-Patch $jcefSource $JcefPatch @((Join-Path $jcefSource 'java/org/cef/browser/CefBrowser.java'), (Join-Path $jcefSource 'java/org/cef/browser/CefBrowser_N.java')) 'JCEF'

if ([string]::IsNullOrWhiteSpace($WorkDir)) {
    $WorkDir = Join-Path ([IO.Path]::GetTempPath()) ('mcwebui-mcef-proof-' + [Guid]::NewGuid().ToString('N'))
}
$work = [IO.Path]::GetFullPath($WorkDir)
New-Item -ItemType Directory -Force -Path $work | Out-Null
$generated = Join-Path $work 'generated-resources'
New-Item -ItemType Directory -Force -Path (Join-Path $generated 'META-INF') | Out-Null
Copy-Item -LiteralPath (Join-Path $McefSource 'neoforge/src/main/resources/META-INF/neoforge.mods.toml') -Destination (Join-Path $generated 'META-INF/neoforge.mods.toml') -Force

$pathForGradle = { param([string]$Path) ([IO.Path]::GetFullPath($Path)).Replace([IO.Path]::DirectorySeparatorChar, '/').Replace("'", "\\'") }
$mcefPath = & $pathForGradle $McefSource
$jcefPath = & $pathForGradle $jcefSource
$neoMain = & $pathForGradle (Join-Path $McefSource 'neoforge/src/main/java')
$commonMain = & $pathForGradle (Join-Path $McefSource 'common/src/main/java')
$commonJcef = & $pathForGradle (Join-Path $McefSource 'common/java-cef/java')
$commonResources = & $pathForGradle (Join-Path $McefSource 'common/src/main/resources')
$neoResources = & $pathForGradle (Join-Path $McefSource 'neoforge/src/main/resources')
$generatedPath = & $pathForGradle $generated

@"
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = uri('https://maven.neoforged.net/releases') }
        mavenCentral()
    }
}
rootProject.name = 'mcwebui-mcef-neoforge-proof'
"@ | Set-Content -LiteralPath (Join-Path $work 'settings.gradle') -Encoding ascii

@"
plugins {
    id 'net.neoforged.moddev' version '2.0.141'
}

group = 'com.cinemamod'
version = '2.1.6-1.21.1'

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

sourceSets {
    main {
        java.srcDirs = ["$commonMain", "$commonJcef", "$neoMain"]
        java.exclude('tests/**')
        resources.srcDirs = ["$commonResources", "$neoResources", "$generatedPath"]
    }
}

dependencies {
    implementation 'net.neoforged:neoforge:21.1.216'
}

neoForge {
    version = '21.1.216'
    mods {
        mcef { sourceSet(sourceSets.main) }
    }
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release = 21
}

tasks.named('processResources') {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching('META-INF/neoforge.mods.toml') {
        expand mod_id: 'mcef', mod_version: project.version, mod_name: 'MCEF (Minecraft Chromium Embedded Framework)',
                mod_vendor: 'CinemaMod Group', loader_version: '[4,)', neoforge_dependency: '[21.1,)',
                minecraft_version: '1.21.1'
    }
}

tasks.named('jar') {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes([
        'Specification-Title': 'MCEF',
        'Specification-Vendor': 'CinemaMod Group',
        'Specification-Version': '1',
        'Implementation-Title': 'MCEF',
        'Implementation-Version': project.version,
        'Implementation-Vendor': 'CinemaMod Group',
        'java-cef-commit': '$jcefCommit'
    ])
}
"@ | Set-Content -LiteralPath (Join-Path $work 'build.gradle') -Encoding ascii

Push-Location $work
try {
    & $Gradle --no-daemon --stacktrace jar
    if ($LASTEXITCODE -ne 0) {
        throw 'Standalone MCEF proof build failed. The exact source revisions and GAME_SYNC patches were preserved; inspect the Gradle/NeoForge toolchain failure above.'
    }
    $candidate = Get-ChildItem -LiteralPath (Join-Path $work 'build/libs') -Filter '*.jar' |
        Where-Object { $_.Name -notmatch 'sources|dev|shadow' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $candidate) { throw 'Standalone build completed without a jar' }
    $out = [IO.Path]::GetFullPath($OutputJar)
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($out)) | Out-Null
    Copy-Item -LiteralPath $candidate.FullName -Destination $out -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($out)
    $entries = $archive.Entries
    try {
        $required = @('com/cinemamod/mcef/MCEF.class', 'com/cinemamod/mcef/MCEFBrowser.class',
            'org/cef/browser/CefBrowser.class', 'org/cef/browser/CefBrowserOsr.class',
            'com/cinemamod/mcef/NeoForgeMCEFMod.class', 'META-INF/neoforge.mods.toml', 'mcef.mixins.json')
        foreach ($entry in $required) {
            if (-not ($entries.FullName -contains $entry)) { throw "Standalone jar is missing required entry: $entry" }
        }
        $entryCount = $entries.Count
    } finally { $archive.Dispose() }
    $hash = (Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash.ToUpperInvariant()
    [pscustomobject]@{ SourceRevision = $mcefCommit; JcefRevision = $jcefCommit; Artifact = $out; Sha256 = $hash; EntryCount = $entryCount } | Format-List
}
finally { Pop-Location }
