package com.hoathinh3d.donghua.data.offline

import android.content.Context
import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.data.model.OfflineEpisodeRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

object HlsOfflineManager {
    private const val MAX_SEGMENTS_PER_EPISODE = 240
    private const val CONCURRENCY = 4

    fun buildEpisodeKey(donghuaId: String, episodeNum: Int): String = "${donghuaId}__ep_$episodeNum"

    data class ParsedM3u8(
        val isMaster: Boolean,
        val variants: List<String>,
        val segments: List<String>,
        val rawLines: List<String>
    )

    fun parseM3u8Lines(text: String): ParsedM3u8 {
        val lines = text.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        val variants = mutableListOf<String>()
        val segments = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("#")) continue
            if (isMaster) variants.add(line) else segments.add(line)
        }
        return ParsedM3u8(isMaster, variants, segments, lines)
    }

    fun resolvePlaylistUrl(baseUrl: String, entry: String): String {
        return try {
            URI(baseUrl).resolve(entry).toString()
        } catch (_: Exception) {
            entry
        }
    }

    suspend fun cacheHlsEpisode(
        context: Context,
        donghuaId: String,
        title: String,
        poster: String,
        episodeNum: Int,
        episodeName: String,
        serverName: String,
        streamUrl: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): OfflineEpisodeRecord = withContext(Dispatchers.IO) {
        require(streamUrl.isNotBlank() && streamUrl.contains(".m3u8", ignoreCase = true)) {
            "Chỉ hỗ trợ lưu offline các tập dùng luồng HLS (.m3u8)."
        }

        val epKey = buildEpisodeKey(donghuaId, episodeNum)
        val epDir = File(context.filesDir, "offline_hls/$epKey").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        onProgress?.invoke(5, "Đang tải danh sách phân đoạn HLS...")
        val masterText = HttpClientProvider.getText(streamUrl, timeoutMs = 10_000L)
        val cachedUrls = mutableListOf(streamUrl)
        var totalBytes = masterText.toByteArray().size.toLong()

        var mediaPlaylistUrl = streamUrl
        var mediaText = masterText
        var parsed = parseM3u8Lines(masterText)

        if (parsed.isMaster && parsed.variants.isNotEmpty()) {
            mediaPlaylistUrl = resolvePlaylistUrl(streamUrl, parsed.variants.last())
            mediaText = HttpClientProvider.getText(mediaPlaylistUrl, timeoutMs = 10_000L)
            cachedUrls.add(mediaPlaylistUrl)
            totalBytes += mediaText.toByteArray().size.toLong()
            parsed = parseM3u8Lines(mediaText)
        }

        val segList = parsed.segments.take(MAX_SEGMENTS_PER_EPISODE)
        require(segList.isNotEmpty()) { "Playlist HLS không chứa phân đoạn video hợp lệ." }

        // Map original segment URI -> local file name
        val segFileNames = HashMap<String, String>(segList.size)
        segList.forEachIndexed { idx, rel ->
            val rawExt = rel.substringBefore('?').substringAfterLast('.', "ts").take(6).ifEmpty { "ts" }
            val ext = if (rawExt.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")) "ts" else rawExt
            segFileNames[rel] = String.format(java.util.Locale.US, "seg_%04d.%s", idx, ext)
        }

        // Download encryption key / init map if referenced in tags
        val tagUrlMap = HashMap<String, String>()
        var extraIdx = 0
        for (line in parsed.rawLines) {
            if ((line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")) && line.contains("URI=\"")) {
                val uriMatch = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                if (!uriMatch.isNullOrBlank() && !tagUrlMap.containsKey(uriMatch)) {
                    val abs = resolvePlaylistUrl(mediaPlaylistUrl, uriMatch)
                    val localName = "extra_${extraIdx++}.bin"
                    runCatching {
                        val bytes = HttpClientProvider.getBytes(abs, timeoutMs = 10_000L)
                        File(epDir, localName).writeBytes(bytes)
                        totalBytes += bytes.size
                        tagUrlMap[uriMatch] = localName
                    }
                }
            }
        }

        var completed = 0
        for (start in segList.indices step CONCURRENCY) {
            val batch = segList.subList(start, minOf(start + CONCURRENCY, segList.size))
            val downloadedBytes = batch.map { segRel ->
                async {
                    val segUrl = resolvePlaylistUrl(mediaPlaylistUrl, segRel)
                    val fileName = segFileNames[segRel] ?: return@async 0L
                    runCatching {
                        val rawBytes = HttpClientProvider.getBytes(segUrl, timeoutMs = 12_000L)
                        val offset = com.hoathinh3d.donghua.player.HlsCacheProvider.findPngCamouflageTsOffset(rawBytes, rawBytes.size)
                        val cleanBytes = if (offset > 0) rawBytes.copyOfRange(offset, rawBytes.size) else rawBytes
                        File(epDir, fileName).writeBytes(cleanBytes)
                        synchronized(cachedUrls) { cachedUrls.add(segUrl) }
                        cleanBytes.size.toLong()
                    }.getOrDefault(0L)
                }
            }.awaitAll().sum()
            totalBytes += downloadedBytes
            completed = minOf(segList.size, start + CONCURRENCY)
            val pct = (10 + (completed.toDouble() / segList.size) * 89).toInt().coerceIn(10, 99)
            onProgress?.invoke(pct, "Đã tải $completed/${segList.size} phân đoạn")
        }

        // Rewrite local media playlist keeping only downloaded segments so ExoPlayer plays 100% offline
        val rewrittenLines = mutableListOf<String>()
        var includedCount = 0
        var skippingNextSegment = false
        for (line in parsed.rawLines) {
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF")) {
                    // Check ahead if next non-comment segment is within MAX_SEGMENTS_PER_EPISODE
                    skippingNextSegment = includedCount >= segList.size
                    if (!skippingNextSegment) rewrittenLines.add(line)
                } else if (line.startsWith("#EXT-X-ENDLIST")) {
                    // Will append after loop
                } else {
                    var rewrittenTag = line
                    for ((origUri, localName) in tagUrlMap) {
                        rewrittenTag = rewrittenTag.replace(origUri, localName)
                    }
                    rewrittenLines.add(rewrittenTag)
                }
            } else {
                val localName = segFileNames[line]
                if (localName != null && File(epDir, localName).exists()) {
                    rewrittenLines.add(localName)
                    includedCount++
                }
            }
        }
        rewrittenLines.add("#EXT-X-ENDLIST")

        val localPlaylistFile = File(epDir, "index.m3u8")
        localPlaylistFile.writeText(rewrittenLines.joinToString("\n"))

        val record = OfflineEpisodeRecord(
            key = epKey,
            donghuaId = donghuaId,
            title = title,
            poster = poster,
            episodeNum = episodeNum,
            episodeName = episodeName,
            serverName = serverName,
            streamUrl = streamUrl,
            localM3u8Path = localPlaylistFile.absolutePath,
            cachedUrls = cachedUrls,
            segmentCount = includedCount,
            approxBytes = totalBytes,
            savedAt = System.currentTimeMillis()
        )
        PrefsStore.saveOfflineEpisode(record)
        onProgress?.invoke(100, "Hoàn tất lưu xem offline!")
        record
    }

    fun removeOfflineEpisode(context: Context, key: String) {
        val records = PrefsStore.getOfflineEpisodes()
        val target = records[key]
        if (target != null && target.localM3u8Path.isNotBlank()) {
            File(target.localM3u8Path).parentFile?.deleteRecursively()
        } else {
            File(context.filesDir, "offline_hls/$key").deleteRecursively()
        }
        PrefsStore.deleteOfflineEpisode(key)
    }
}
