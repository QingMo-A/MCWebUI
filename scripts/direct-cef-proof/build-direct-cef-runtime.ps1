[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CefRoot,
    [string]$BuildRoot = (Join-Path $env:TEMP 'mcwebui-direct-cef-runtime-build'),
    [string]$VisualStudioRoot = 'C:\Program Files\Microsoft Visual Studio\18\Community'
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path (Join-Path $PSScriptRoot '..\..\native\direct-cef-runtime')).Path
$vcvars = Join-Path $VisualStudioRoot 'VC\Auxiliary\Build\vcvars64.bat'
$cmake = Join-Path $VisualStudioRoot 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
foreach ($required in @($vcvars, $cmake, (Join-Path $CefRoot 'include\cef_api_hash.h'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required Direct CEF build input missing: $required" }
}
New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
$configure = "`"$vcvars`" >nul && `"$cmake`" -S `"$($source.Replace('\','/'))`" -B `"$($BuildRoot.Replace('\','/'))`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCEF_ROOT=`"$($CefRoot.Replace('\','/'))`""
cmd.exe /d /s /c $configure
if ($LASTEXITCODE -ne 0) { throw "Direct CEF configure failed: $LASTEXITCODE" }
$build = "`"$vcvars`" >nul && `"$cmake`" --build `"$($BuildRoot.Replace('\','/'))`" --config Release"
cmd.exe /d /s /c $build
if ($LASTEXITCODE -ne 0) { throw "Direct CEF build failed: $LASTEXITCODE" }
$bin = Join-Path $BuildRoot 'bin'
foreach ($file in @('libcef.dll','chrome_elf.dll','snapshot_blob.bin','v8_context_snapshot.bin','vk_swiftshader_icd.json')) {
    $sourceFile = Join-Path (Join-Path $CefRoot 'Release') $file
    if (Test-Path -LiteralPath $sourceFile) { Copy-Item -LiteralPath $sourceFile -Destination $bin -Force }
}
Get-ChildItem -LiteralPath (Join-Path $CefRoot 'Release') -File -Filter '*.dll' | Copy-Item -Destination $bin -Force
Get-ChildItem -LiteralPath (Join-Path $CefRoot 'Resources') -Force | Copy-Item -Destination $bin -Recurse -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\..\native\direct-cef-runtime\win\mcwebui_cef_helper.exe.manifest') -Destination (Join-Path $bin 'mcwebui-cef-helper.exe.manifest') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\..\native\direct-cef-runtime\win\compatibility.manifest') -Destination (Join-Path $bin 'compatibility.manifest') -Force
