param(
    [string]$Changelog = "",
    [string]$Token = "",
    [switch]$SkipBuild,
    [switch]$ManifestOnly
)

# Script xuất bản bản cập nhật OTA chính thức lên GitHub Releases + version.json
# Tuyệt đối KHÔNG lưu token GitHub vào mã nguồn hay tệp tin trên ổ đĩa.
$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
if (-not $projectRoot) { $projectRoot = (Get-Location).Path }

$gradlePath = Join-Path $projectRoot "android\app\build.gradle"
$apkPath = Join-Path $projectRoot "HoatHinh3d.apk"
$versionJsonPath = Join-Path $projectRoot "version.json"
$repoSlug = "long-1285/Hoathinh3D"

function Resolve-GitHubToken {
    param([string]$ExplicitToken)
    if (-not [string]::IsNullOrWhiteSpace($ExplicitToken)) { return $ExplicitToken.Trim() }
    if (-not [string]::IsNullOrWhiteSpace($env:GH_TOKEN)) { return $env:GH_TOKEN.Trim() }
    if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) { return $env:GITHUB_TOKEN.Trim() }

    $ghCmd = Get-Command gh -ErrorAction SilentlyContinue
    if ($ghCmd) {
        $prevErr = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $ghTok = (& gh auth token 2>$null | Out-String).Trim()
        $ErrorActionPreference = $prevErr
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($ghTok)) {
            return $ghTok
        }
    }

    $gcmCandidates = @(
        "C:\Program Files\Git\mingw64\bin\git-credential-manager.exe",
        "C:\Program Files (x86)\Git\mingw64\bin\git-credential-manager.exe"
    )
    $gcm = $gcmCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($gcm) {
        $psi = [System.Diagnostics.ProcessStartInfo]::new($gcm, "get")
        $psi.RedirectStandardInput = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $proc = [System.Diagnostics.Process]::Start($psi)
        $bytes = [System.Text.Encoding]::UTF8.GetBytes("protocol=https`nhost=github.com`n`n")
        $proc.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $proc.StandardInput.BaseStream.Close()
        $out = $proc.StandardOutput.ReadToEnd()
        $proc.WaitForExit()
        if ($proc.ExitCode -eq 0) {
            foreach ($line in ($out -split "`r?`n")) {
                if ($line -match '^password=(.+)$') {
                    return $Matches[1].Trim()
                }
            }
        }
    }
    return $null
}

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "  XUẤT BẢN PHIÊN BẢN OTA LÊN GITHUB ($repoSlug)          " -ForegroundColor Cyan
Write-Host "=========================================================" -ForegroundColor Cyan

if (-not $SkipBuild) {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $projectRoot "build-apk.ps1")
    if ($LASTEXITCODE -ne 0) { throw "build-apk.ps1 thất bại!" }
}

if (-not (Test-Path $apkPath)) {
    throw "Không tìm thấy APK tại $apkPath. Hãy chạy build-apk.ps1 trước."
}

$gradleRaw = Get-Content -LiteralPath $gradlePath -Raw -Encoding UTF8
$codeMatch = [regex]::Match($gradleRaw, '(?m)^\s*versionCode\s+(\d+)\s*$')
$nameMatch = [regex]::Match($gradleRaw, '(?m)^\s*versionName\s+"([^"]+)"\s*$')
if (-not $codeMatch.Success -or -not $nameMatch.Success) {
    throw "Không đọc được versionCode / versionName từ $gradlePath"
}

$versionCode = [int]$codeMatch.Groups[1].Value
$versionName = $nameMatch.Groups[1].Value
$tagName = "v$versionCode"
$apkFile = Get-Item -LiteralPath $apkPath
$sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$apkSize = [int64]$apkFile.Length
$apkAssetName = "HoatHinh3d.apk"
$apkUrl = "https://github.com/$repoSlug/releases/download/$tagName/$apkAssetName"
$fallbackApkUrl = "https://github.com/$repoSlug/releases/latest/download/$apkAssetName"
$releasedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

if ([string]::IsNullOrWhiteSpace($Changelog)) {
    $Changelog = "Phát hành v$versionName (build $versionCode)."
}

Write-Host "  Version      : v$versionName (versionCode=$versionCode, tag=$tagName)" -ForegroundColor White
Write-Host "  APK Size     : $apkSize bytes ($([math]::Round($apkSize / 1MB, 2)) MB)" -ForegroundColor White
Write-Host "  SHA-256      : $sha256" -ForegroundColor DarkGray

if (-not $ManifestOnly) {
    $ghToken = Resolve-GitHubToken -ExplicitToken $Token
    if ([string]::IsNullOrWhiteSpace($ghToken)) {
        throw "Không tìm thấy token GitHub trong GH_TOKEN / gh auth / Git Credential Manager để upload GitHub Release."
    }

    $apiHeaders = @{
        "Authorization"        = "Bearer $ghToken"
        "Accept"               = "application/vnd.github+json"
        "X-GitHub-Api-Version" = "2022-11-28"
        "User-Agent"           = "HoatHinh3D-OTA-Publisher"
    }

    $releaseBody = @"
$Changelog

- versionCode: $versionCode
- versionName: $versionName
- SHA-256: $sha256
- Dung lượng APK: $([math]::Round($apkSize / 1MB, 2)) MB ($apkSize bytes)
"@.Trim()

    $releaseObj = $null
    try {
        $releaseObj = Invoke-RestMethod -Method Get -Uri "https://api.github.com/repos/$repoSlug/releases/tags/$tagName" -Headers $apiHeaders
        Write-Host "[1/3] Đã tìm thấy Release $tagName -> cập nhật ghi chú phát hành..." -ForegroundColor Yellow
        $patchPayload = @{
            name       = "HoatHinh3D Universal v$versionName (build $versionCode)"
            body       = $releaseBody
            draft      = $false
            prerelease = $false
        } | ConvertTo-Json -Depth 4
        $releaseObj = Invoke-RestMethod -Method Patch -Uri "https://api.github.com/repos/$repoSlug/releases/$($releaseObj.id)" -Headers $apiHeaders -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($patchPayload))
    } catch {
        $statusCode = $null
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        if ($statusCode -ne 404) {
            throw
        }
        Write-Host "[1/3] Đang tạo GitHub Release mới ($tagName)..." -ForegroundColor Yellow
        $headCommit = (& git -C $projectRoot rev-parse HEAD 2>$null | Out-String).Trim()
        if ([string]::IsNullOrWhiteSpace($headCommit)) { $headCommit = "main" }
        $createPayload = @{
            tag_name         = $tagName
            target_commitish = "main"
            name             = "HoatHinh3D Universal v$versionName (build $versionCode)"
            body             = $releaseBody
            draft            = $false
            prerelease       = $false
        } | ConvertTo-Json -Depth 4
        $releaseObj = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$repoSlug/releases" -Headers $apiHeaders -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($createPayload))
    }

    # Xóa asset cũ cùng tên nếu đã tồn tại để upload lại bản mới nhất
    foreach ($asset in @($releaseObj.assets)) {
        if ($asset.name -eq $apkAssetName) {
            Write-Host "[2/3] Đang thay thế asset $apkAssetName cũ trên Release $tagName..." -ForegroundColor Yellow
            Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$repoSlug/releases/assets/$($asset.id)" -Headers $apiHeaders | Out-Null
        }
    }

    Write-Host "[2/3] Đang tải lên $apkAssetName ($([math]::Round($apkSize / 1MB, 2)) MB) lên GitHub Release..." -ForegroundColor Yellow
    $uploadBase = ($releaseObj.upload_url -replace '\{\?name,label\}$', '')
    $uploadUri = "${uploadBase}?name=$([Uri]::EscapeDataString($apkAssetName))"
    $apkBytes = [System.IO.File]::ReadAllBytes($apkPath)
    $uploadedAsset = Invoke-RestMethod -Method Post -Uri $uploadUri -Headers $apiHeaders -ContentType "application/vnd.android.package-archive" -Body $apkBytes
    $apkUrl = $uploadedAsset.browser_download_url
    $ghToken = $null
}

$manifestObj = [ordered]@{
    versionCode    = $versionCode
    versionName    = $versionName
    tag            = $tagName
    apkUrl         = $apkUrl
    fallbackApkUrl = $fallbackApkUrl
    sha256         = $sha256
    apkSizeBytes   = $apkSize
    changelog      = $Changelog
    releasedAt     = $releasedAt
}
$manifestJson = ($manifestObj | ConvertTo-Json -Depth 4) + "`n"
[System.IO.File]::WriteAllText($versionJsonPath, $manifestJson, [System.Text.UTF8Encoding]::new($false))
Write-Host "[3/3] Đã cập nhật manifest OTA: $versionJsonPath" -ForegroundColor Green
Write-Host "      APK URL: $apkUrl" -ForegroundColor Green
