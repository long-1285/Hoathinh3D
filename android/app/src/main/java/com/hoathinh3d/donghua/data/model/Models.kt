package com.hoathinh3d.donghua.data.model

import java.io.Serializable

data class DonghuaSource(
    val id: String,
    val name: String,
    val url: String,
    val hostname: String? = null,
    val type: String,
    val ping: String,
    val quality: String,
    val desc: String,
    val enabled: Boolean,
    val isCustom: Boolean,
    val addedAt: Long? = null
) : Serializable

data class EpisodeItem(
    val name: String,
    val slug: String,
    val linkM3u8: String = "",
    val linkEmbed: String = "",
    val yanPostId: Int? = null,
    val yanEpSlug: String? = null,
    val yanServerType: String? = null,
    val yanPageUrl: String? = null,
    val wpBaseUrl: String? = null,
    val wpPostId: Int? = null,
    val wpEpSlug: String? = null,
    val wpServerType: String? = null,
    val wpServerSv: String? = null,
    val wpPageUrl: String? = null
) : Serializable

data class WpSourceRef(
    val baseUrl: String,
    val postId: Int,
    val url: String,
    val sourceName: String
) : Serializable

data class ServerGroup(
    val serverName: String,
    val episodes: List<EpisodeItem>
) : Serializable

data class DonghuaMovie(
    val id: String,
    val title: String,
    val originalTitle: String,
    val slug: String? = null,
    val yanPostId: Int? = null,
    val yanUrl: String? = null,
    val wpSources: List<WpSourceRef>? = null,
    val statusLabel: String? = null,
    val studio: String,
    val latestEp: Int,
    val totalEp: Int,
    val rating: Double,
    val views: String,
    val viewsNum: Double,
    val quality: String,
    val audioModes: List<String>,
    val sources: List<String>,
    val genres: List<String>,
    val scheduleDay: String,
    val scheduleTime: String,
    val poster: String,
    val thumb: String? = null,
    val streamIndex: Int = 0,
    val serverGroups: List<ServerGroup>? = null,
    val description: String? = null,
    val customWebUrl: String? = null
) : Serializable

data class WatchProgress(
    val donghuaId: String,
    val title: String,
    val poster: String,
    val episode: Int,
    val currentTime: Double,
    val duration: Double,
    val progressPct: Int,
    val server: String,
    val audio: String,
    val updatedAt: Long
) : Serializable

data class GenreItem(
    val id: String,
    val label: String,
    val icon: String,
    val count: Int? = null,
    val sourceSlug: String? = null,
    val yanCategoryId: Int? = null,
    val yanStatusId: Int? = null,
    val yanShowtimeId: Int? = null,
    val wpCategoryId: Int? = null,
    val wpStatusId: Int? = null,
    val wpShowtimeId: Int? = null
) : Serializable

enum class DeviceMode(val wireValue: String) {
    AUTO("auto"),
    MOBILE("mobile"),
    TABLET("tablet"),
    TV("tv");

    companion object {
        fun fromWire(value: String?): DeviceMode =
            entries.firstOrNull { it.wireValue == value } ?: AUTO
    }
}

enum class ResolvedDeviceProfile(val wireValue: String) {
    MOBILE("mobile"),
    TABLET("tablet"),
    TV("tv")
}

enum class ThemePreset(val wireValue: String, val displayName: String, val desc: String) {
    PEARL("pearl", "Light Pearl (Mặc định)", "Trắng ngọc trai thanh lịch"),
    AQUA("aqua", "Crystal Aqua", "Xanh băng thủy tinh mát dịu"),
    SAKURA("sakura", "Sakura Glass", "Hồng phấn anh đào nhẹ nhàng"),
    DARK("dark", "Dark Aurora", "Kính mờ đêm cực quang");

    companion object {
        fun fromWire(value: String?): ThemePreset =
            entries.firstOrNull { it.wireValue == value } ?: PEARL
    }
}

enum class DarkModePreference(val wireValue: String, val label: String) {
    LIGHT("light", "☀ Sáng"),
    DARK("dark", "🌙 Tối (Dark)"),
    SYSTEM("system", "⚙ Hệ thống");

    companion object {
        fun fromWire(value: String?): DarkModePreference =
            entries.firstOrNull { it.wireValue == value } ?: LIGHT
    }
}

enum class SettingsSubtab(val wireValue: String, val label: String) {
    PLAYER("player", "1. Trình phát tích hợp"),
    SOURCES("sources", "2. Nguồn phim (+Web)"),
    APPEARANCE("appearance", "3. Giao diện"),
    BACKUP("backup", "4. Cập nhật & Sao lưu"),
    EXTENSIONS("extensions", "5. Tiện ích mở rộng")
}

data class ExtensionSettings(
    val adblock: Boolean = true,
    val aiUpscale: Boolean = false,
    val bilingualSub: Boolean = false
) : Serializable

data class AppSettings(
    val deviceMode: String = "auto",
    val theme: String = "pearl",
    val darkMode: String = "light",
    val glassBlur: Int = 24,
    val glassOpacity: Int = 35,
    val ambientOrbs: Boolean = true,
    val preferredSource: String = "YanHH3DNow",
    val cloudAutoSync: Boolean = true,
    val autoNext: Boolean = true,
    val autoSkipIntro: Boolean = false,
    val seekStep: Int = 10,
    val bilingualSub: Boolean = false,
    val defaultAudio: String = "Thuyết Minh",
    val defaultQuality: String = "4K 60FPS",
    val aspectRatio: String = "16:9",
    val volume: Float = 1f,
    val playbackSpeed: Float = 1f,
    val extensions: ExtensionSettings = ExtensionSettings(),
    val subtitleScale: Float = 0.055f,
    val reducedMotion: Boolean = false,
    val nightModeDrc: Boolean = false,
    val autoClearCacheOnExit: Boolean = false,
    val autoUpdateApp: Boolean = true,
    val updateManifestUrl: String = com.hoathinh3d.donghua.core.AppUpdateChecker.DEFAULT_MANIFEST_URL,
    val showHomeHero: Boolean = true,
    val showHomeContinue: Boolean = true,
    val showHomeTrending: Boolean = true
) : Serializable

data class UserProfile(
    val name: String,
    val email: String,
    val avatar: String,
    val lastSync: Long? = null
) : Serializable

data class FavoriteEpisodeMeta(
    val donghuaId: String,
    val lastSeenEp: Int,
    val latestKnownEp: Int,
    val newEpCount: Int,
    val lastCheckedAt: Long
) : Serializable

data class OfflineEpisodeRecord(
    val key: String,
    val donghuaId: String,
    val title: String,
    val poster: String,
    val episodeNum: Int,
    val episodeName: String,
    val serverName: String,
    val streamUrl: String,
    val localM3u8Path: String = "",
    val cachedUrls: List<String> = emptyList(),
    val segmentCount: Int = 0,
    val approxBytes: Long = 0L,
    val savedAt: Long = 0L
) : Serializable

data class StreamResult(
    val linkEmbed: String = "",
    val linkM3u8: String = ""
) : Serializable

data class MovieDetailResult(
    val serverGroups: List<ServerGroup> = emptyList(),
    val description: String? = null,
    val poster: String? = null,
    val totalEp: Int? = null,
    val latestEp: Int? = null
) : Serializable

enum class CatalogAudioFilter(val wireValue: String, val label: String) {
    ALL("all", "Tất cả âm thanh"),
    DUBBED("dubbed", "Thuyết Minh"),
    SUB("sub", "Vietsub")
}

enum class CatalogStatusFilter(val wireValue: String, val label: String) {
    ALL("all", "Tất cả trạng thái"),
    ONGOING("ongoing", "Đang chiếu"),
    COMPLETED("completed", "Hoàn thành")
}

enum class CatalogSortMode(val wireValue: String, val label: String) {
    DEFAULT("default", "Mới cập nhật"),
    MOST_EPISODES("most_ep", "Nhiều tập"),
    TOP_RATED("top_rated", "Đánh giá cao"),
    TITLE_AZ("title_az", "Tên A → Z")
}

data class CatalogPageResult(
    val movies: List<DonghuaMovie>,
    val page: Int,
    val totalPages: Int
) {
    val hasMore: Boolean get() = page < totalPages && movies.isNotEmpty()
}
