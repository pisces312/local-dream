# Local Dream Native Build (Windows)
# Builds libstable_diffusion_core.so + QNN runtime libs against QNN SDK 2.45,
# performs sanity checks, and copies artifacts to app/src/main/{jniLibs,assets}.
#
# Usage: .\build-native.ps1 [-Clean] [-NoCopy] [-Configuration <Debug|Release>]
#   -Clean          remove app/src/main/cpp/build before configuring
#   -NoCopy         don't copy artifacts into jniLibs/ + assets/qnnlibs/ at the end
#   -Configuration  Debug or Release (default Release)

param(
    [switch]$Clean,
    [switch]$NoCopy,
    [ValidateSet('Debug','Release')]
    [string]$Configuration = 'Release'
)

$ErrorActionPreference = 'Stop'

# --- Resolve paths ---
$ProjectRoot = $PSScriptRoot
$CppDir      = Join-Path $ProjectRoot 'app\src\main\cpp'
$BuildDir    = Join-Path $CppDir 'build\android'
$JniLibsDir  = Join-Path $ProjectRoot 'app\src\main\jniLibs\arm64-v8a'
$AssetsDir   = Join-Path $ProjectRoot 'app\src\main\assets\qnnlibs'

# --- Defaults (override via env vars) ---
if (-not $env:QAIRT_PATH)        { $env:QAIRT_PATH        = 'D:\dev\qairt\2.45.41.260507' }
if (-not $env:ANDROID_NDK_ROOT)  { $env:ANDROID_NDK_ROOT  = 'D:\dev\android_sdk\ndk\28.2.13676358' }
$CMakeBinDir = 'D:\dev\android_sdk\cmake\3.22.1\bin'   # cmake.exe + ninja.exe

$Preset = "android-$($Configuration.ToLower())"

function Section($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Info($msg)    { Write-Host "  $msg" -ForegroundColor DarkGray }
function Ok($msg)      { Write-Host "  ✓ $msg" -ForegroundColor Green }
function Warn($msg)    { Write-Host "  ! $msg" -ForegroundColor Yellow }
function Die($msg)     { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# ─── 1. Sanity checks ────────────────────────────────────────────────────
Section 'Sanity checks'

# QAIRT SDK
if (-not (Test-Path (Join-Path $env:QAIRT_PATH 'include\QNN'))) {
    Die "QAIRT SDK not found at $env:QAIRT_PATH (no include\QNN). Set `$env:QAIRT_PATH."
}
Ok "QAIRT_PATH       = $env:QAIRT_PATH"

# Android NDK
$NdkClang = Join-Path $env:ANDROID_NDK_ROOT 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang.cmd'
if (-not (Test-Path $NdkClang)) {
    Die "NDK windows-x86_64 toolchain missing: $NdkClang"
}
Ok "ANDROID_NDK_ROOT = $env:ANDROID_NDK_ROOT"

# CMake + Ninja
if (-not (Test-Path (Join-Path $CMakeBinDir 'cmake.exe'))) {
    Die "cmake.exe not found at $CMakeBinDir"
}
if (-not (Test-Path (Join-Path $CMakeBinDir 'ninja.exe'))) {
    Die "ninja.exe not found at $CMakeBinDir"
}
$env:Path = "$CMakeBinDir;$env:Path"
$cmakeVer = (& cmake.exe --version | Select-Object -First 1)
Ok "CMake            = $cmakeVer"

# Rust toolchain + android target
if (-not (Get-Command rustup -ErrorAction SilentlyContinue)) {
    Die "rustup not in PATH. Install Rust + add target aarch64-linux-android."
}
$installedTargets = & rustup target list --installed
if (-not ($installedTargets -match 'aarch64-linux-android')) {
    Warn "Rust target aarch64-linux-android NOT installed — installing now…"
    & rustup target add aarch64-linux-android
    if ($LASTEXITCODE -ne 0) { Die "rustup target add aarch64-linux-android failed" }
}
# Verify the stdlib actually exists on disk (rustup state can lie — see docs/2026-06-27-qnn-sdk-2.45-build-success.md §3.5)
$rustHome = & rustc --print sysroot
$rustStd  = Join-Path $rustHome 'lib\rustlib\aarch64-linux-android\lib'
if (-not (Test-Path $rustStd)) {
    Warn "rustup says target is installed, but $rustStd is missing."
    Warn "Repair: edit $rustHome\lib\rustlib\multirust-config.toml & components, drop the aarch64-linux-android entry, then 'rustup target add aarch64-linux-android'."
    Die  "Rust android stdlib missing on disk."
}
Ok "Rust target      = aarch64-linux-android (stdlib present)"

# ─── 2. Submodule sanity ─────────────────────────────────────────────────
Section 'Submodule sanity'
Push-Location $ProjectRoot
try {
    # `git submodule status` prefixes drifted submodules with `+`
    $subStatus = git submodule status --recursive 2>&1
    $drifted = $subStatus | Where-Object { $_ -match '^\+' }
    if ($drifted) {
        Warn "Submodules drifted from pinned commit (may break build):"
        foreach ($d in $drifted) { Warn "    $d" }
        Warn "Fix with:  git submodule update --init --recursive --force"
        Warn "Continuing anyway — the build may still succeed if drift is benign."
    } else {
        Ok "All submodules match pinned commits."
    }
} finally {
    Pop-Location
}

# ─── 3. Configure ────────────────────────────────────────────────────────
Section "Configure (preset: $Preset)"
Push-Location $CppDir
try {
    if ($Clean -and (Test-Path $BuildDir)) {
        Info "Cleaning $BuildDir"
        Remove-Item $BuildDir -Recurse -Force
    }
    # Always clear ANDROID_TOOLCHAIN_ROOT — if a prior bad host-tag value got cached,
    # editing CMakeLists.txt alone won't refresh it.
    & cmake.exe --preset $Preset -UANDROID_TOOLCHAIN_ROOT
    if ($LASTEXITCODE -ne 0) { Die "cmake configure failed" }
} finally {
    Pop-Location
}
Ok "Configured."

# ─── 4. Build ─────────────────────────────────────────────────────────────
Section "Build (preset: $Preset)"
Push-Location $CppDir
try {
    & cmake.exe --build --preset $Preset
    if ($LASTEXITCODE -ne 0) { Die "cmake build failed" }
} finally {
    Pop-Location
}

$SoOut = Join-Path $BuildDir 'bin\arm64-v8a\libstable_diffusion_core.so'
if (-not (Test-Path $SoOut)) { Die "Build reported success but $SoOut missing." }
$soSize = [math]::Round((Get-Item $SoOut).Length / 1MB, 2)
Ok "Built libstable_diffusion_core.so (${soSize} MB)"

# ─── 5. Copy artifacts ───────────────────────────────────────────────────
if ($NoCopy) {
    Section 'Skipping copy (-NoCopy)'
    Write-Host "  Artifacts ready at:" -ForegroundColor DarkGray
    Write-Host "    $SoOut"
    Write-Host "    $(Join-Path $BuildDir 'qnnlibs')"
    return
}

Section 'Copy artifacts'

# libstable_diffusion_core.so → app/src/main/jniLibs/arm64-v8a/
New-Item -ItemType Directory -Force -Path $JniLibsDir | Out-Null
Copy-Item $SoOut $JniLibsDir -Force
Ok "→ $JniLibsDir\libstable_diffusion_core.so"

# QNN runtime libs → app/src/main/assets/qnnlibs/
$QnnLibSrc = Join-Path $BuildDir 'qnnlibs'
if (-not (Test-Path $QnnLibSrc)) { Die "Build did not produce $QnnLibSrc" }
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null
Get-ChildItem $QnnLibSrc -Filter '*.so' | ForEach-Object {
    Copy-Item $_.FullName $AssetsDir -Force
}
$qnnCount = (Get-ChildItem $AssetsDir -Filter '*.so').Count
Ok "→ $AssetsDir ($qnnCount .so files)"

Section 'Done'
Write-Host "Next: build the Android APK." -ForegroundColor Green
Write-Host "  Debug:   .\gradlew assembleBasicDebug" -ForegroundColor DarkGray
Write-Host "  Release: .\build-sm8850.ps1 release basic     # SM8850-only slim build" -ForegroundColor DarkGray
