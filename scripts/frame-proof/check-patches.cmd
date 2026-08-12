@echo off
setlocal
if "%~1"=="" exit /b 2
if "%~2"=="" exit /b 2
git -C "%~1" apply --check "%~2"
exit /b %errorlevel%
