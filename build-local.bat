@echo off
setlocal
if exist "E:\codex\nbt-gradle\caches" (
    set "GRADLE_USER_HOME=E:\codex\nbt-gradle"
) else (
    set "GRADLE_USER_HOME=%~dp0.nbt-dev\gradle-user-home"
)
pushd "%~dp0"
if errorlevel 1 exit /b 1
if "%~1"=="" (
    call "%~dp0gradlew.bat" --no-daemon build
) else (
    call "%~dp0gradlew.bat" --no-daemon %*
)
set "NBT_BUILD_EXIT=%ERRORLEVEL%"
popd
exit /b %NBT_BUILD_EXIT%
