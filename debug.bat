@echo off
setlocal EnableExtensions

set "PROJECT_DIR=%~dp0"

if not exist "%PROJECT_DIR%gradlew.bat" (
    echo.
    echo ERREUR : gradlew.bat est introuvable dans :
    echo "%PROJECT_DIR%"
    echo.
    echo Verifie que ce fichier BAT se trouve a la racine du projet Android.
    pause
    exit /b 1
)

:build
set "LAST_RESULT=0"

echo.
echo === Compilation debug de Jojo Home ===
echo Projet :
echo "%PROJECT_DIR%"

pushd "%PROJECT_DIR%"

call gradlew.bat :app:assembleDebug --no-daemon --console=plain -PjojoHomeAbi=armeabi-v7a --profile

if errorlevel 1 (
    echo.
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Write-Host 'BUILD FAILED' -ForegroundColor Red"
    echo ECHEC : la compilation Gradle a echoue.
    popd
    set "LAST_RESULT=1"
    goto :ask
)

popd

echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Write-Host 'BUILD SUCCESSFUL' -ForegroundColor Green"
echo Compilation debug terminee.

echo.
echo APK genere dans :
echo "%PROJECT_DIR%app\build\outputs\apk\debug\"

echo.
dir /s /b "%PROJECT_DIR%app\build\outputs\apk\*.apk"

:ask
echo.
choice /C RQ /N /M "R = relancer la compilation, Q = quitter : "
if errorlevel 2 goto :quit
if errorlevel 1 goto :build

:quit
endlocal
exit /b %LAST_RESULT%
