package com.hoathinh3d.donghua.core

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Bộ đồng bộ nội bộ qua Wi-Fi LAN (không cần máy chủ ngoài, 0 thư viện phụ thuộc).
 * Cho phép Điện thoại mở cổng HTTP tạm trên mạng Wi-Fi gia đình + phát UDP discovery
 * để Android TV tự tìm thấy thiết bị mà không cần gõ IP bằng remote.
 */
object LanSyncManager {
    private const val START_PORT = 8765
    private const val END_PORT = 8775
    private const val DISCOVERY_UDP_PORT = 8776

    data class LanHostSession(
        val url: String,
        val pin: String,
        val port: Int
    )

    data class LanDiscoveredPeer(
        val deviceName: String,
        val ip: String,
        val port: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var beaconJob: Job? = null
    @Volatile
    private var currentSession: LanHostSession? = null

    fun getActiveSession(): LanHostSession? = currentSession

    fun resolveLocalLanIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallbackSiteLocal: String? = null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val addrs = iface.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            return ip
                        }
                        if (addr.isSiteLocalAddress && fallbackSiteLocal == null) {
                            fallbackSiteLocal = ip
                        }
                    }
                }
            }
            fallbackSiteLocal
        } catch (_: Exception) {
            null
        }
    }

    fun startLanSyncServer(): LanHostSession? {
        stopLanSyncServer()
        val ip = resolveLocalLanIpv4() ?: return null
        val pin = ((1000..9999).random()).toString()

        var boundSocket: ServerSocket? = null
        var boundPort = START_PORT
        for (port in START_PORT..END_PORT) {
            val s = runCatching { ServerSocket(port) }.getOrNull()
            if (s != null) {
                boundSocket = s
                boundPort = port
                break
            }
        }
        val srv = boundSocket ?: return null
        serverSocket = srv
        val session = LanHostSession(
            url = "http://$ip:$boundPort/sync?pin=$pin",
            pin = pin,
            port = boundPort
        )
        currentSession = session

        serverJob = scope.launch {
            while (isActive && !srv.isClosed) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                launch { handleClient(client, pin) }
            }
        }
        startDiscoveryBeacon(ip, boundPort)
        AppDiagnostics.log("LAN", "Mở máy chủ đồng bộ tại $ip:$boundPort")
        return session
    }

    private fun startDiscoveryBeacon(ip: String, port: Int) {
        beaconJob?.cancel()
        val devName = runCatching { Build.MODEL?.takeIf { it.isNotBlank() } }.getOrNull() ?: "HoatHinh3D"
        val payload = JSONObject().apply {
            put("app", "HH3D")
            put("device", devName)
            put("ip", ip)
            put("port", port)
        }.toString().toByteArray(Charsets.UTF_8)

        beaconJob = scope.launch {
            runCatching {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    val bcastGlobal = InetAddress.getByName("255.255.255.255")
                    val subnetBcast = runCatching {
                        val parts = ip.split('.')
                        if (parts.size == 4) {
                            InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
                        } else null
                    }.getOrNull()

                    while (isActive && currentSession != null) {
                        runCatching {
                            sock.send(DatagramPacket(payload, payload.size, bcastGlobal, DISCOVERY_UDP_PORT))
                        }
                        if (subnetBcast != null) {
                            runCatching {
                                sock.send(DatagramPacket(payload, payload.size, subnetBcast, DISCOVERY_UDP_PORT))
                            }
                        }
                        delay(1100L)
                    }
                }
            }
        }
    }

    fun parseDiscoveryPacket(raw: String): LanDiscoveredPeer? {
        return try {
            val obj = JSONObject(raw.trim())
            if (obj.optString("app") != "HH3D") return null
            val ip = obj.optString("ip").trim()
            val port = obj.optInt("port", -1)
            if (ip.isBlank() || port !in 1..65535) return null
            val device = obj.optString("device", "Thiết bị HH3D").ifBlank { "Thiết bị HH3D" }
            LanDiscoveredPeer(deviceName = device, ip = ip, port = port)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun discoverLanPeers(timeoutMs: Long = 2200L): List<LanDiscoveredPeer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, LanDiscoveredPeer>()
        val deadline = System.currentTimeMillis() + timeoutMs
        runCatching {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(InetSocketAddress(DISCOVERY_UDP_PORT))
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    val remaining = (deadline - System.currentTimeMillis()).toInt().coerceIn(80, 600)
                    sock.soTimeout = remaining
                    val packet = DatagramPacket(buf, buf.size)
                    val got = runCatching { sock.receive(packet); true }.getOrDefault(false)
                    if (!got) continue
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parseDiscoveryPacket(raw)?.let { peer ->
                        found["${peer.ip}:${peer.port}"] = peer
                    }
                }
            }
        }
        found.values.toList()
    }

    private fun handleClient(socket: Socket, expectedPin: String) {
        socket.use { sock ->
            runCatching {
                sock.soTimeout = 4000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine().orEmpty()
                val out = sock.getOutputStream()
                val ok = requestLine.contains("pin=$expectedPin")
                if (!ok) {
                    val body = "{\"error\":\"PIN không hợp lệ\"}".toByteArray(Charsets.UTF_8)
                    val resp = "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    out.write(resp.toByteArray(Charsets.UTF_8))
                    out.write(body)
                    out.flush()
                    return
                }
                val jsonBytes = PrefsStore.exportFullBackup().toByteArray(Charsets.UTF_8)
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${jsonBytes.size}\r\nConnection: close\r\n\r\n"
                out.write(headers.toByteArray(Charsets.UTF_8))
                out.write(jsonBytes)
                out.flush()
            }
        }
    }

    fun stopLanSyncServer() {
        currentSession = null
        beaconJob?.cancel()
        beaconJob = null
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun normalizeLanSyncUrl(rawInput: String, pinInput: String): String {
        val trimmed = rawInput.trim()
        val pin = pinInput.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            val hostPort = if (trimmed.contains(":")) trimmed else "$trimmed:$START_PORT"
            "http://$hostPort"
        }
        return when {
            withScheme.contains("/sync") && withScheme.contains("pin=") -> withScheme
            withScheme.contains("/sync") -> if (pin.isNotEmpty()) "$withScheme?pin=$pin" else withScheme
            else -> {
                val base = withScheme.trimEnd('/')
                if (pin.isNotEmpty()) "$base/sync?pin=$pin" else "$base/sync"
            }
        }
    }

    suspend fun pullAndImportFromLan(rawHostOrUrl: String, pin: String = ""): Boolean = withContext(Dispatchers.IO) {
        val url = normalizeLanSyncUrl(rawHostOrUrl, pin)
        if (url.isBlank()) return@withContext false
        return@withContext try {
            val json = HttpClientProvider.getText(url, timeoutMs = 5000L)
            PrefsStore.importFullBackup(json)
        } catch (_: Exception) {
            false
        }
    }
}
