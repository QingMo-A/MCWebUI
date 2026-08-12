[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$CefRoot,
    [string]$BuildRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-build'),
    [string]$VisualStudioRoot = 'C:\Program Files\Microsoft Visual Studio\18\Community'
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path (Join-Path $PSScriptRoot '..\..\native\direct-cef-proof')).Path
$cef = (Resolve-Path -LiteralPath $CefRoot).Path
$vcvars = Join-Path $VisualStudioRoot 'VC\Auxiliary\Build\vcvars64.bat'
$cmake = Join-Path $VisualStudioRoot 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
$ninja = Join-Path $VisualStudioRoot 'Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe'
foreach ($required in @($vcvars, $cmake, $ninja)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required build tool is missing: $required" }
}

New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
$sourceArg = $source.Replace('\', '/')
$buildArg = ([IO.Path]::GetFullPath($BuildRoot)).Replace('\', '/')
$cefArg = $cef.Replace('\', '/')
$configure = "`"$vcvars`" >nul && `"$cmake`" -S `"$sourceArg`" -B `"$buildArg`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCEF_ROOT=`"$cefArg`""
cmd.exe /d /s /c $configure
if ($LASTEXITCODE -ne 0) { throw "Direct CEF configure failed with exit code $LASTEXITCODE" }

$build = "`"$vcvars`" >nul && `"$cmake`" --build `"$buildArg`" --config Release"
cmd.exe /d /s /c $build
if ($LASTEXITCODE -ne 0) { throw "Direct CEF build failed with exit code $LASTEXITCODE" }

$bin = Join-Path $BuildRoot 'bin'
New-Item -ItemType Directory -Force -Path $bin | Out-Null
Copy-Item -LiteralPath (Join-Path $cef 'Release\libcef.dll') -Destination $bin -Force
Get-ChildItem -LiteralPath (Join-Path $cef 'Release') -File | Where-Object Extension -eq '.dll' |
    Copy-Item -Destination $bin -Force
foreach ($runtimeData in @('snapshot_blob.bin', 'v8_context_snapshot.bin', 'vk_swiftshader_icd.json')) {
    $runtimePath = Join-Path $cef "Release\$runtimeData"
    if (Test-Path -LiteralPath $runtimePath) {
        Copy-Item -LiteralPath $runtimePath -Destination $bin -Force
    }
}
Get-ChildItem -LiteralPath (Join-Path $cef 'Resources') -Force |
    Copy-Item -Destination $bin -Recurse -Force

$executable = Join-Path $bin 'mcwebui_direct_cef_proof.exe'
if (-not (Test-Path -LiteralPath $executable)) { throw "Proof executable was not produced: $executable" }
Write-Output $executable
