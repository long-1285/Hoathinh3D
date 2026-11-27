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

    fun parseManifestJson(jsonText: String): UpdateManifest? {
        return try {
            val obj = JSONObject(jsonText.trim())
            val explicitSha = normalizeSha256(
                obj.optString("sha256")
                    .ifBlank { obj.optString("apkSha256") }
                    .ifBlank { obj.optString("hash") }
            )
            // Hỗ trợ định dạng JSON tùy chỉnh: {versionCode, versionName, apkUrl, changelog, sha256}
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
            val tag = obj.optString("tag_name").trim().removePrefix("v")
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
                val bodySha = explicitSha.ifBlank {
                    val m = Regex("""sha-?256\s*[:=]\s*([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE).find(body)
                    normalizeSha256(m?.groupValues?.getOrNull(1))
                }
                return UpdateManifest(0, tag, ghApkUrl, body, bodySha)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun checkForUpdate(manifestUrl: String): UpdateCheckResult {
        val cleanUrl = manifestUrl.trim()
        if (cleanUrl.isBlank()) {
            return UpdateCheckResult.NotConfigured("Chưa cấu hình URL kiểm tra cập nhật (JSON/GitHub Release).")
        }
        if (cleanUrl.endsWith(".apk", ignoreCase = true)) {
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
            val body = HttpClientProvider.getText(
                cleanUrl,
                mapOf("Accept" to "application/json"),
                timeoutMs = 7000L
            )
            val parsed = parseManifestJson(body)
                ?: return UpdateCheckResult.Error("Phản hồi cập nhật không đúng định dạng JSON.")
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

    suspend fun downloadApkToCache(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.applicationContext.cacheDir, "updates")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, "HoatHinh3D-update.apk")
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", HttpClientProvider.DEFAULT_UA)
                .build()
            HttpClientProvider.okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                var downloaded = 0L
                var lastPct = -1
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
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
            if (outFile.exists() && outFile.length() > 100_000L) outFile else null
        } catch (e: Exception) {
            AppDiagnostics.log("Update", "Lỗi tải APK: ${e.message}")
            null
        }
    }

    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
