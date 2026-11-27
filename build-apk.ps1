# Script tự động build APK HoatHinh3D Universal Native (Kotlin + XML + Media3 ExoPlayer)
$ErrorActionPreference = "Stop"

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "  BẮT ĐẦU BUILD HOATHINH3D NATIVE ANDROID APK (KOTLIN)  " -ForegroundColor Cyan
Write-Host "=========================================================" -ForegroundColor Cyan

# 1. Cấu hình biến môi trường (bắt buộc OpenJDK 21 cho Gradle 8.11.1)
$jdkPath = (Get-ChildItem "C:\Program Files\Microsoft\jdk-21*" | Select-Object -First 1).FullName
if (-not $jdkPath) {
    $jdkPath = "C:\Program Files\Android\Android Studio\jbr"
}
$env:JAVA_HOME = $jdkPath
$env:ANDROID_HOME = "C:\Users\Admin\AppData\Local\Android\Sdk"
$env:PATH = "$jdkPath\bin;$env:PATH"

$projectRoot = "E:\Project\3_HoatHinh3D"
Set-Location "$projectRoot\android"

# 2. Chạy Unit Tests & biên dịch APK bằng Gradle
Write-Host "`n[1/2] Đang chạy Unit Tests (testDebugUnitTest)..." -ForegroundColor Yellow
& .\gradlew.bat testDebugUnitTest
if ($LASTEXITCODE -ne 0) { throw "Gradle unit tests failed!" }

Write-Host "`n[2/2] Đang biên dịch Android APK (assembleDebug)..." -ForegroundColor Yellow
& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle APK build failed!" }

# 3. Sao chép APK ra thư mục gốc
$sourceApk = "$projectRoot\android\app\build\outputs\apk\debug\app-debug.apk"
$destApk = "$projectRoot\HoatHinh3D-Universal-debug.apk"

if (Test-Path $sourceApk) {
    Copy-Item -Path $sourceApk -Destination $destApk -Force
    $sizeMb = [math]::Round((Get-Item $destApk).Length / 1MB, 2)
    Write-Host "`n=========================================================" -ForegroundColor Green
    Write-Host "  BUILD NATIVE APK THÀNH CÔNG!" -ForegroundColor Green
    Write-Host "  File APK: $destApk ($sizeMb MB)" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
} else {
    throw "Không tìm thấy file APK sau khi build!"
}

Set-Location $projectRoot
