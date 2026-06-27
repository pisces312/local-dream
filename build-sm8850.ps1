# Local Dream Build Script — SM8850 (Snapdragon 8 Elite 2nd gen) only
# Builds normally, then strips non-V81 QNN libs from the APK before signing.
# Usage: .\build-sm8850.ps1 [debug|release] [basic|filter] [-d <device>]
#   (default: release basic)

param(
    [Parameter(Position=0)]
    [ValidateSet('debug','release')]
    [string]$BuildType = 'release',

    [Parameter(Position=1)]
    [ValidateSet('basic','filter')]
    [string]$Flavor = 'basic',

    [string]$Device = ''
)

$ErrorActionPreference = 'Stop'
$ProjectDir = $PSScriptRoot
$AppDir = Join-Path $ProjectDir 'app'

# Auto-detect version from build.gradle.kts
$Version = '2.7.0'
$GradleFile = Join-Path $AppDir 'build.gradle.kts'
if (Test-Path $GradleFile) {
    $match = Select-String -Path $GradleFile -Pattern 'versionName\s*=\s*"([^"]+)"'
    if ($match) { $Version = $match.Matches[0].Groups[1].Value }
}

$BuildTypeCap = $BuildType.Substring(0,1).ToUpper() + $BuildType.Substring(1)
$FlavorCap = $Flavor.Substring(0,1).ToUpper() + $Flavor.Substring(1)
$GradleTask = "assemble$FlavorCap$BuildTypeCap"

Write-Host "=== Building LocalDream v$Version | $BuildType | $Flavor | SM8850 only ===" -ForegroundColor Cyan

# ─── Step 1: Gradle build ───
Write-Host "=== Step 1: Gradle build ($GradleTask) ===" -ForegroundColor Yellow
Push-Location $ProjectDir
try {
    & .\gradlew.bat $GradleTask
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}

# ─── Step 2: Locate APK ───
$BuildDir = Join-Path $AppDir "build\outputs\apk\$Flavor\$BuildType"
$SourceApk = $null

$candidates = @(
    (Join-Path $BuildDir "LocalDream_armv8a_$Version.apk"),
    (Join-Path $BuildDir "LocalDream_armv8a_${Version}-unsigned.apk")
)
foreach ($c in $candidates) {
    if (Test-Path $c) { $SourceApk = $c; break }
}
if (-not $SourceApk) {
    $apkFiles = Get-ChildItem $BuildDir -Filter '*.apk' -ErrorAction SilentlyContinue
    if ($apkFiles) { $SourceApk = $apkFiles[0].FullName }
}
if (-not $SourceApk) {
    Write-Host "ERROR: APK not found in $BuildDir" -ForegroundColor Red
    exit 1
}
Write-Host "  Source APK: $SourceApk"

# ─── Step 3: Strip non-V81 QNN libs from APK ───
Write-Host "=== Step 3: Stripping non-V81 QNN libs ===" -ForegroundColor Yellow

$SizeBefore = [math]::Round((Get-Item $SourceApk).Length / 1MB, 1)
Write-Host "  APK before: ${SizeBefore}MB"

$keep = @{
    'assets/qnnlibs/libQnnHtp.so'          = $true
    'assets/qnnlibs/libQnnSystem.so'        = $true
    'assets/qnnlibs/libQnnHtpV81.so'        = $true
    'assets/qnnlibs/libQnnHtpV81Skel.so'    = $true
    'assets/qnnlibs/libQnnHtpV81Stub.so'    = $true
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$tmpApk = "$SourceApk.tmp"
$removed = 0

$zin = [System.IO.Compression.ZipFile]::OpenRead($SourceApk)
$zout = [System.IO.Compression.ZipFile]::Open($tmpApk, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in $zin.Entries) {
        if ($entry.FullName.StartsWith('assets/qnnlibs/') -and -not $keep.ContainsKey($entry.FullName)) {
            $removed++
            continue
        }
        $newEntry = $zout.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        $newEntry.LastWriteTime = $entry.LastWriteTime
        $stream = $newEntry.Open()
        $entryStream = $entry.Open()
        $entryStream.CopyTo($stream)
        $entryStream.Close()
        $stream.Close()
    }
} finally {
    $zout.Dispose()
    $zin.Dispose()
}

Move-Item -Path $tmpApk -Destination $SourceApk -Force
Write-Host "  Removed $removed QNN lib entries"

$SizeAfter = [math]::Round((Get-Item $SourceApk).Length / 1MB, 1)
Write-Host "  APK after: ${SizeAfter}MB"

# ─── Step 4: Sign (release) or copy (debug) ───
$OutputApk = $null

if ($BuildType -eq 'release') {
    $keystore = $env:KEY_STORE
    $keystorePass = $env:KEY_STORE_PASSWORD
    $keyAlias = if ($env:KEY_ALIAS) { $env:KEY_ALIAS } else { 'pisces312' }

    if (-not $keystore -or -not $keystorePass) {
        Write-Host "WARNING: Signing config not set (KEY_STORE / KEY_STORE_PASSWORD env vars)" -ForegroundColor Yellow
        Write-Host "  Copying unsigned APK instead"
        $OutputApk = Join-Path $ProjectDir "LocalDream_armv8a_${Version}-${Flavor}-sm8850-unsigned.apk"
        Copy-Item $SourceApk $OutputApk -Force
    } else {
        $buildTools = Join-Path $env:ANDROID_SDK_ROOT 'build-tools\34.0.0'
        if (-not (Test-Path (Join-Path $buildTools 'apksigner.jar'))) {
            $buildTools = Join-Path 'D:\dev\android_sdk\build-tools\34.0.0' ''
        }
        $AlignedApk = Join-Path $BuildDir 'release-aligned.apk'
        $OutputApk = Join-Path $ProjectDir "LocalDream_armv8a_${Version}-${Flavor}-sm8850-signed.apk"

        Write-Host "=== Aligning ==="
        & "$buildTools\zipalign" -f 4 $SourceApk $AlignedApk
        if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

        Write-Host "=== Signing ==="
        & java -jar "$buildTools\lib\apksigner.jar" sign `
            --ks $keystore `
            --ks-pass "pass:$keystorePass" `
            --ks-key-alias $keyAlias `
            --key-pass "pass:$keystorePass" `
            --out $OutputApk `
            $AlignedApk
        if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

        Remove-Item $AlignedApk -Force -ErrorAction SilentlyContinue
    }
} else {
    $OutputApk = Join-Path $ProjectDir "LocalDream_armv8a_${Version}-${Flavor}-sm8850-debug.apk"
    Copy-Item $SourceApk $OutputApk -Force
}

$FinalSize = [math]::Round((Get-Item $OutputApk).Length / 1MB, 1)
Write-Host "=== Done: $OutputApk (${FinalSize}MB) ===" -ForegroundColor Green

# ─── Step 5: Install to device (optional) ───
if ($Device) {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adb) {
        Write-Host "ERROR: adb not found in PATH" -ForegroundColor Red
        exit 1
    }
    Write-Host "=== Installing to $Device ===" -ForegroundColor Yellow
    & adb connect $Device 2>$null
    & adb -s $Device install -r $OutputApk
    if ($LASTEXITCODE -ne 0) { Write-Host "Install failed" -ForegroundColor Red; exit 1 }
    Write-Host "=== Installed ===" -ForegroundColor Green
}
