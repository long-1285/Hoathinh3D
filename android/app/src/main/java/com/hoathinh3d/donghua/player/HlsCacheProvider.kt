package com.hoathinh3d.donghua.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.data.offline.HlsOfflineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Bộ lọc phân đoạn quảng cáo nhúng trong playlist HLS (#EXT-X-DISCONTINUITY / CDN quảng cáo)
 * chạy trực tiếp trong bộ nhớ mà không cần mở HTTP server trung gian.
 */
object HlsPlaylistAdFilter {
    private val AD_KEYWORDS = listOf(
        "adcenter", "doubleclick", "googlesyndication", "googleads", "adservice",
        "/ads/", "/ad_", "quangcao", "casino", "bet88", "jun88", "fb88", "okvip", "hi88", "new88", "mb66"
    )

    fun isKnownAdSegmentUrl(url: String): Boolean {
        val low = url.lowercase()
        return AD_KEYWORDS.any { low.contains(it) }
    }

    private fun extractHost(rawUrl: String, baseUrl: String): String? {
        return runCatching {
            val trimmed = rawUrl.trim()
            val resolved = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                URI(trimmed)
            } else if (baseUrl.isNotBlank()) {
                URI(baseUrl).resolve(trimmed)
            } else {
                null
            }
            resolved?.host?.lowercase()
        }.getOrNull()
    }

    private data class SegmentBlock(
        val lines: MutableList<String> = mutableListOf(),
        var durationSec: Double = 0.0,
        val hosts: MutableSet<String> = mutableSetOf(),
        var hasAdUrl: Boolean = false,
        var segmentCount: Int = 0
    )

    fun stripAdSegments(playlistText: String, playlistUrl: String = ""): Pair<String, Int> {
        if (!playlistText.contains("#EXT-X-DISCONTINUITY", ignoreCase = true) ||
            playlistText.contains("#EXT-X-STREAM-INF", ignoreCase = true)
        ) {
            return playlistText to 0
        }

        val rawLines = playlistText.lines()
        val headerLines = mutableListOf<String>()
        val footerLines = mutableListOf<String>()
        val blocks = mutableListOf<SegmentBlock>()
        var currentBlock: SegmentBlock? = null
        var inSegments = false

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.equals("#EXT-X-ENDLIST", ignoreCase = true)) {
                footerLines.add(line)
                continue
            }
            if (!inSegments && !trimmed.startsWith("#EXTINF:", ignoreCase = true) &&
                !trimmed.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) &&
                !trimmed.startsWith("#EXT-X-KEY", ignoreCase = true)
            ) {
                headerLines.add(line)
                continue
            }
            if (trimmed.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) &&
                !trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE", ignoreCase = true)
            ) {
                inSegments = true
                if (currentBlock != null && currentBlock.lines.isNotEmpty()) {
                    blocks.add(currentBlock)
                }
                currentBlock = SegmentBlock()
                continue
            }
            inSegments = true
            val blk = currentBlock ?: SegmentBlock().also { currentBlock = it }
            blk.lines.add(line)
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                val dur = trimmed.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                blk.durationSec += dur
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                blk.segmentCount++
                if (isKnownAdSegmentUrl(trimmed)) blk.hasAdUrl = true
                extractHost(trimmed, playlistUrl)?.let { blk.hosts.add(it) }
            }
        }
        if (currentBlock != null && currentBlock.lines.isNotEmpty()) {
            blocks.add(currentBlock)
        }
        if (blocks.size < 2) return playlistText to 0

        val hostDurations = HashMap<String, Double>()
        for (b in blocks) {
            if (!b.hasAdUrl) {
                for (h in b.hosts) {
                    hostDurations[h] = (hostDurations[h] ?: 0.0) + b.durationSec
                }
            }
        }
        val dominantEntry = hostDurations.maxByOrNull { it.value }
        val dominantHost = dominantEntry?.key
        val dominantDur = dominantEntry?.value ?: 0.0

        val keptBlocks = mutableListOf<SegmentBlock>()
        var removedSegments = 0
        for (b in blocks) {
            val foreignHostOnly = dominantHost != null &&
                dominantDur >= 90.0 &&
                b.hosts.isNotEmpty() &&
                !b.hosts.contains(dominantHost)
            val isSuspiciousForeignInsert = foreignHostOnly && b.durationSec in 0.1..45.0
            if (b.hasAdUrl || isSuspiciousForeignInsert) {
                removedSegments += b.segmentCount.coerceAtLeast(1)
            } else {
                keptBlocks.add(b)
            }
        }
        if (removedSegments == 0 || keptBlocks.none { it.segmentCount > 0 }) {
            return playlistText to 0
        }

        val out = StringBuilder(playlistText.length)
        for (h in headerLines) out.append(h).append('\n')
        for ((idx, b) in keptBlocks.withIndex()) {
            if (idx > 0) out.append("#EXT-X-DISCONTINUITY\n")
            for (l in b.lines) out.append(l).append('\n')
        }
        for (f in footerLines) out.append(f).append('\n')
        return out.toString() to removedSegments
    }
}

@OptIn(UnstableApi::class)
object HlsCacheProvider {
    private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L // 256 MB LRU HLS segment cache
    private const val LOW_RAM_MAX_CACHE_BYTES = 96L * 1024L * 1024L // 96 MB safe for 2.5GB devices
    private const val ULTRA_LOW_RAM_MAX_CACHE_BYTES = 64L * 1024L * 1024L // 64 MB safe for MiTV4 5GB storage / 1.6GB RAM
    private const val CACHE_DIR_NAME = "media3_hls_cache_v2"
    private const val LEGACY_CACHE_DIR_NAME = "media3_hls_cache"
    private const val INITIAL_PEEK_BYTES = 512
    private const val MAX_IMAGE_HEADER_PEEK_BYTES = 8192

    @Volatile
    private var simpleCache: SimpleCache? = null
    private val prefetchedStreams: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val streamingOkHttpClient: OkHttpClient by lazy {
        HttpClientProvider.okHttpClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val hostLow = req.url.host.lowercase()
                val builder = req.newBuilder()
                if (req.header("Accept") == null) {
                    builder.header("Accept", "*/*")
                }
                if (req.header("Referer") == null && hostLow.contains("streamrpt1")) {
                    val origin = "${req.url.scheme}://${req.url.host}"
                    builder.header("Referer", "$origin/")
                    builder.header("Origin", origin)
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    fun getCache(context: Context): SimpleCache {
        return simpleCache ?: synchronized(this) {
            simpleCache ?: run {
                val appCtx = context.applicationContext
                // Xóa cache v1 cũ từng lưu phân đoạn .png chưa cắt header (như tiktokcdn không đuôi)
                val legacyDir = File(appCtx.cacheDir, LEGACY_CACHE_DIR_NAME)
                if (legacyDir.exists()) {
                    runCatching { legacyDir.deleteRecursively() }
                }
                val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
                val maxBytes = when {
                    com.hoathinh3d.donghua.core.DeviceProfileResolver.isUltraLowRamDevice(appCtx) ->
                        ULTRA_LOW_RAM_MAX_CACHE_BYTES
                    com.hoathinh3d.donghua.core.DeviceProfileResolver.isLowRamDevice(appCtx) ->
                        LOW_RAM_MAX_CACHE_BYTES
                    else -> MAX_CACHE_BYTES
                }
                val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
                val dbProvider = StandaloneDatabaseProvider(appCtx)
                SimpleCache(cacheDir, evictor, dbProvider).also { simpleCache = it }
            }
        }
    }

    fun isCamouflagedImageHeader(bytes: ByteArray, length: Int): Boolean {
        if (length < 4) return false
        if (bytes[0] == 0x47.toByte()) return false
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val isGif = bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
        val isJpg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isRiff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val isBmp = bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()
        return isPng || isGif || isJpg || isRiff || isBmp
    }

    /**
     * Tìm vị trí MPEG-TS sync byte `0x47` lặp lại theo chu kỳ 188 byte nếu phân đoạn bị ngụy trang bằng header ảnh (PNG/GIF/JPG/WEBP/BMP),
     * kể cả URL CDN không có đuôi ảnh như tiktokcdn.com hoặc drive/googleusercontent.
     */
    fun findPngCamouflageTsOffset(bytes: ByteArray, length: Int): Int {
        if (length < 380) return 0
        if (!isCamouflagedImageHeader(bytes, length)) return 0

        val limit = length - 376
        for (i in 1 until limit) {
            if (bytes[i] == 0x47.toByte() && bytes[i + 188] == 0x47.toByte() && bytes[i + 376] == 0x47.toByte()) {
                return i
            }
        }
        return 0
    }

    private class SanitizingTsDataSource(
        private val upstream: DataSource,
        private val adBlockEnabled: () -> Boolean = { true }
    ) : DataSource {
        private val peekBuf = ByteArray(MAX_IMAGE_HEADER_PEEK_BYTES)
        private var peekPos = 0
        private var peekLimit = 0
        private var filteredPlaylistBytes: ByteArray? = null
        private var filteredPlaylistPos = 0

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        private fun isProbablyPlaylist(uri: Uri, headers: Map<String, List<String>>): Boolean {
            val uriStr = uri.toString().lowercase()
            if (uriStr.contains(".m3u8") || uriStr.contains("/stream-plain")) return true
            val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.joinToString(" ")?.lowercase().orEmpty()
            return contentType.contains("mpegurl")
        }

        private fun shouldInspectSegment(uri: Uri): Boolean {
            val pathLow = uri.path.orEmpty().lowercase()
            if (pathLow.endsWith(".m3u8") ||
                pathLow.endsWith(".mp4") ||
                pathLow.endsWith(".m4s") ||
                pathLow.endsWith(".webm") ||
                pathLow.endsWith(".vtt") ||
                pathLow.endsWith(".srt") ||
                pathLow.endsWith(".ass") ||
                pathLow.endsWith(".key")
            ) {
                return false
            }
            return true
        }

        override fun open(dataSpec: DataSpec): Long {
            peekPos = 0
            peekLimit = 0
            filteredPlaylistBytes = null
            filteredPlaylistPos = 0
            val len = upstream.open(dataSpec)
            if (dataSpec.position == 0L && adBlockEnabled() && isProbablyPlaylist(dataSpec.uri, upstream.responseHeaders)) {
                val maxPlaylistBytes = 512 * 1024
                val baos = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                var total = 0
                var hitCap = false
                while (true) {
                    val n = upstream.read(tmp, 0, tmp.size)
                    if (n == C.RESULT_END_OF_INPUT || n <= 0) break
                    baos.write(tmp, 0, n)
                    total += n
                    if (total > maxPlaylistBytes) {
                        hitCap = true
                        break
                    }
                }
                val rawBytes = baos.toByteArray()
                if (!hitCap && rawBytes.isNotEmpty()) {
                    val text = rawBytes.toString(Charsets.UTF_8)
                    val (cleanText, removedCount) = HlsPlaylistAdFilter.stripAdSegments(text, dataSpec.uri.toString())
                    val outBytes = if (removedCount > 0) cleanText.toByteArray(Charsets.UTF_8) else rawBytes
                    filteredPlaylistBytes = outBytes
                    filteredPlaylistPos = 0
                    return outBytes.size.toLong()
                }
                // Vượt 512KB thì không giữ toàn bộ trong memory, vẫn trả phần đã đọc nếu nhỏ hoặc fallback:
                if (rawBytes.isNotEmpty()) {
                    filteredPlaylistBytes = rawBytes
                    filteredPlaylistPos = 0
                    return C.LENGTH_UNSET.toLong()
                }
            }
            if (dataSpec.position == 0L && shouldInspectSegment(dataSpec.uri)) {
                var filled = 0
                while (filled < INITIAL_PEEK_BYTES) {
                    val n = upstream.read(peekBuf, filled, INITIAL_PEEK_BYTES - filled)
                    if (n == C.RESULT_END_OF_INPUT || n <= 0) break
                    filled += n
                }
                if (isCamouflagedImageHeader(peekBuf, filled)) {
                    var tsOffset = findPngCamouflageTsOffset(peekBuf, filled)
                    while (tsOffset == 0 && filled < peekBuf.size) {
                        val n = upstream.read(peekBuf, filled, peekBuf.size - filled)
                        if (n == C.RESULT_END_OF_INPUT || n <= 0) break
                        filled += n
                        tsOffset = findPngCamouflageTsOffset(peekBuf, filled)
                    }
                    peekPos = tsOffset
                    peekLimit = filled
                    return if (len != C.LENGTH_UNSET.toLong() && len > tsOffset) len - tsOffset else C.LENGTH_UNSET.toLong()
                }
                peekPos = 0
                peekLimit = filled
            }
            return len
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val plBytes = filteredPlaylistBytes
            if (plBytes != null) {
                if (filteredPlaylistPos < plBytes.size) {
                    val avail = plBytes.size - filteredPlaylistPos
                    val toCopy = minOf(avail, length)
                    System.arraycopy(plBytes, filteredPlaylistPos, buffer, offset, toCopy)
                    filteredPlaylistPos += toCopy
                    return toCopy
                }
                return upstream.read(buffer, offset, length)
            }
            if (peekPos < peekLimit) {
                val avail = peekLimit - peekPos
                val toCopy = minOf(avail, length)
                System.arraycopy(peekBuf, peekPos, buffer, offset, toCopy)
                peekPos += toCopy
                return toCopy
            }
            return upstream.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): MutableMap<String, MutableList<String>> = upstream.responseHeaders

        override fun close() {
            peekPos = 0
            peekLimit = 0
            filteredPlaylistBytes = null
            filteredPlaylistPos = 0
            upstream.close()
        }
    }

    fun buildDataSourceFactory(context: Context): DataSource.Factory {
        val okHttpFactory = OkHttpDataSource.Factory(streamingOkHttpClient)
            .setUserAgent(HttpClientProvider.DEFAULT_UA)
        val rawUpstream = DefaultDataSource.Factory(context.applicationContext, okHttpFactory)
        val sanitizingUpstream = DataSource.Factory {
            SanitizingTsDataSource(rawUpstream.createDataSource()) {
                runCatching { PrefsStore.getSettings().extensions.adblock }.getOrDefault(true)
            }
        }
        return CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(sanitizingUpstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun buildMediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        return DefaultMediaSourceFactory(buildDataSourceFactory(context))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))
    }

    fun buildHlsMediaSourceFactory(context: Context): HlsMediaSource.Factory {
        return HlsMediaSource.Factory(buildDataSourceFactory(context))
            .setAllowChunklessPreparation(true)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))
    }

    fun getCacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, CACHE_DIR_NAME)

    fun getCacheSizeBytes(context: Context): Long {
        val dir = getCacheDir(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearCache(context: Context): Boolean {
        return synchronized(this) {
            runCatching {
                simpleCache?.release()
                simpleCache = null
                val dir = getCacheDir(context)
                if (dir.exists()) dir.deleteRecursively()
                true
            }.getOrDefault(false)
        }
    }

    fun release() {
        synchronized(this) {
            runCatching { simpleCache?.release() }
            simpleCache = null
        }
    }

    /**
     * Nạp trước playlist + 2 phân đoạn đầu tiên của tập kế tiếp vào SimpleCache
     * thông qua SanitizingTsDataSource để chuyển tập đạt độ trễ 0ms.
     */
    suspend fun prefetchHlsStart(context: Context, streamUrl: String) = withContext(Dispatchers.IO) {
        if (streamUrl.isBlank() || streamUrl.startsWith("file://")) return@withContext
        if (!streamUrl.contains(".m3u8", ignoreCase = true) && !streamUrl.contains("/stream-plain", ignoreCase = true)) {
            return@withContext
        }
        if (!prefetchedStreams.add(streamUrl)) return@withContext

        runCatching {
            val appCtx = context.applicationContext
            if (com.hoathinh3d.donghua.core.DeviceProfileResolver.isUltraLowRamDevice(appCtx)) {
                return@withContext
            }
            val dsFactory = buildDataSourceFactory(appCtx)
            val masterText = readUriTextViaDataSource(dsFactory.createDataSource(), streamUrl, maxBytes = 256 * 1024)
            var mediaUrl = streamUrl
            var mediaText = masterText
            var parsed = HlsOfflineManager.parseM3u8Lines(masterText)
            if (parsed.isMaster && parsed.variants.isNotEmpty()) {
                mediaUrl = HlsOfflineManager.resolvePlaylistUrl(streamUrl, parsed.variants.last())
                mediaText = readUriTextViaDataSource(dsFactory.createDataSource(), mediaUrl, maxBytes = 512 * 1024)
                parsed = HlsOfflineManager.parseM3u8Lines(mediaText)
            }
            val firstSegments = parsed.segments.take(2)
            for (segRel in firstSegments) {
                val segUrl = HlsOfflineManager.resolvePlaylistUrl(mediaUrl, segRel)
                warmSegmentIntoCache(dsFactory.createDataSource(), segUrl, maxBytes = 3 * 1024 * 1024)
            }
        }
    }

    private fun readUriTextViaDataSource(ds: DataSource, url: String, maxBytes: Int): String {
        return try {
            ds.open(DataSpec(Uri.parse(url)))
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (total < maxBytes) {
                val n = ds.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (n == C.RESULT_END_OF_INPUT || n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString("UTF-8")
        } finally {
            runCatching { ds.close() }
        }
    }

    private fun warmSegmentIntoCache(ds: DataSource, url: String, maxBytes: Int) {
        try {
            ds.open(DataSpec(Uri.parse(url)))
            val buf = ByteArray(16384)
            var total = 0
            while (total < maxBytes) {
                val n = ds.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (n == C.RESULT_END_OF_INPUT || n <= 0) break
                total += n
            }
        } finally {
            runCatching { ds.close() }
        }
    }
}
