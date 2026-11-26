@echo off
rem SPDX-License-Identifier: GPL-3.0-only
rem
rem Runs the unit tests, builds both release APKs, and signs them with the project release key.
rem
rem   signed\motion-anchor.apk        standard build: installs without Play Protect warnings
rem   signed\motion-anchor-full.apk   adds the accessibility service that draws over system bars
rem
rem On the first run the script creates release.jks and keystore.properties in this folder.
rem Back up both files: Android only installs updates signed with the same key.
rem To use an existing key instead, write keystore.properties yourself before the first run:
rem   storeFile=PATH\TO\KEYSTORE   storePassword=...   keyAlias=...   keyPassword=...
rem
rem Needs a JDK 17 or newer (JAVA_HOME, or the JDK bundled with Android Studio) and the
rem Android SDK (ANDROID_HOME, ANDROID_SDK_ROOT, or %LOCALAPPDATA%\Android\Sdk).

setlocal
pushd "%~dp0"

if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\keytool.exe" (
    echo JDK not found. Set JAVA_HOME to a JDK 17 or newer.
    goto fail
)
rem JDK 24+ warns when the Gradle launcher loads its native library; this flag allows it quietly.
set "JAVA_OPTS=%JAVA_OPTS% --enable-native-access=ALL-UNNAMED"

if not defined ANDROID_HOME set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%\platforms" (
    echo Android SDK not found. Set ANDROID_HOME.
    goto fail
)

if exist keystore.properties goto build
if exist release.jks (
    echo release.jks exists but keystore.properties is missing. Restore it from your backup.
    goto fail
)
echo Creating the release signing key release.jks ...
set "KS_PASS="
for /f %%p in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "KS_PASS=%%p"
if not defined KS_PASS (
    echo Could not generate a keystore password.
    goto fail
)
"%JAVA_HOME%\bin\keytool.exe" -genkeypair -keystore release.jks -storetype PKCS12 -storepass %KS_PASS% -alias motionanchor -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Motion Anchor"
if errorlevel 1 goto fail
> keystore.properties (
    echo storeFile=release.jks
    echo storePassword=%KS_PASS%
    echo keyAlias=motionanchor
    echo keyPassword=%KS_PASS%
)
echo Created release.jks and keystore.properties. Back up both files.
echo.

:build
call "%~dp0gradlew.bat" :app:testStandardDebugUnitTest :app:assembleStandardRelease :app:assembleFullRelease
if errorlevel 1 goto fail

if not exist signed mkdir signed
copy /y "app\build\outputs\apk\standard\release\app-standard-release.apk" "signed\motion-anchor.apk" >nul || goto fail
copy /y "app\build\outputs\apk\full\release\app-full-release.apk" "signed\motion-anchor-full.apk" >nul || goto fail
echo.
for %%f in ("signed\motion-anchor.apk" "signed\motion-anchor-full.apk") do echo %%~ff  %%~zf bytes
popd
pause
exit /b 0

:fail
echo.
echo Build failed.
popd
pause
exit /b 1
