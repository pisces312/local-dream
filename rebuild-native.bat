@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  rebuild-native.bat — 编译 libstable_diffusion_core.so
REM  使用 CMake + Ninja，禁用 ccache，适配 Windows 环境
REM ============================================================

REM ─── Paths ───
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
set "APP_DIR=%PROJECT_DIR%\app"
set "CPP_DIR=%APP_DIR%\src\main\cpp"
set "JNI_DIR=%APP_DIR%\src\main\jniLibs\arm64-v8a"
set "QNN_ASSETS_DIR=%APP_DIR%\src\main\assets\qnnlibs"

set "CMAKE=D:/dev/android_sdk/cmake/3.22.1/bin/cmake.exe"
set "NINJA=D:/dev/android_sdk/cmake/3.22.1/bin/ninja.exe"
set "NDK=D:/dev/android_sdk/ndk/28.2.13676358"

REM ─── Env ───
if not defined QAIRT_PATH set "QAIRT_PATH=D:\dev\qairt\2.39.0.250926"
set "ANDROID_NDK_ROOT=%NDK%"

echo === LocalDream Native Rebuild ===
echo   QAIRT: %QAIRT_PATH%
echo   NDK:   %ANDROID_NDK_ROOT%
echo   CMake: %CMAKE%

REM ─── Verify prerequisites ───
if not exist "%QAIRT_PATH%" (
    echo ERROR: QAIRT_PATH not found: %QAIRT_PATH%
    exit /b 1
)
if not exist "%NDK%" (
    echo ERROR: NDK not found: %NDK%
    exit /b 1
)
if not exist "%CMAKE%" (
    echo ERROR: cmake not found: %CMAKE%
    exit /b 1
)
if not exist "%CPP_DIR%\CMakeLists.txt" (
    echo ERROR: CMakeLists.txt not found at %CPP_DIR%
    exit /b 1
)

REM ─── Step 1: CMake Configure ───
echo.
echo === Step 1: CMake Configure ===
pushd "%CPP_DIR%"
"%CMAKE%" --preset android-release ^
    -DCMAKE_C_COMPILER_LAUNCHER="" ^
    -DCMAKE_CXX_COMPILER_LAUNCHER="" ^
    -DCMAKE_MAKE_PROGRAM="%NINJA%"
if %ERRORLEVEL% neq 0 (
    popd
    echo ERROR: CMake configure failed
    exit /b 1
)

REM ─── Step 2: Build ───
echo.
echo === Step 2: Ninja Build ===
"%CMAKE%" --build --preset android-release
if %ERRORLEVEL% neq 0 (
    popd
    echo ERROR: Build failed
    exit /b 1
)
popd

REM ─── Step 3: Copy artifacts ───
echo.
echo === Step 3: Copy artifacts ===

if not exist "%JNI_DIR%" mkdir "%JNI_DIR%"
copy /Y "%CPP_DIR%\build\android\bin\arm64-v8a\libstable_diffusion_core.so" "%JNI_DIR%\" >nul
if %ERRORLEVEL% neq 0 (
    echo ERROR: Failed to copy .so
    exit /b 1
)
echo   [+] libstable_diffusion_core.so -^> jniLibs\arm64-v8a\

if not exist "%QNN_ASSETS_DIR%" mkdir "%QNN_ASSETS_DIR%"
xcopy /Y /E /Q "%CPP_DIR%\build\android\qnnlibs\*" "%QNN_ASSETS_DIR%\" >nul
if %ERRORLEVEL% neq 0 (
    echo ERROR: Failed to copy qnnlibs
    exit /b 1
)
echo   [+] qnnlibs\ -^> assets\qnnlibs\

echo.
echo === Done ===
exit /b 0
