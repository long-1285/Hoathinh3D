package com.hoathinh3d.donghua.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.hoathinh3d.donghua.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String = "",
    val sha256: String = ""
)

sealed class UpdateCheckResult {
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class NotConfigured(val reason: String) : UpdateCheckResult()
    data class Error(val reason: String) : UpdateCheckResult()
}

object AppUpdateChecker {

    const val OFFICIAL_GITHUB_REPO = "long-1285/Hoathinh3D"
    const val DEFAULT_MANIFEST_URL =
        "https://raw.githubusercontent.com/$OFFICIAL_GITHUB_REPO/main/version.json"
    const val GITHUB_RELEASES_LATEST_URL =
        "https://api.github.com/repos/$OFFICIAL_GITHUB_REPO/releases/latest"

    fun resolveEffectiveManifestUrl(rawUrl: String?): String =
        rawUrl?.trim().orEmpty().ifBlank { DEFAULT_MANIFEST_URL }

    fun isOfficialGitUrl(rawUrl: String?): Boolean {
        val cleaned = resolveEffectiveManifestUrl(rawUrl)
        return cleaned.equals(DEFAULT_MANIFEST_URL, ignoreCase = true) ||
            cleaned.equals(GITHUB_RELEASES_LATEST_URL, ignoreCase = true)
    }

    fun withCacheBuster(url: String, nowMs: Long = System.currentTimeMillis()): String {
        val trimmed = url.trim()
        if (!trimmed.contains("raw.githubusercontent.com", ignoreCase = true)) {
            return trimmed
        }
        val sep = if (trimmed.contains('?')) '&' else '?'
        return "$trimmed${sep}t=$nowMs"
    }

    fun isNewerVersion(
        remoteCode: Int,
        remoteName: String,
        currentCode: Int = BuildConfig.VERSION_CODE,
        currentName: String = BuildConfig.VERSION_NAME
    ): Boolean {
        if (remoteCode > currentCode) return true
        if (remoteCode in 1 until currentCode) return false
        return compareSemVer(remoteName, currentName) > 0
    }

    fun compareSemVer(a: String, b: String): Int {
        val numsA = Regex("""\d+""").findAll(a).mapNotNull { it.value.toIntOrNull() }.toList()
        val numsB = Regex("""\d+""").findAll(b).mapNotNull { it.value.toIntOrNull() }.toList()
        val maxLen = maxOf(numsA.size, numsB.size)
        for (i in 0 until maxLen) {
            val va = numsA.getOrElse(i) { 0 }
            val vb = numsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    fun normalizeSha256(raw: String?): String {
        val cleaned = raw.orEmpty()
            .trim()
            .removePrefix("sha256:")
            .removePrefix("SHA256:")
            .lowercase()
        return if (Regex("^[0-9a-f]{64}$").matches(cleaned)) cleaned else ""
    }

    fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expectedHex: String?): Boolean {
        val normalized = normalizeSha256(expectedHex)
        if (normalized.isEmpty()) return true
        return runCatching {
            file.exists() && computeSha256(file).equals(normalized, ignoreCase = true)
        }.getOrDefault(false)
    }

    fun isValidApkArchive(file: File): Boolean {
        if (!file.exists() || file.length() <= 100_000L) return false
        return runCatching {
            file.inputStream().use { input ->
                val b0 = input.read()
                val b1 = input.read()
                b0 == 0x50 && b1 == 0x4B // 'P''K' ZIP/APK magic header
            }
        }.getOrDefault(false)
    }

    fun parseManifestJson(jsonText: String): UpdateManifest? {
        return try {
            val obj = JSONObject(jsonText.trim())
            val explicitSha = normalizeSha256(
                obj.optString("sha256")
                    .ifBlank { obj.optString("apkSha256") }
                    .ifBlank { obj.optString("hash") }
            )
            // Hỗ trợ định dạng JSON chuẩn (`version.json`): {versionCode, versionName, apkUrl, changelog, sha256}
            val directApk = obj.optString("apkUrl").trim()
                .ifBlank { obj.optString("downloadUrl").trim() }
                .ifBlank { obj.optString("url").trim() }
            if (directApk.startsWith("http", ignoreCase = true)) {
                val code = obj.optInt("versionCode", 0)
                val name = obj.optString("versionName")
                    .ifBlank { obj.optString("version") }
                    .ifBlank { if (code > 0) "build $code" else "" }
                val log = obj.optString("changelog").ifBlank { obj.optString("notes") }
                if (code > 0 || name.isNotBlank()) {
                    return UpdateManifest(code, name, directApk, log.trim(), explicitSha)
                }
            }
            // Hỗ trợ GitHub Releases API JSON: {tag_name, body, assets:[{browser_download_url}]}
            val tag = obj.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            val assets = obj.optJSONArray("assets")
            var ghApkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val item = assets.optJSONObject(i) ?: continue
                    val dl = item.optString("browser_download_url").trim()
                    if (dl.endsWith(".apk", ignoreCase = true)) {
                        ghApkUrl = dl
                        break
                    }
                }
            }
            if (ghApkUrl.isNotBlank() && tag.isNotBlank()) {
                val body = obj.optString("body").trim()
                val bodyCode = Regex("""(?:versionCode|build)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(body)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: tag.toIntOrNull()
                    ?: 0
                val bodySha = explicitSha.ifBlank {
                    val m = Regex("""sha-?256\s*[:=]\s*([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE).find(body)
                    normalizeSha256(m?.groupValues?.getOrNull(1))
                }
                return UpdateManifest(bodyCode, tag, ghApkUrl, body, bodySha)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchAndParseManifest(url: String): UpdateManifest {
        val headers = mapOf(
            "Accept" to "application/json",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
        val body = HttpClientProvider.getText(
            withCacheBuster(url),
            headers,
            timeoutMs = 9000L
        )
        return parseManifestJson(body)
            ?: throw IllegalStateException("Phản hồi cập nhật không đúng định dạng JSON.")
    }

    suspend fun checkForUpdate(manifestUrl: String = DEFAULT_MANIFEST_URL): UpdateCheckResult {
        val cleanUrl = resolveEffectiveManifestUrl(manifestUrl)
        if (cleanUrl.substringBefore('?').endsWith(".apk", ignoreCase = true)) {
            return UpdateCheckResult.Available(
                UpdateManifest(
                    versionCode = BuildConfig.VERSION_CODE + 1,
                    versionName = "Mới nhất",
                    apkUrl = cleanUrl,
                    changelog = "Gói APK trực tiếp từ liên kết đã cấu hình."
                )
            )
        }
        return try {
            val parsed = try {
                fetchAndParseManifest(cleanUrl)
            } catch (primaryErr: Exception) {
                if (cleanUrl.equals(DEFAULT_MANIFEST_URL, ignoreCase = true)) {
                    AppDiagnostics.log(
                        "Update",
                        "Fallback sang GitHub Releases API sau lỗi version.json: ${primaryErr.message}"
                    )
                    fetchAndParseManifest(GITHUB_RELEASES_LATEST_URL)
                } else {
                    throw primaryErr
                }
            }
            if (isNewerVersion(parsed.versionCode, parsed.versionName)) {
                UpdateCheckResult.Available(parsed)
            } else {
                UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            AppDiagnostics.log("Update", "Lỗi kiểm tra cập nhật: ${e.message}")
            UpdateCheckResult.Error("Không thể kiểm tra cập nhật: ${e.message ?: "Lỗi mạng"}")
        }
    }

    fun resolveUpdateDir(context: Context): File {
        val appCtx = context.applicationContext
        // Trên Android 6.0.1 (API 23, VD Xiaomi MiTV4-ANSM0), PackageInstaller không đọc được file:// trong /data/user/0/.../cache (0700),
        // nên dùng externalCacheDir (không cần quyền WRITE_EXTERNAL_STORAGE trên API 19+).
        val baseDir = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            appCtx.externalCacheDir ?: appCtx.cacheDir
        } else {
            appCtx.cacheDir
        }
        return File(baseDir, "updates").apply { if (!exists()) mkdirs() }
    }

    fun cleanupOldUpdates(context: Context) {
        val appCtx = context.applicationContext
        val dirs = listOfNotNull(
            File(appCtx.cacheDir, "updates"),
            appCtx.externalCacheDir?.let { File(it, "updates") }
        )
        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    suspend fun downloadApkToCache(
        context: Context,
        apkUrl: String,
        onProgress: (pct: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = resolveUpdateDir(context)
            val outFile = File(dir, "HoatHinh3D-update.apk")
            if (outFile.exists()) outFile.delete()

            val downloadClient = HttpClientProvider.okHttpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .build()

            val req = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", HttpClientProvider.DEFAULT_UA)
                .header("Cache-Control", "no-cache")
                .build()

            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppDiagnostics.log("Update", "Tải APK HTTP ${resp.code} ($apkUrl)")
                    return@withContext null
                }
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                var downloaded = 0L
                var lastPct = -2
                var lastStepBytes = 0L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0L) {
                                val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct, downloaded, total)
                                }
                            } else if (downloaded - lastStepBytes >= 262_144L) {
                                lastStepBytes = downloaded
                                onProgress(-1, downloaded, -1L)
                            }
                        }
                    }
                }
            }
            if (!isValidApkArchive(outFile)) {
                AppDiagnostics.log("Update", "Tệp tải về không phải APK hợp lệ (${outFile.length()} bytes)")
                runCatching { outFile.delete() }
                return@withContext null
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                runCatching { outFile.setReadable(true, false) }
            }
            outFile
        } catch (e: Exception) {
            AppDiagnostics.log("Update", "Lỗi tải APK: ${e.message}")
            null
        }
    }

    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } else {
            runCatching { apkFile.setReadable(true, false) }
            Uri.fromFile(apkFile)
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
