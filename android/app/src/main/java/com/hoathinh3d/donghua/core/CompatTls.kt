package com.hoathinh3d.donghua.core

import android.os.Build
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Hỗ trợ TLS 1.2 + Root CA hiện đại (ISRG Root X1/X2, GlobalSign ECC R4, GTS Root R1, SSL.com ECC, DigiCert G2/G3...)
 * cho các thiết bị Android 6.0.1 / API 23 (như Xiaomi MiTV4-ANSM0 bản vá 2017) mà vẫn giữ nguyên xác thực chữ ký TLS chuẩn.
 */
object CompatTls {
    @Volatile
    private var extraPemBytes: ByteArray? = null

    @Volatile
    private var cachedComposite: Pair<SSLSocketFactory, X509TrustManager>? = null

    fun setExtraRootsPem(pemStream: InputStream?) {
        if (pemStream == null) return
        val bytes = runCatching { pemStream.use { it.readBytes() } }.getOrNull() ?: return
        if (bytes.isNotEmpty()) {
            extraPemBytes = bytes
            cachedComposite = null
        }
    }

    fun parseCertificatesFromPem(pemBytes: ByteArray): List<X509Certificate> {
        if (pemBytes.isEmpty()) return emptyList()
        val cf = CertificateFactory.getInstance("X.509")
        val certs = ArrayList<X509Certificate>()
        val text = pemBytes.toString(Charsets.US_ASCII)
        val matches = Regex(
            "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----"
        ).findAll(text)
        for (m in matches) {
            runCatching {
                val c = cf.generateCertificate(
                    ByteArrayInputStream(m.value.toByteArray(Charsets.US_ASCII))
                ) as? X509Certificate
                if (c != null) certs.add(c)
            }
        }
        return certs
    }

    fun buildTrustManager(extraPem: ByteArray? = extraPemBytes): X509TrustManager {
        val sysFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }
        val systemTm = sysFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val extraCerts = if (extraPem != null) parseCertificatesFromPem(extraPem) else emptyList()
        if (extraCerts.isEmpty()) return systemTm

        val extraKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            extraCerts.forEachIndexed { idx, cert ->
                setCertificateEntry("modern_root_$idx", cert)
            }
        }
        val extraFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(extraKs)
        }
        val extraTm = extraFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
        return CompositeX509TrustManager(systemTm, extraTm)
    }

    private fun getOrCreateSslPair(): Pair<SSLSocketFactory, X509TrustManager>? {
        cachedComposite?.let { return it }
        return synchronized(this) {
            cachedComposite ?: runCatching {
                val tm = buildTrustManager(extraPemBytes)
                val ctx = runCatching { SSLContext.getInstance("TLSv1.2") }
                    .getOrElse { SSLContext.getInstance("TLS") }
                ctx.init(null, arrayOf(tm), null)
                val baseFactory = ctx.socketFactory
                val finalFactory = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                    Tls12SocketFactory(baseFactory)
                } else {
                    baseFactory
                }
                (finalFactory to tm).also { cachedComposite = it }
            }.getOrNull()
        }
    }

    fun applyCompat(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        getOrCreateSslPair()?.let { (factory, tm) ->
            builder.sslSocketFactory(factory, tm)
        }
        val modernSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1)
            .build()
        builder.connectionSpecs(listOf(modernSpec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        if (Build.VERSION.SDK_INT in 1..Build.VERSION_CODES.M) {
            // Tránh lỗi ALPN HTTP/2 trên một số bản BoringSSL Android 6.0.1 (2017)
            builder.protocols(listOf(Protocol.HTTP_1_1))
        }
        return builder
    }

    private class CompositeX509TrustManager(
        private val primary: X509TrustManager,
        private val fallback: X509TrustManager
    ) : X509TrustManager {
        private val combinedIssuers: Array<X509Certificate> by lazy {
            (primary.acceptedIssuers.toList() + fallback.acceptedIssuers.toList()).toTypedArray()
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                primary.checkClientTrusted(chain, authType)
            } catch (_: CertificateException) {
                fallback.checkClientTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                primary.checkServerTrusted(chain, authType)
            } catch (primaryErr: CertificateException) {
                try {
                    fallback.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    throw primaryErr
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = combinedIssuers
    }

    private class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            patch(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            patch(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            patch(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            patch(delegate.createSocket(address, port, localAddress, localPort))

        private fun patch(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supported = socket.supportedProtocols.toSet()
                val desired = listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1").filter { supported.contains(it) }
                if (desired.isNotEmpty()) {
                    socket.enabledProtocols = desired.toTypedArray()
                }
                val supportedCiphers = socket.supportedCipherSuites.toSet()
                val preferredCiphers = listOf(
                    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                    "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                    "TLS_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_RSA_WITH_AES_128_CBC_SHA"
                ).filter { supportedCiphers.contains(it) }
                if (preferredCiphers.isNotEmpty()) {
                    val merged = (preferredCiphers + socket.enabledCipherSuites.toList()).distinct()
                    socket.enabledCipherSuites = merged.toTypedArray()
                }
            }
            return socket
        }
    }
}
