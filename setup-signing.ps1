param(
    [switch]$Init,
    [switch]$AdoptLocalDebug,
    [switch]$Verify,
    [switch]$Force
)

# Quản lý Keystore Ký Dùng Chung (Shared Debug/OTA Keystore) giữa nhiều máy (Công ty & Nhà).
# Tuyệt đối KHÔNG commit file .jks hoặc keystore.properties lên Git; chỉ lưu dấu vân tay SHA-256 công khai tại android/signing-sha256.txt.
$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
if (-not $projectRoot) { $projectRoot = (Get-Location).Path }

$pinFile = Join-Path $projectRoot "android\signing-sha256.txt"
$sharedDir = Join-Path $env:USERPROFILE ".hoathinh3d"
$sharedKeystore = Join-Path $sharedDir "hoathinh3d-shared.jks"
$sharedProps = Join-Path $sharedDir "keystore.properties"

function Resolve-Keytool {
    $candidates = @()
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) {
        $candidates += "$env:JAVA_HOME\bin\keytool.exe"
    }
    foreach ($pat in @(
        "C:\Program Files\Microsoft\jdk-21*\bin\keytool.exe",
        "C:\Program Files\Eclipse Adoptium\jdk-21*\bin\keytool.exe",
        "C:\Program Files\Java\jdk-21*\bin\keytool.exe",
        "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
        "C:\Program Files\Microsoft\jdk-17*\bin\keytool.exe"
    )) {
        $candidates += Get-ChildItem $pat -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName
    }
    $kt = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $kt) {
        $cmd = Get-Command keytool -ErrorAction SilentlyContinue
        if ($cmd) { $kt = $cmd.Source }
    }
    if (-not $kt) { throw "Không tìm thấy keytool.exe (OpenJDK 17/21)." }
    return $kt
}

function Read-PropsFile([string]$Path) {
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $t = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($t) -or $t.StartsWith("#")) { continue }
        $eq = $t.IndexOf('=')
        if ($eq -gt 0) {
            $k = $t.Substring(0, $eq).Trim()
            $v = $t.Substring($eq + 1).Trim()
            $map[$k] = $v
        }
    }
    return $map
}

function Get-CertSha256([string]$KeytoolExe, [string]$StorePath, [string]$StorePass, [string]$Alias) {
    $out = (& $KeytoolExe -list -v -keystore $StorePath -storepass $StorePass -alias $Alias 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Không đọc được keystore tại $StorePath`:`n$out"
    }
    $m = [regex]::Match($out, 'SHA256:\s*([0-9A-Fa-f:]+)')
    if (-not $m.Success) {
        throw "Không trích xuất được SHA-256 từ keystore $StorePath"
    }
    return ($m.Groups[1].Value -replace ':', '').ToLowerInvariant()
}

function Write-KeystoreProps([string]$PropsPath, [string]$StorePath, [string]$StorePass, [string]$Alias, [string]$KeyPass) {
    $escapedStore = $StorePath.Replace('\', '/')
    $content = @"
# HoatHinh3D Shared Signing Config (KHÔNG ĐƯA LÊN GIT)
storeFile=$escapedStore
storePassword=$StorePass
keyAlias=$Alias
keyPassword=$KeyPass
"@.Trim() + "`n"
    [System.IO.File]::WriteAllText($PropsPath, $content, [System.Text.UTF8Encoding]::new($false))
}

function New-RandomSecret([int]$Length = 22) {
    $chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#_-".ToCharArray()
    $bytes = New-Object byte[] $Length
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $sb = New-Object System.Text.StringBuilder $Length
    foreach ($b in $bytes) {
        [void]$sb.Append($chars[$b % $chars.Length])
    }
    return $sb.ToString()
}

$keytool = Resolve-Keytool

if ($Verify) {
    Write-Host "=== KIỂM TRA ĐỒNG BỘ CHỮ KÝ SỐ (SHARED SIGNING) ===" -ForegroundColor Cyan
    if (-not (Test-Path $sharedProps) -or -not (Test-Path $sharedKeystore)) {
        Write-Host "❌ Máy này CHƯA có bộ chữ ký dùng chung tại: $sharedDir" -ForegroundColor Red
        if (Test-Path $pinFile) {
            $expected = (Get-Content -LiteralPath $pinFile -Raw -Encoding UTF8).Trim().ToLowerInvariant()
            Write-Host "   Repo đang khóa dấu vân tay SHA-256: $expected" -ForegroundColor Yellow
            Write-Host "   -> Hãy chép thư mục '.hoathinh3d' từ máy gốc vào '$env:USERPROFILE\.hoathinh3d'" -ForegroundColor Yellow
        }
        exit 1
    }
    $props = Read-PropsFile $sharedProps
    $actualSha = Get-CertSha256 $keytool $sharedKeystore $props["storePassword"] $props["keyAlias"]
    Write-Host "  Keystore : $sharedKeystore" -ForegroundColor White
    Write-Host "  Alias    : $($props['keyAlias'])" -ForegroundColor White
    Write-Host "  SHA-256  : $actualSha" -ForegroundColor Green
    if (Test-Path $pinFile) {
        $expected = (Get-Content -LiteralPath $pinFile -Raw -Encoding UTF8).Trim().ToLowerInvariant()
        if ($actualSha -ne $expected) {
            Write-Host "❌ KHÔNG KHỚP với android/signing-sha256.txt ($expected)!" -ForegroundColor Red
            exit 1
        }
        Write-Host "✅ KHỚP 100% với dấu vân tay OTA của Repo!" -ForegroundColor Green
    } else {
        Write-Host "⚠ Chưa có android/signing-sha256.txt để đối chiếu." -ForegroundColor Yellow
    }
    exit 0
}

if (-not (Test-Path $sharedDir)) {
    New-Item -ItemType Directory -Path $sharedDir -Force | Out-Null
}

if ($AdoptLocalDebug) {
    $localDebug = Join-Path $env:USERPROFILE ".android\debug.keystore"
    if (-not (Test-Path $localDebug)) {
        throw "Không tìm thấy $localDebug trên máy này."
    }
    if ((Test-Path $sharedKeystore) -and -not $Force) {
        throw "Đã tồn tại $sharedKeystore. Truyền thêm -Force nếu muốn ghi đè."
    }
    Copy-Item -LiteralPath $localDebug -Destination $sharedKeystore -Force
    Write-KeystoreProps $sharedProps $sharedKeystore "android" "androiddebugkey" "android"
    $sha = Get-CertSha256 $keytool $sharedKeystore "android" "androiddebugkey"
    [System.IO.File]::WriteAllText($pinFile, "$sha`n", [System.Text.UTF8Encoding]::new($false))
    Write-Host "✅ Đã chuyển debug.keystore cục bộ thành Shared Keystore!" -ForegroundColor Green
    Write-Host "   SHA-256 (công khai trong android/signing-sha256.txt): $sha" -ForegroundColor White
    Write-Host "   -> Hãy chép 2 file trong '$sharedDir' sang máy còn lại và giữ nguyên đường dẫn '%USERPROFILE%\.hoathinh3d\'." -ForegroundColor Yellow
    exit 0
}

# Mặc định: -Init tạo Shared Keystore RSA 2048 mới chuẩn
if ((Test-Path $sharedKeystore) -and (Test-Path $sharedProps) -and -not $Force) {
    Write-Host "ℹ Shared Keystore đã tồn tại tại $sharedDir. Đang xác minh..." -ForegroundColor Yellow
    $props = Read-PropsFile $sharedProps
    $sha = Get-CertSha256 $keytool $sharedKeystore $props["storePassword"] $props["keyAlias"]
    [System.IO.File]::WriteAllText($pinFile, "$sha`n", [System.Text.UTF8Encoding]::new($false))
    Write-Host "✅ Đã đồng bộ android/signing-sha256.txt = $sha" -ForegroundColor Green
    exit 0
}

if (Test-Path $sharedKeystore) { Remove-Item -LiteralPath $sharedKeystore -Force }
if (Test-Path $sharedProps) { Remove-Item -LiteralPath $sharedProps -Force }

$pass = New-RandomSecret 22
$alias = "hoathinh3d_shared"
& $keytool -genkeypair -v `
    -keystore $sharedKeystore `
    -storetype PKCS12 `
    -storepass $pass `
    -alias $alias `
    -keypass $pass `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -dname "CN=HoatHinh3D Universal, OU=MobileTV, O=HoatHinh3D, L=HoChiMinh, C=VN" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Tạo Shared Keystore thất bại!" }

Write-KeystoreProps $sharedProps $sharedKeystore $pass $alias $pass
$sha = Get-CertSha256 $keytool $sharedKeystore $pass $alias
[System.IO.File]::WriteAllText($pinFile, "$sha`n", [System.Text.UTF8Encoding]::new($false))

Write-Host "=========================================================" -ForegroundColor Green
Write-Host "  ĐÃ TẠO BỘ CHỮ KÝ SỐ DÙNG CHUNG (SHARED KEYSTORE)      " -ForegroundColor Green
Write-Host "=========================================================" -ForegroundColor Green
Write-Host "  Thư mục bí mật (KHÔNG ĐẨY GIT): $sharedDir" -ForegroundColor Yellow
Write-Host "  Dấu vân tay SHA-256 (đã lưu Git): $sha" -ForegroundColor White
Write-Host "  -> Copy 2 file trong '$sharedDir' sang '%USERPROFILE%\.hoathinh3d\' ở máy Công ty để build chung 1 chữ ký!" -ForegroundColor Cyan
