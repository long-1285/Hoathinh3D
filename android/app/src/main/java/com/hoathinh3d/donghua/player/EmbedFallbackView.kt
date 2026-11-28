package com.hoathinh3d.donghua.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.hoathinh3d.donghua.core.HttpClientProvider
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * WebView Fallback & Hidden M3U8 Sniffer:
 * 1) Thử trích xuất .m3u8 trực tiếp bằng OkHttp (~150ms) từ trang embed.
 * 2) Nếu cần JS runtime, dùng WebView ẩn bọc trong <iframe> thật (loadDataWithBaseURL)
 *    để vượt qua kiểm tra `window.self === window.top` (-2) và Referer của streamfree.vip / streamc.xyz.
 * 3) Chỉ hiển thị EmbedFallbackView (cũng bọc trong <iframe> thật) khi không trích xuất được .m3u8.
 */
class EmbedFallbackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var webView: WebView? = null
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = View.GONE
    }
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    var adBlockEnabled: Boolean = true
    var onM3u8Sniffed: ((String) -> Unit)? = null
    var onPageFinished: (() -> Unit)? = null
    var onEnterCustomFullscreen: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null
    var onExitCustomFullscreen: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        val lp = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        addView(spinner, lp)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = HttpClientProvider.DEFAULT_UA
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (adBlockEnabled && isAdHost(url)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (isM3u8StreamUrl(url) && !isAdHost(url)) {
                    post { onM3u8Sniffed?.invoke(url) }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                return adBlockEnabled && isAdHost(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                spinner.visibility = View.GONE
                onPageFinished?.invoke()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                customFullscreenView = view
                customViewCallback = callback
                onEnterCustomFullscreen?.invoke(view, callback)
            }

            override fun onHideCustomView() {
                customFullscreenView = null
                customViewCallback = null
                onExitCustomFullscreen?.invoke()
            }
        }

        addView(wv, 0, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        return wv
    }

    fun loadEmbed(embedUrl: String, refererUrl: String? = null) {
        val wv = ensureWebView()
        spinner.visibility = View.VISIBLE
        val finalUrl = PlayerUtils.appendAutoplayParam(embedUrl)
        val baseReferer = resolveBaseReferer(embedUrl, refererUrl)
        val html = buildIframeWrapperHtml(finalUrl)
        wv.loadDataWithBaseURL(baseReferer, html, "text/html", "UTF-8", null)
    }

    fun reload() {
        spinner.visibility = View.VISIBLE
        webView?.reload()
    }

    fun stopAndClear() {
        spinner.visibility = View.GONE
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
        }
    }

    fun destroyWebView() {
        webView?.let { wv ->
            removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        webView = null
    }

    companion object {
        private val AD_HOST_KEYWORDS = listOf(
            "doubleclick.net", "googlesyndication", "adservice", "popads", "popcash",
            "propellerads", "exoclick", "juicyads", "trafficjunky", "mgid.com",
            "adsterra", "hilltopads", "onclick", "bet88", "8xbet", "789bet", "kubet"
        )

        fun isAdHost(url: String): Boolean {
            if (url.isBlank()) return false
            val low = url.lowercase()
            val host = runCatching { Uri.parse(low).host.orEmpty() }.getOrDefault("")
            return AD_HOST_KEYWORDS.any { low.contains(it) || host.contains(it) }
        }

        fun isEncryptedEmbedHost(url: String): Boolean {
            if (url.isBlank()) return false
            val low = url.lowercase()
            return low.contains("streamfree.vip") ||
                low.contains("streamfreevip.vip") ||
                low.contains("abysscdn.com")
        }

        fun isM3u8StreamUrl(url: String): Boolean {
            if (url.isBlank()) return false
            // streamfree.vip /hls/... trả về container mã hóa #ENC-AESGCM, KHÔNG phải #EXTM3U thuần cho ExoPlayer
            if (isEncryptedEmbedHost(url)) return false
            val low = url.lowercase()
            return low.contains(".m3u8") ||
                low.contains("playlist.m3u8") ||
                (low.contains("/hls/") && low.contains("m3u8"))
        }

        fun resolveBaseReferer(embedUrl: String, refererUrl: String?): String {
            if (!refererUrl.isNullOrBlank() && refererUrl.startsWith("http")) {
                return refererUrl
            }
            if (embedUrl.contains("streamc.xyz", ignoreCase = true)) {
                return "https://phim.nguonc.com/"
            }
            if (embedUrl.contains("streamfree.vip", ignoreCase = true)) {
                return "https://yanhh3d.ee/"
            }
            return try {
                val u = URI(embedUrl)
                "${u.scheme}://${u.host}/"
            } catch (_: Exception) {
                "https://yanhh3d.ee/"
            }
        }

        fun buildIframeWrapperHtml(embedUrl: String): String {
            val safeSrc = embedUrl
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
                  <style>
                    html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
                    iframe { position: fixed; inset: 0; width: 100%; height: 100%; border: 0; background: #000; }
                  </style>
                </head>
                <body>
                  <iframe
                    src="$safeSrc"
                    referrerpolicy="unsafe-url"
                    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; fullscreen; web-share"
                    allowfullscreen="true"
                    webkitallowfullscreen="true"
                    mozallowfullscreen="true">
                  </iframe>
                </body>
                </html>
            """.trimIndent()
        }

        fun extractM3u8FromHtml(html: String): String? {
            if (html.isBlank()) return null
            val unescaped = html.replace("\\/", "/")
            val m = Regex("""(https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
                .findAll(unescaped)
                .map { it.groupValues[1] }
                .firstOrNull { !isAdHost(it) && !isEncryptedEmbedHost(it) }
            return m
        }

        /**
         * Thử trích xuất .m3u8 nhanh qua HTTP trước; nếu JS tạo link động thì dùng WebView ẩn bọc <iframe>.
         */
        suspend fun sniffM3u8Url(
            context: Context,
            embedUrl: String,
            refererUrl: String? = null,
            adBlock: Boolean = true,
            timeoutMs: Long = 4500L
        ): String? {
            if (isEncryptedEmbedHost(embedUrl)) return null
            val baseReferer = resolveBaseReferer(embedUrl, refererUrl)

            // Fast-path HTTP probe (~150-300ms)
            val httpFound = runCatching {
                val html = HttpClientProvider.getText(
                    embedUrl,
                    mapOf("Referer" to baseReferer),
                    timeoutMs = 2500L
                )
                extractM3u8FromHtml(html)
            }.getOrNull()
            if (!httpFound.isNullOrBlank()) return httpFound

            // Fallback to HeadlessWebViewSniffer wrapped in real <iframe>
            return HeadlessWebViewSniffer.sniffVideoStream(
                context = context,
                embedUrl = embedUrl,
                refererUrl = baseReferer,
                adBlock = adBlock,
                timeoutMs = timeoutMs
            )?.streamUrl
        }
    }
}
