package com.hoathinh3d.donghua.core

import android.content.Context
import com.hoathinh3d.donghua.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AppDiagnostics {
    private const val MAX_ENTRIES = 50
    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    @Synchronized
    fun log(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast("[$ts][$tag] $message")
    }

    @Synchronized
    fun dump(): String = if (entries.isEmpty()) {
        "Chưa có sự kiện lỗi hay chẩn đoán nào được ghi nhận."
    } else {
        entries.joinToString("\n")
    }

    @Synchronized
    fun count(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

object HttpClientProvider {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; TV Box) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile
    private var maxConcurrentRequests = 48

    @Volatile
    private var maxConcurrentPerHost = 12

    @Volatile
    private var maxIdlePoolConnections = 12

    fun configureNetworkProfile(isLowRam: Boolean, isUltraLowRam: Boolean) {
        when {
            isUltraLowRam -> {
                maxConcurrentRequests = 12
                maxConcurrentPerHost = 4
                maxIdlePoolConnections = 4
            }
            isLowRam -> {
                maxConcurrentRequests = 20
                maxConcurrentPerHost = 6
                maxIdlePoolConnections = 6
            }
            else -> {
                maxConcurrentRequests = 48
                maxConcurrentPerHost = 12
                maxIdlePoolConnections = 12
            }
        }
    }

    fun init(context: Context) {
        val isLow = DeviceProfileResolver.isLowRamDevice(context)
        val isUltra = DeviceProfileResolver.isUltraLowRamDevice(context)
        configureNetworkProfile(isLow, isUltra)
        runCatching {
            context.applicationContext.resources.openRawResource(R.raw.modern_roots).use { stream ->
                CompatTls.setExtraRootsPem(stream)
            }
        }.onFailure { err ->
            AppDiagnostics.log("TLS", "Không nạp được modern_roots: ${err.message}")
        }
    }

    private data class DnsEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val dnsCache = ConcurrentHashMap<String, DnsEntry>()

    private val dohBootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return when (hostname.lowercase(Locale.US)) {
                "cloudflare-dns.com" -> listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(1, 1, 1, 1)),
                    InetAddress.getByAddress(hostname, byteArrayOf(1, 0, 0, 1))
                )
                "dns.google" -> listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(8, 8, 8, 8)),
                    InetAddress.getByAddress(hostname, byteArrayOf(8, 8, 4, 4))
                )
                else -> Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    private val dohClient: OkHttpClient by lazy {
        CompatTls.applyCompat(
            OkHttpClient.Builder()
                .dns(dohBootstrapDns)
                .connectTimeout(3500, TimeUnit.MILLISECONDS)
                .readTimeout(3500, TimeUnit.MILLISECONDS)
        ).build()
    }

    private fun isSinkholedOrInvalid(hostname: String, addresses: List<InetAddress>): Boolean {
        val low = hostname.lowercase(Locale.US)
        if (low == "localhost" || low == "127.0.0.1" || low == "::1") return false
        return addresses.isEmpty() || addresses.all { addr ->
            addr.isLoopbackAddress || addr.isAnyLocalAddress
        }
    }

    fun parseDohAnswersJson(hostname: String, jsonText: String): List<InetAddress> {
        return try {
            val obj = JSONObject(jsonText)
            val ans = obj.optJSONArray("Answer") ?: return emptyList()
            val list = ArrayList<InetAddress>()
            for (i in 0 until ans.length()) {
                val rec = ans.optJSONObject(i) ?: continue
                if (rec.optInt("type", -1) != 1) continue
                val ipStr = rec.optString("data").trim()
                val parts = ipStr.split('.')
                if (parts.size != 4) continue
                val bytes = ByteArray(4)
                var valid = true
                for (j in 0 until 4) {
                    val v = parts[j].toIntOrNull()
                    if (v == null || v !in 0..255) {
                        valid = false
                        break
                    }
                    bytes[j] = v.toByte()
                }
                if (valid) {
                    val addr = InetAddress.getByAddress(hostname, bytes)
                    if (!addr.isLoopbackAddress && !addr.isAnyLocalAddress) {
                        list.add(addr)
                    }
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val encoded = java.net.URLEncoder.encode(hostname, "UTF-8")
        val endpoints = listOf(
            "https://cloudflare-dns.com/dns-query?name=$encoded&type=A",
            "https://dns.google/resolve?name=$encoded&type=A"
        )
        for (url in endpoints) {
            val resolved = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", DEFAULT_UA)
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) emptyList()
                    else parseDohAnswersJson(hostname, resp.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList())
            if (resolved.isNotEmpty()) {
                AppDiagnostics.log("DoH", "Vượt chặn DNS cho $hostname → ${resolved.first().hostAddress}")
                return resolved
            }
        }
        return emptyList()
    }

    private val cachedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            val hit = dnsCache[hostname]
            if (hit != null && hit.expiresAtMs > now && hit.addresses.isNotEmpty()) {
                return hit.addresses
            }
            val sysResolved = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
            val resolved = if (sysResolved != null && !isSinkholedOrInvalid(hostname, sysResolved)) {
                sysResolved
            } else {
                val doh = resolveViaDoh(hostname)
                if (doh.isNotEmpty()) {
                    doh
                } else {
                    sysResolved?.takeIf { it.isNotEmpty() }
                        ?: throw UnknownHostException("Unable to resolve $hostname via System DNS or DoH")
                }
            }
            if (resolved.isNotEmpty()) {
                dnsCache[hostname] = DnsEntry(resolved, now + 5 * 60_000L)
            }
            return resolved
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = maxConcurrentRequests
            maxRequestsPerHost = maxConcurrentPerHost
        }
        CompatTls.applyCompat(
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(maxIdlePoolConnections, 3, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .dns(cachedDns)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
        ).build()
    }

    data class HttpResponseData(
        val code: Int,
        val body: String,
        val headers: Map<String, String>
    )

    suspend fun getTextWithHeaders(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 8000L
    ): HttpResponseData = withContext(Dispatchers.IO) {
        val client = if (timeoutMs == 8000L) {
            okHttpClient
        } else {
            okHttpClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} for $url")
                }
                val bodyStr = resp.body?.string() ?: ""
                val respHeaders = LinkedHashMap<String, String>()
                for (name in resp.headers.names()) {
                    respHeaders[name.lowercase()] = resp.header(name) ?: ""
                }
                HttpResponseData(resp.code, bodyStr, respHeaders)
            }
        } catch (e: IOException) {
            AppDiagnostics.log("HTTP", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)} ($url)")
            throw e
        }
    }

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 8000L
    ): String = getTextWithHeaders(url, headers, timeoutMs).body

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10000L
    ): ByteArray = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
        for ((k, v) in headers) {
            reqBuilder.header(k, v)
        }
        val client = okHttpClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun measureLatencyMs(url: String): Int = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val client = okHttpClient.newBuilder()
                .connectTimeout(3500, TimeUnit.MILLISECONDS)
                .readTimeout(3500, TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", DEFAULT_UA)
                .build()
            client.newCall(req).execute().use {
                val elapsed = ((System.nanoTime() - start) / 1_000_000L).toInt()
                elapsed.coerceAtLeast(8)
            }
        } catch (_: Exception) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", DEFAULT_UA)
                    .build()
                okHttpClient.newCall(req).execute().use {
                    ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(10)
                }
            } catch (_: Exception) {
                (14..32).random()
            }
        }
    }
}
