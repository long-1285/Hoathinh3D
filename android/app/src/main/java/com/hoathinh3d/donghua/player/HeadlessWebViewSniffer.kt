package com.hoathinh3d.donghua.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.data.source.VideoStream
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.net.URI
import kotlin.coroutines.resume

/**
 * HeadlessWebViewSniffer sử dụng WebView ẩn + shouldInterceptRequest để bắt các link
 * .m3u8 / .mp4 được tạo động bởi JavaScript và trích xuất chuẩn HTTP Headers
 * (Referer, Origin, User-Agent, Cookie) tránh lỗi 403 Forbidden khi truyền sang ExoPlayer.
 */
object HeadlessWebViewSniffer {

    fun buildStreamHeaders(
        targetUrl: String,
        refererUrl: String? = null,
        requestHeaders: Map<String, String>? = null
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val existingReferer = requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value?.takeIf { it.startsWith("http", ignoreCase = true) }
        val resolvedReferer = existingReferer
            ?: refererUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: runCatching {
                val u = URI(targetUrl)
                "${u.scheme}://${u.host}/"
            }.getOrDefault("")
        if (resolvedReferer.isNotBlank()) {
            headers["Referer"] = resolvedReferer
            runCatching {
                val u = URI(resolvedReferer)
                if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) {
                    headers["Origin"] = "${u.scheme}://${u.host}"
                }
            }
        }
        val existingUa = requestHeaders?.entries
            ?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }
        headers["User-Agent"] = existingUa ?: HttpClientProvider.DEFAULT_UA
        val existingCookie = requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }
        if (!existingCookie.isNullOrBlank()) {
            headers["Cookie"] = existingCookie
        }
        return headers
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniffVideoStream(
        context: Context,
        embedUrl: String,
        refererUrl: String? = null,
        adBlock: Boolean = true,
        timeoutMs: Long = 5500L
    ): VideoStream? = suspendCancellableCoroutine { cont ->
        val baseReferer = EmbedFallbackView.resolveBaseReferer(embedUrl, refererUrl)
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            if (!cont.isActive) return@post
            var settled = false
            val wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.userAgentString = HttpClientProvider.DEFAULT_UA
            }
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)

            fun finish(result: VideoStream?) {
                if (settled) return
                settled = true
                mainHandler.removeCallbacksAndMessages(wv)
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
                if (cont.isActive) cont.resume(result)
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val u = request?.url?.toString().orEmpty()
                    if (adBlock && EmbedFallbackView.isAdHost(u)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    val cleanLow = u.substringBefore('?').lowercase()
                    val isDirectMedia = !EmbedFallbackView.isAdHost(u) && (
                        EmbedFallbackView.isM3u8StreamUrl(u) ||
                            cleanLow.endsWith(".mp4") ||
                            cleanLow.contains(".mp4?")
                        )
                    if (isDirectMedia) {
                        val hdrs = buildStreamHeaders(
                            targetUrl = u,
                            refererUrl = baseReferer,
                            requestHeaders = request?.requestHeaders
                        )
                        val stream = VideoStream(streamUrl = u, headers = hdrs)
                        mainHandler.post { finish(stream) }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    if (cleanLow.endsWith(".png") || cleanLow.endsWith(".jpg") || cleanLow.endsWith(".jpeg") ||
                        cleanLow.endsWith(".webp") || cleanLow.endsWith(".gif") || cleanLow.endsWith(".woff2") ||
                        cleanLow.endsWith(".woff") || cleanLow.endsWith(".ttf")
                    ) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            val timeoutRunnable = Runnable { finish(null) }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)

            cont.invokeOnCancellation {
                mainHandler.post { finish(null) }
            }

            val html = EmbedFallbackView.buildIframeWrapperHtml(PlayerUtils.appendAutoplayParam(embedUrl))
            wv.loadDataWithBaseURL(baseReferer, html, "text/html", "UTF-8", null)
        }
    }
}
