@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM Local Dream Build Script (Windows)
REM Usage: build.bat [debug|release] [basic|filter] [--rebuild-native] [--skip-gradle] [-d device]
REM   (default: release basic)
REM
REM Examples:
REM   build.bat                           Build release, basic flavor
REM   build.bat debug                     Build debug, basic flavor
REM   build.bat release filter            Build release, filter flavor
REM   build.bat debug -d 192.168.1.5:5555 Build debug, install to device
REM   build.bat release --rebuild-native  Rebuild C++ backend then build APK

REM ─── Defaults ───
set "BUILD_TYPE=release"
set "FLAVOR=basic"
set "REBUILD_NATIVE=false"
set "SKIP_GRADLE=false"
set "DEVICE="

REM ─── Parse args ───
:parse_args
if "%~1"=="" goto :done_args
if /i "%~1"=="debug" (set "BUILD_TYPE=debug" & shift & goto :parse_args)
if /i "%~1"=="release" (set "BUILD_TYPE=release" & shift & goto :parse_args)
if /i "%~1"=="basic" (set "FLAVOR=basic" & shift & goto :parse_args)
if /i "%~1"=="filter" (set "FLAVOR=filter" & shift & goto :parse_args)
if /i "%~1"=="--rebuild-native" (set "REBUILD_NATIVE=true" & shift & goto :parse_args)
if /i "%~1"=="--skip-gradle" (set "SKIP_GRADLE=true" & shift & goto :parse_args)
if /i "%~1"=="-d" (
    if "%~2"=="" (echo ERROR: -d requires a device argument & exit /b 1)
    set "DEVICE=%~2"
    shift
    shift
    goto :parse_args
)
echo Usage: build.bat [debug^|release] [basic^|filter] [--rebuild-native] [--skip-gradle] [-d device]
exit /b 1
:done_args

set "PROJECT_DIR=%~dp0"
REM Remove trailing backslash
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
set "APP_DIR=%PROJECT_DIR%\app"
set "CPP_DIR=%APP_DIR%\src\main\cpp"
set "JNI_DIR=%APP_DIR%\src\main\jniLibs\arm64-v8a"
set "QNN_ASSETS_DIR=%APP_DIR%\src\main\assets\qnnlibs"

REM Auto-detect version
set "VERSION=2.7.0"
for /f "tokens=2 delims==\"" %%v in ('findstr /c:"versionName" "%APP_DIR%\build.gradle.kts" 2^>nul') do set "VERSION=%%v"

REM Build type capitalization
if /i "%BUILD_TYPE%"=="debug" set "BUILD_TYPE_CAP=Debug"
if /i "%BUILD_TYPE%"=="release" set "BUILD_TYPE_CAP=Release"
if /i "%FLAVOR%"=="basic" set "FLAVOR_CAP=Basic"
if /i "%FLAVOR%"=="filter" set "FLAVOR_CAP=Filter"
set "GRADLE_TASK=assemble%FLAVOR_CAP%%BUILD_TYPE_CAP%"

echo === Building LocalDream v%VERSION% ^| %BUILD_TYPE% ^| %FLAVOR% ===

REM ─── Step 1: Optionally rebuild C++ backend ───
if /i "%REBUILD_NATIVE%"=="true" (
    echo === Step 1: Rebuilding native backend ===

    if not defined ANDROID_NDK_ROOT (
        echo ERROR: ANDROID_NDK_ROOT not set
        echo   set ANDROID_NDK_ROOT=D:\dev\android_sdk\ndk\28.0.x
        exit /b 1
    )

    if not exist "%CPP_DIR%\CMakeLists.txt" (
        echo ERROR: CMakeLists.txt not found at %CPP_DIR%
        exit /b 1
    )

    echo   NDK: %ANDROID_NDK_ROOT%

    pushd "%CPP_DIR%"
    cmake --preset android-release -DCMAKE_ANDROID_NDK="%ANDROID_NDK_ROOT%" -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    if %ERRORLEVEL% neq 0 (popd & goto :error)
    cmake --build --preset android-release
    if %ERRORLEVEL% neq 0 (popd & goto :error)
    popd

    if not exist "%JNI_DIR%" mkdir "%JNI_DIR%"
    copy /Y "%CPP_DIR%\build\android\bin\arm64-v8a\libstable_diffusion_core.so" "%JNI_DIR%\" >nul
    echo   Copied libstable_diffusion_core.so =^> jniLibs\arm64-v8a\

    if not exist "%QNN_ASSETS_DIR%" mkdir "%QNN_ASSETS_DIR%"
    xcopy /Y /E /Q "%CPP_DIR%\build\android\qnnlibs\*" "%QNN_ASSETS_DIR%\" >nul
    echo   Copied qnnlibs\ =^> assets\qnnlibs\

    echo === Native backend rebuilt ===
) else (
    REM Verify pre-built .so exists
    if not exist "%JNI_DIR%\libstable_diffusion_core.so" (
        echo ERROR: libstable_diffusion_core.so not found in jniLibs\
        echo   Either run with --rebuild-native ^(requires QNN SDK + NDK^)
        echo   Or extract from official APK and place in %JNI_DIR%\
        exit /b 1
    )
    echo === Using pre-built native backend ^(skip C++ compile^) ===
)

if /i "%SKIP_GRADLE%"=="true" (
    echo === Skipping Gradle build ===
    goto :eof
)

REM ─── Step 2: Gradle build ───
echo === Step 2: Gradle build ^(%GRADLE_TASK%^) ===
cd /d "%PROJECT_DIR%"
call gradlew.bat %GRADLE_TASK%
if %ERRORLEVEL% neq 0 goto :error

REM ─── Step 3: Locate APK ───
set "BUILD_DIR=%APP_DIR%\build\outputs\apk\%FLAVOR%\%BUILD_TYPE%"
set "SOURCE_APK="

REM Try naming patterns (Gradle output filename varies by AGP version)
if exist "%BUILD_DIR%\LocalDream_armv8a_%VERSION%.apk" (
    set "SOURCE_APK=%BUILD_DIR%\LocalDream_armv8a_%VERSION%.apk"
)
if not defined SOURCE_APK if exist "%BUILD_DIR%\app-%FLAVOR%-%BUILD_TYPE%.apk" (
    set "SOURCE_APK=%BUILD_DIR%\app-%FLAVOR%-%BUILD_TYPE%.apk"
)
if not defined SOURCE_APK if "%BUILD_TYPE%"=="release" (
    for %%f in ("%BUILD_DIR%\*-unsigned.apk") do set "SOURCE_APK=%%f"
)
if not defined SOURCE_APK (
    for %%f in ("%BUILD_DIR%\*.apk") do set "SOURCE_APK=%%f"
)

if not defined SOURCE_APK (
    echo ERROR: APK not found in %BUILD_DIR%
    dir "%BUILD_DIR%" 2>nul || echo   ^(directory empty or missing^)
    exit /b 1
)

REM ─── Step 4: Sign (release) or copy (debug) ───
set "OUTPUT_APK="
if "%BUILD_TYPE%"=="release" (
    if defined KEY_STORE if defined KEY_STORE_PASSWORD (
        set "BUILD_TOOLS=%ANDROID_SDK_ROOT%\build-tools\34.0.0"
        if not exist "!BUILD_TOOLS!\apksigner.jar" (
            echo WARNING: apksigner.jar not found at !BUILD_TOOLS!
            echo   Copying unsigned APK instead
            set "OUTPUT_APK=%PROJECT_DIR%\LocalDream_armv8a_%VERSION%-%FLAVOR%-unsigned.apk"
            copy /Y "!SOURCE_APK!" "!OUTPUT_APK!" >nul
        ) else (
            set "ALIGNED_APK=%BUILD_DIR%\release-aligned.apk"
            set "OUTPUT_APK=%PROJECT_DIR%\LocalDream_armv8a_%VERSION%-%FLAVOR%-signed.apk"

            echo === Aligning ===
            "!BUILD_TOOLS!\zipalign" -f 4 "!SOURCE_APK!" "!ALIGNED_APK!"
            if !ERRORLEVEL! neq 0 goto :error

            echo === Signing ===
            java -jar "!BUILD_TOOLS!\lib\apksigner.jar" sign ^
                --ks "%KEY_STORE%" ^
                --ks-pass "pass:%KEY_STORE_PASSWORD%" ^
                --ks-key-alias "%KEY_ALIAS%" ^
                --key-pass "pass:%KEY_STORE_PASSWORD%" ^
                --out "!OUTPUT_APK!" ^
                "!ALIGNED_APK!"
            if !ERRORLEVEL! neq 0 (
                del /f "!ALIGNED_APK!" 2>nul
                goto :error
            )
            del /f "!ALIGNED_APK!" 2>nul
        )
    ) else (
        echo WARNING: KEY_STORE or KEY_STORE_PASSWORD not set
        echo   Copying unsigned APK instead
        set "OUTPUT_APK=%PROJECT_DIR%\LocalDream_armv8a_%VERSION%-%FLAVOR%-unsigned.apk"
        copy /Y "!SOURCE_APK!" "!OUTPUT_APK!" >nul
    )
) else (
    set "OUTPUT_APK=%PROJECT_DIR%\LocalDream_armv8a_%VERSION%-%FLAVOR%-debug.apk"
    copy /Y "!SOURCE_APK!" "!OUTPUT_APK!" >nul
)

for %%a in ("!OUTPUT_APK!") do set "APK_SIZE=%%~za"
echo === Done: !OUTPUT_APK! (!APK_SIZE! bytes^) ===

REM ─── Step 5: Install to device (optional) ───
if defined DEVICE (
    where adb >nul 2>nul || (
        echo ERROR: adb not found in PATH
        exit /b 1
    )
    echo === Installing to %DEVICE% ===
    adb connect %DEVICE% 2>nul || true
    adb -s %DEVICE% install -r "!OUTPUT_APK!"
    if !ERRORLEVEL! neq 0 (
        echo Install failed
        exit /b 1
    )
    echo === Installed ===
)

goto :eof

:error
echo Failed with error #%ERRORLEVEL%.
exit /b %ERRORLEVEL%
