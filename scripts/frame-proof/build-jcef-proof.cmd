@echo off
setlocal EnableExtensions
if "%~1"=="" goto usage
if "%~2"=="" goto usage
if "%~3"=="" goto usage
set "SOURCE=%~1"
set "SDK=%~2"
set "BUILD=%~3"
set "VSBAT=%VS_VCVARS64%"
set "CMAKE=%CMAKE_EXE%"
set "NINJA=%NINJA_EXE%"
set "JAVA_HOME=%JAVA_HOME%"
set "PYTHON_EXECUTABLE=%PYTHON_EXECUTABLE%"
if "%VSBAT%"=="" set "VSBAT=vcvars64.bat"
if "%CMAKE%"=="" set "CMAKE=cmake.exe"
if "%NINJA%"=="" set "NINJA=ninja.exe"
if "%JAVA_HOME%"=="" if not "%JAVA_HOME_PROOF%"=="" set "JAVA_HOME=%JAVA_HOME_PROOF%"
if "%PYTHON_EXECUTABLE%"=="" set "PYTHON_EXECUTABLE=python.exe"
where "%VSBAT%" >nul 2>nul
if errorlevel 1 if not exist "%VSBAT%" exit /b 2
where "%CMAKE%" >nul 2>nul
if errorlevel 1 if not exist "%CMAKE%" exit /b 2
if not exist "%SDK%\include\cef_api_hash.h" exit /b 2
for %%I in ("%SDK%") do set "SDK_NAME=%%~nxI"
set "EXPECTED_SDK=%SOURCE%\third_party\cef\%SDK_NAME%"
if not exist "%EXPECTED_SDK%\include\cef_api_hash.h" (
  if not exist "%SOURCE%\third_party\cef" mkdir "%SOURCE%\third_party\cef"
  robocopy "%SDK%" "%EXPECTED_SDK%" /E /NFL /NDL /NJH /NJS /NP >nul
  if errorlevel 8 exit /b 1
)
call "%VSBAT%"
if errorlevel 1 exit /b 1
set "PATH=%JAVA_HOME%\bin;%PATH%"
"%CMAKE%" -S "%SOURCE%" -B "%BUILD%" -G Ninja -DCMAKE_BUILD_TYPE=Release -DPYTHON_EXECUTABLE=%PYTHON_EXECUTABLE%
if errorlevel 1 exit /b 1
"%NINJA%" -C "%BUILD%" jcef
if errorlevel 1 exit /b 1
set "JAVA_OUT=%BUILD%\java-proof"
if not exist "%JAVA_OUT%\classes" mkdir "%JAVA_OUT%\classes"
if exist "%JAVA_OUT%\sources.txt" del /q "%JAVA_OUT%\sources.txt"
dir /s /b "%SOURCE%\java\org\*.java" > "%JAVA_OUT%\sources.txt"
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "%SOURCE%\third_party\jogamp\jar\*" -d "%JAVA_OUT%\classes" @"%JAVA_OUT%\sources.txt"
if errorlevel 1 exit /b 1
"%JAVA_HOME%\bin\jar.exe" --create --file "%JAVA_OUT%\jcef-game-sync-proof.jar" -C "%JAVA_OUT%\classes" .
if errorlevel 1 exit /b 1
certutil -hashfile "%BUILD%\native\Release\jcef.dll" SHA256
certutil -hashfile "%JAVA_OUT%\jcef-game-sync-proof.jar" SHA256
exit /b 0
:usage
echo Usage: build-jcef-proof.cmd ^<java-cef-source^> ^<cef-sdk^> ^<build-dir^>
exit /b 2
