package com.hoathinh3d.donghua.data.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hoathinh3d.donghua.R
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.source.YanHh3dNowScraper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Service tải HLS Offline chạy nền bền bỉ (không bị hủy khi người dùng thoát MovieDetailActivity).
 * Hỗ trợ xếp hàng tải 1 tập hoặc tải hàng loạt nhiều tập liên tiếp.
 */
class OfflineDownloadService : Service() {

    data class DownloadTaskPayload(
        val donghuaId: String,
        val title: String,
        val poster: String,
        val episodeNum: Int,
        val serverName: String,
        val episode: EpisodeItem
    ) : Serializable

    data class DownloadUiState(
        val activeDonghuaId: String = "",
        val activeEpisodeNum: Int = -1,
        val progressPct: Int = 0,
        val queuedCount: Int = 0,
        val statusText: String = ""
    )

    companion object {
        private const val CHANNEL_ID = "hh3d_offline_download"
        private const val NOTIF_ID = 3501
        private const val EXTRA_TASKS = "extra_tasks"

        private val _progressState = MutableStateFlow(DownloadUiState())
        val progressState: StateFlow<DownloadUiState> = _progressState

        fun enqueue(context: Context, tasks: List<DownloadTaskPayload>) {
            if (tasks.isEmpty()) return
            val intent = Intent(context, OfflineDownloadService::class.java).apply {
                putExtra(EXTRA_TASKS, ArrayList(tasks))
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                runCatching { context.startService(intent) }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<DownloadTaskPayload>()
    private var workerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        promoteForegroundSafe("Đang chuẩn bị tải tập phim offline...", 0)

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val tasks = intent?.getSerializableExtra(EXTRA_TASKS) as? ArrayList<DownloadTaskPayload>
        if (!tasks.isNullOrEmpty()) {
            tasks.forEach { queue.add(it) }
        }
        startWorkerIfNeeded()
        return START_NOT_STICKY
    }

    private fun startWorkerIfNeeded() {
        if (workerJob?.isActive == true) {
            updateProgressState(
                _progressState.value.copy(queuedCount = queue.size)
            )
            return
        }
        workerJob = scope.launch {
            while (true) {
                val task = queue.poll() ?: break
                updateProgressState(
                    DownloadUiState(
                        activeDonghuaId = task.donghuaId,
                        activeEpisodeNum = task.episodeNum,
                        progressPct = 5,
                        queuedCount = queue.size,
                        statusText = "Đang tải ${task.title} - Tập ${task.episodeNum}"
                    )
                )
                promoteForegroundSafe("Đang tải ${task.title} • Tập ${task.episodeNum}", 5)

                runCatching {
                    val ep = task.episode
                    val resolvedUrl = if (ep.linkM3u8.contains(".m3u8", ignoreCase = true)) {
                        ep.linkM3u8
                    } else {
                        YanHh3dNowScraper.resolveStream(
                            watchPageUrl = ep.wpPageUrl.orEmpty(),
                            serverKey = ep.wpServerType.orEmpty(),
                            refererBase = ep.wpBaseUrl ?: YanHh3dNowScraper.YAN_NOW_BASE
                        ).linkM3u8
                    }
                    HlsOfflineManager.cacheHlsEpisode(
                        context = applicationContext,
                        donghuaId = task.donghuaId,
                        title = task.title,
                        poster = task.poster,
                        episodeNum = task.episodeNum,
                        episodeName = ep.name.ifBlank { "Tập ${task.episodeNum}" },
                        serverName = task.serverName,
                        streamUrl = resolvedUrl
                    ) { pct, _ ->
                        updateProgressState(
                            DownloadUiState(
                                activeDonghuaId = task.donghuaId,
                                activeEpisodeNum = task.episodeNum,
                                progressPct = pct,
                                queuedCount = queue.size,
                                statusText = "Đang lưu Tập ${task.episodeNum}: $pct%"
                            )
                        )
                        promoteForegroundSafe("${task.title} • Tập ${task.episodeNum} ($pct%)", pct)
                    }
                }
            }
            updateProgressState(DownloadUiState())
            stopForegroundSafe()
            stopSelf()
        }
    }

    private fun updateProgressState(next: DownloadUiState) {
        _progressState.value = next
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Lưu phim xem Offline",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Hiển thị tiến trình lưu tập Hoạt Hình 3D offline"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun promoteForegroundSafe(content: String, progress: Int) {
        runCatching {
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("HoatHinh3D • Lưu Offline")
                .setContentText(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }
    }

    private fun stopForegroundSafe() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    override fun onDestroy() {
        workerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
