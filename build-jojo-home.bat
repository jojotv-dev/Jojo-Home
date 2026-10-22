@echo off
setlocal EnableExtensions

rem Compile the compact release APK for Android TV and archive it on drive Z.
set "PROJECT_DIR=%~dp0"
if not defined PROJECT_DIR set "PROJECT_DIR=%CD%\\"
set "APK_SOURCE=%PROJECT_DIR%app\build\outputs\apk\release\app-armeabi-v7a-release.apk"
set "GRADLE_TASK=:app:assembleRelease"
set "APK_ROOT=%PROJECT_DIR%Jojo Home.apk"
set "DEST_DIR=Z:\"
set "VERSION_FILE=%PROJECT_DIR%jojo-home-version.properties"
set "VERSION_SCRIPT=%PROJECT_DIR%tools\increment-jojo-home-version.ps1"

:build
for /f "tokens=1,* delims==" %%A in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%VERSION_SCRIPT%" -Path "%VERSION_FILE%"') do if "%%A"=="VERSION" set "JOJO_VERSION=%%B"
set "APK_DEST=%DEST_DIR%Jojo Home_%JOJO_VERSION%.apk"
echo.
echo === Compilation de Jojo Home ===
call "%PROJECT_DIR%gradlew.bat" %GRADLE_TASK% --no-daemon --console=plain -PjojoHomeAbi=armeabi-v7a -x :app:lintVitalAnalyzeRelease --profile
if errorlevel 1 (
    echo.
    powershell -NoProfile -Command "$esc=[char]27; Write-Host ('{0}[91mBUILD FAILED{0}[0m' -f $esc)"
    echo ECHEC : la compilation Gradle a echoue.
    goto menu
)

if not exist "%APK_SOURCE%" (
    echo.
    powershell -NoProfile -Command "$esc=[char]27; Write-Host ('{0}[91mBUILD FAILED{0}[0m' -f $esc)"
    echo ECHEC : APK introuvable : %APK_SOURCE%
    goto menu
)

copy /Y "%APK_SOURCE%" "%APK_ROOT%" >nul
if errorlevel 1 (
    echo.
    powershell -NoProfile -Command "$esc=[char]27; Write-Host ('{0}[91mBUILD FAILED{0}[0m' -f $esc)"
    echo ECHEC : copie impossible vers %APK_ROOT%
    goto menu
)

if exist "%DEST_DIR%" (
    copy /Y "%APK_SOURCE%" "%APK_DEST%" >nul
    if errorlevel 1 (
        echo.
        powershell -NoProfile -Command "$esc=[char]27; Write-Host ('{0}[93mAVERTISSEMENT{0}[0m' -f $esc)"
        echo AVERTISSEMENT : archive inaccessible, APK conserve localement : %APK_ROOT%
    )
) else (
    echo.
    powershell -NoProfile -Command "$esc=[char]27; Write-Host ('{0}[93mAVERTISSEMENT{0}[0m' -f $esc)"
    echo AVERTISSEMENT : lecteur d'archive indisponible : %DEST_DIR%
    echo L'APK compile reste disponible ici : %APK_ROOT%
)

echo.
powershell -NoProfile -Command "$esc=[char]27; Write-Host ('{0}[92mBUILD SUCCESSFUL{0}[0m' -f $esc)"
echo SUCCES :
echo   Projet : %APK_ROOT%
if exist "%DEST_DIR%" echo   Archive : %APK_DEST%

:menu
echo.
choice /C RQ /N /M "[R] Relancer une compilation   [Q] Quitter"
if errorlevel 2 exit /b 0
goto build
