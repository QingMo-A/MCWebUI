[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression

$verifier = Join-Path $PSScriptRoot 'verify-frozen-runtime-r1.ps1'
$testRoot = Join-Path $env:TEMP ('mcwebui-r1-verifier-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null

function Hash-Bytes([byte[]]$Bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function New-Fixture([string]$Name, [string]$Mutation = '') {
    $directory = Join-Path $testRoot $Name
    New-Item -ItemType Directory -Path $directory | Out-Null
    $zipPath = Join-Path $directory 'fixture.zip'
    $payload = [ordered]@{
        'mcwebui-direct-cef.dll' = [Text.Encoding]::UTF8.GetBytes('native')
        'mcwebui-cef-helper.exe' = [Text.Encoding]::UTF8.GetBytes('helper')
        'libcef.dll' = [Text.Encoding]::UTF8.GetBytes('cef')
        'chrome_elf.dll' = [Text.Encoding]::UTF8.GetBytes('elf')
        'LICENSE.txt' = [Text.Encoding]::UTF8.GetBytes('license')
        'CREDITS.html' = [Text.Encoding]::UTF8.GetBytes('credits')
        'payload.dat' = [Text.Encoding]::UTF8.GetBytes('payload')
    }
    if ($Mutation -eq 'missing-entrypoint') { $payload.Remove('mcwebui-cef-helper.exe') }
    if ($Mutation -eq 'missing-notice') { $payload.Remove('CREDITS.html') }

    $records = @()
    foreach ($entry in $payload.GetEnumerator()) {
        $hash = Hash-Bytes $entry.Value
        if ($Mutation -eq 'payload-hash' -and $entry.Key -eq 'payload.dat') { $hash = '0' * 64 }
        $records += [ordered]@{ path = $entry.Key; size = $entry.Value.Length; sha256 = $hash }
    }
    $manifest = [ordered]@{
        schemaVersion = 1
        runtimeId = 'fixture-runtime'
        mcwebuiRuntimeAbi = 1
        cefVersion = '1.0'
        chromiumVersion = '2.0'
        platform = 'windows'
        arch = 'x86_64'
        entrypoints = [ordered]@{
            native = 'mcwebui-direct-cef.dll'; helper = 'mcwebui-cef-helper.exe'
            cef = 'libcef.dll'; chromeElf = 'chrome_elf.dll'
        }
        files = $records
    }
    $manifestBytes = [Text.Encoding]::UTF8.GetBytes(($manifest | ConvertTo-Json -Depth 6))

    $file = [IO.File]::Open($zipPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $zip = [IO.Compression.ZipArchive]::new($file, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            foreach ($entry in $payload.GetEnumerator()) {
                $target = $zip.CreateEntry($entry.Key)
                $stream = $target.Open()
                try { $stream.Write($entry.Value, 0, $entry.Value.Length) } finally { $stream.Dispose() }
            }
            $runtime = $zip.CreateEntry('runtime.json')
            $stream = $runtime.Open()
            try { $stream.Write($manifestBytes, 0, $manifestBytes.Length) } finally { $stream.Dispose() }
            if ($Mutation -eq 'duplicate') {
                $duplicate = $zip.CreateEntry('PAYLOAD.DAT')
                $stream = $duplicate.Open()
                try { $stream.WriteByte(1) } finally { $stream.Dispose() }
            }
            if ($Mutation -eq 'unsafe') {
                $unsafe = $zip.CreateEntry('../evil.dat')
                $stream = $unsafe.Open()
                try { $stream.WriteByte(1) } finally { $stream.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $file.Dispose() }

    $zipItem = Get-Item $zipPath
    $entrypoints = [ordered]@{}
    foreach ($pair in @(
        @('native','mcwebui-direct-cef.dll'), @('helper','mcwebui-cef-helper.exe'),
        @('cef','libcef.dll'), @('chromeElf','chrome_elf.dll'))) {
        [byte[]]$bytes = [byte[]]::new(0)
        if ($payload.Contains($pair[1])) { $bytes = [byte[]]$payload[$pair[1]] }
        $entrypoints[$pair[0]] = [ordered]@{ path=$pair[1]; size=$bytes.Length; sha256=Hash-Bytes $bytes }
    }
    [int64]$unpackedSize = 0
    foreach ($record in $records) { $unpackedSize += [int64]$record.size }
    $lock = [ordered]@{
        lockVersion = 1; status = 'FROZEN'; sourceGitSha = '0' * 40
        runtime = [ordered]@{ runtimeId='fixture-runtime'; runtimeAbi=1; cefVersion='1.0'; chromiumVersion='2.0'; platform='windows'; arch='x86_64' }
        artifact = [ordered]@{
            revision='test'; filename=$zipItem.Name; size=$zipItem.Length
            unpackedSize=$unpackedSize
            fileCount=$records.Count; sha256=(Get-FileHash $zipPath -Algorithm SHA256).Hash
        }
        entrypoints = $entrypoints
        requiredNotices = @('LICENSE.txt','CREDITS.html')
    }
    if ($Mutation -eq 'wrong-filename') { $lock.artifact.filename = 'wrong.zip' }
    if ($Mutation -eq 'wrong-size') { $lock.artifact.size = [int64]$zipItem.Length + 1 }
    if ($Mutation -eq 'wrong-package-hash') { $lock.artifact.sha256 = '0' * 64 }
    if ($Mutation -eq 'wrong-runtime') { $lock.runtime.runtimeId = 'different-runtime' }
    if ($Mutation -eq 'wrong-entrypoint-hash') { $lock.entrypoints.native.sha256 = '0' * 64 }
    $lockPath = Join-Path $directory 'lock.json'
    [IO.File]::WriteAllText($lockPath, ($lock | ConvertTo-Json -Depth 7), [Text.UTF8Encoding]::new($false))
    return [pscustomobject]@{ Zip=$zipPath; Lock=$lockPath }
}

function Invoke-Case([string]$Name, [string]$Mutation, [int]$ExpectedExit, [string]$ExpectedText) {
    $fixture = New-Fixture $Name $Mutation
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifier `
            -Path $fixture.Zip -LockPath $fixture.Lock 2>&1) | Out-String
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exit -ne $ExpectedExit -or $output -notmatch [regex]::Escape($ExpectedText)) {
        throw "Verifier test '$Name' failed: exit=$exit expected=$ExpectedExit text='$ExpectedText' output=$output"
    }
    Write-Host "PASS $Name -> $ExpectedText"
}

try {
    Invoke-Case 'valid' '' 0 'RUNTIME R1 RELEASE INPUT VERIFIED'
    Invoke-Case 'wrong-filename' 'wrong-filename' 1 'FILENAME_MISMATCH'
    Invoke-Case 'wrong-size' 'wrong-size' 1 'SIZE_MISMATCH'
    Invoke-Case 'wrong-package-hash' 'wrong-package-hash' 1 'PACKAGE_HASH_MISMATCH'
    Invoke-Case 'wrong-runtime' 'wrong-runtime' 1 'MANIFEST_IDENTITY_MISMATCH'
    Invoke-Case 'missing-entrypoint' 'missing-entrypoint' 1 'ENTRYPOINT_MISSING'
    Invoke-Case 'wrong-entrypoint-hash' 'wrong-entrypoint-hash' 1 'ENTRYPOINT_HASH_MISMATCH'
    Invoke-Case 'missing-notice' 'missing-notice' 1 'NOTICE_MISSING'
    Invoke-Case 'payload-hash' 'payload-hash' 1 'PAYLOAD_HASH_MISMATCH'
    Invoke-Case 'duplicate' 'duplicate' 1 'DUPLICATE_ENTRY'
    Invoke-Case 'unsafe' 'unsafe' 1 'UNSAFE_ENTRY_PATH'
    Write-Host 'FROZEN RUNTIME VERIFIER SYNTHETIC TESTS: PASS (11/11)'
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
