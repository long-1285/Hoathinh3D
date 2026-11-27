# Script tự động build APK HoatHinh3D Universal Native (Kotlin + XML + Media3 ExoPlayer)
$ErrorActionPreference = "Stop"

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "  BẮT ĐẦU BUILD HOATHINH3D NATIVE ANDROID APK (KOTLIN)  " -ForegroundColor Cyan
Write-Host "=========================================================" -ForegroundColor Cyan

# 1. Tự động dò OpenJDK 21 / 17 & Android SDK (tương thích mọi máy tính mới sau git clone)
$projectRoot = $PSScriptRoot
if (-not $projectRoot) {
    $projectRoot = (Get-Location).Path
}

$jdkCandidates = @()
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdkCandidates += $env:JAVA_HOME
}
$userJava = [System.Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
if ($userJava -and (Test-Path "$userJava\bin\java.exe")) {
    $jdkCandidates += $userJava
}
$machineJava = [System.Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
if ($machineJava -and (Test-Path "$machineJava\bin\java.exe")) {
    $jdkCandidates += $machineJava
}
$jdkPatterns = @(
    "C:\Program Files\Microsoft\jdk-21*",
    "C:\Program Files\Eclipse Adoptium\jdk-21*",
    "C:\Program Files\Java\jdk-21*",
    "C:\Program Files\Android\Android Studio\jbr",
    "C:\Program Files\Microsoft\jdk-17*",
    "C:\Program Files\Eclipse Adoptium\jdk-17*",
    "C:\Android\jdk-21*",
    "C:\Android\jdk-17*",
    "C:\Android\jdk*"
)
foreach ($pat in $jdkPatterns) {
    $found = Get-ChildItem $pat -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    foreach ($item in $found) {
        if (Test-Path "$($item.FullName)\bin\java.exe") {
            $jdkCandidates += $item.FullName
        }
    }
}

$jdkPath = $null
foreach ($cand in ($jdkCandidates | Select-Object -Unique)) {
    $prevErrPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $verOut = cmd /c "`"$cand\bin\java.exe`" -version 2>&1" | Out-String
    $ErrorActionPreference = $prevErrPref
    if ($verOut -match 'version "(17|21)\.') {
        $jdkPath = $cand
        break
    }
}
if (-not $jdkPath -and $jdkCandidates.Count -gt 0) {
    $jdkPath = $jdkCandidates[0]
}
if (-not $jdkPath) {
    throw "Không tìm thấy JDK 21 (hoặc JDK 17). Vui lòng cài OpenJDK 21 và thử lại."
}

$userSdk = [System.Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
$machineSdk = [System.Environment]::GetEnvironmentVariable('ANDROID_HOME', 'Machine')
$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    $userSdk,
    $machineSdk,
    "$env:LOCALAPPDATA\Android\Sdk",
    "C:\Android\Sdk",
    "C:\Android"
) | Where-Object { $_ -and (Test-Path $_) }

$androidSdk = $sdkCandidates | Select-Object -First 1
if (-not $androidSdk -and (Test-Path "$projectRoot\android\local.properties")) {
    $sdkLine = Select-String -Path "$projectRoot\android\local.properties" -Pattern "^sdk\.dir=" | Select-Object -First 1
    if ($sdkLine) {
        $parsedSdk = ($sdkLine.Line -replace "^sdk\.dir=", "") -replace "\\\\", "\" -replace "\\:", ":"
        if (Test-Path $parsedSdk) { $androidSdk = $parsedSdk }
    }
}
if (-not $androidSdk) {
    throw "Không tìm thấy Android SDK! Hãy cài Android SDK hoặc đặt biến môi trường ANDROID_HOME."
}

$env:JAVA_HOME = $jdkPath
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
$env:PATH = "$jdkPath\bin;$env:PATH"

Write-Host "  Project Root : $projectRoot" -ForegroundColor DarkGray
Write-Host "  JAVA_HOME    : $env:JAVA_HOME" -ForegroundColor DarkGray
Write-Host "  ANDROID_HOME : $env:ANDROID_HOME" -ForegroundColor DarkGray

Set-Location "$projectRoot\android"

# 2. Chạy Unit Tests & biên dịch APK bằng Gradle
Write-Host "`n[1/2] Đang chạy Unit Tests (testDebugUnitTest)..." -ForegroundColor Yellow
& .\gradlew.bat testDebugUnitTest
if ($LASTEXITCODE -ne 0) { throw "Gradle unit tests failed!" }

Write-Host "`n[2/2] Đang biên dịch Android APK (assembleDebug)..." -ForegroundColor Yellow
& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle APK build failed!" }

# 3. Sao chép APK ra thư mục gốc với tên gói chính thức HoatHinh3d.apk
$sourceApk = "$projectRoot\android\app\build\outputs\apk\debug\app-debug.apk"
$destApk = "$projectRoot\HoatHinh3d.apk"

if (Test-Path $sourceApk) {
    Copy-Item -Path $sourceApk -Destination $destApk -Force
    foreach ($legacyName in @("HoatHinh3D-Universal-debug.apk", "HoatHinh3D-Universal-v41-install-test.apk")) {
        $legacyPath = Join-Path $projectRoot $legacyName
        if (Test-Path $legacyPath) { Remove-Item -LiteralPath $legacyPath -Force -ErrorAction SilentlyContinue }
    }
    $sizeMb = [math]::Round((Get-Item $destApk).Length / 1MB, 2)
    Write-Host "`n=========================================================" -ForegroundColor Green
    Write-Host "  BUILD NATIVE APK THÀNH CÔNG!" -ForegroundColor Green
    Write-Host "  File APK: $destApk ($sizeMb MB)" -ForegroundColor Green
    Write-Host "=========================================================" -ForegroundColor Green
} else {
    throw "Không tìm thấy file APK sau khi build!"
}

Set-Location $projectRoot
