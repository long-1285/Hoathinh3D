package com.hoathinh3d.donghua.core

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.util.ContentLengthInputStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kết nối Glide trực tiếp qua OkHttpClient của HttpClientProvider để ảnh poster trên Trang chủ / Chi tiết
 * được hưởng trọn vẹn Root CA hiện đại (Android 6.0.1 / MiTV4), TLS 1.2 và DoH DNS vượt chặn.
 */
class GlideOkHttpUrlLoader(private val client: Call.Factory) : ModelLoader<GlideUrl, InputStream> {

    override fun handles(url: GlideUrl): Boolean = true

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> {
        return ModelLoader.LoadData(model, OkHttpStreamFetcher(client, model))
    }

    class Factory(private val client: Call.Factory) : ModelLoaderFactory<GlideUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> =
            GlideOkHttpUrlLoader(client)

        override fun teardown() = Unit
    }

    private class OkHttpStreamFetcher(
        private val client: Call.Factory,
        private val url: GlideUrl
    ) : DataFetcher<InputStream>, Callback {

        private var stream: InputStream? = null
        private var responseBody: ResponseBody? = null
        private var callback: DataFetcher.DataCallback<in InputStream>? = null

        @Volatile
        private var call: Call? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            this.callback = callback
            val urlStr = url.toStringUrl()
            val reqBuilder = Request.Builder().url(urlStr)
            var hasUa = false
            var hasReferer = false
            for ((k, v) in url.headers) {
                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                if (k.equals("Referer", ignoreCase = true)) hasReferer = true
                reqBuilder.addHeader(k, v)
            }
            if (!hasUa) {
                reqBuilder.header("User-Agent", HttpClientProvider.DEFAULT_UA)
            }
            if (!hasReferer && urlStr.contains("yanhh3d.", ignoreCase = true)) {
                val origin = runCatching {
                    val u = java.net.URI(urlStr)
                    "${u.scheme}://${u.host}/"
                }.getOrNull()
                if (!origin.isNullOrBlank()) reqBuilder.header("Referer", origin)
            }
            val c = client.newCall(reqBuilder.build())
            call = c
            c.enqueue(this)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (loggedImageFailures.incrementAndGet() <= 8) {
                AppDiagnostics.log("Image", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
            }
            callback?.onLoadFailed(e)
        }

        override fun onResponse(call: Call, response: Response) {
            responseBody = response.body
            if (response.isSuccessful) {
                val body = responseBody
                if (body != null) {
                    val contentLength = body.contentLength()
                    stream = ContentLengthInputStream.obtain(body.byteStream(), contentLength)
                    callback?.onDataReady(stream)
                } else {
                    callback?.onLoadFailed(HttpException("Empty image body", response.code))
                }
            } else {
                if (loggedImageFailures.incrementAndGet() <= 8) {
                    AppDiagnostics.log("Image", "HTTP ${response.code} cho ${url.toStringUrl().take(60)}")
                }
                callback?.onLoadFailed(HttpException(response.message, response.code))
            }
        }

        override fun cleanup() {
            runCatching { stream?.close() }
            runCatching { responseBody?.close() }
            callback = null
        }

        override fun cancel() {
            call?.cancel()
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.REMOTE

        companion object {
            private val loggedImageFailures = AtomicInteger(0)
        }
    }
}
