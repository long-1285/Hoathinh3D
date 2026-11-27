package com.hoathinh3d.donghua.core

import android.content.Context
import android.content.SharedPreferences
import com.hoathinh3d.donghua.data.model.AppSettings
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.DonghuaSource
import com.hoathinh3d.donghua.data.model.ExtensionSettings
import com.hoathinh3d.donghua.data.model.FavoriteEpisodeMeta
import com.hoathinh3d.donghua.data.model.InitialData
import com.hoathinh3d.donghua.data.model.OfflineEpisodeRecord
import com.hoathinh3d.donghua.data.model.ThemePreset
import com.hoathinh3d.donghua.data.model.UserProfile
import com.hoathinh3d.donghua.data.model.WatchProgress
import org.json.JSONArray
import org.json.JSONObject

object PrefsStore {
    private const val PREF_NAME = "hh3d_prefs_store"

    object Keys {
        const val HISTORY = "hh3d_watch_history_v2"
        const val FAVORITES = "hh3d_favorites_v2"
        const val FAVORITES_EP_META = "hh3d_favorites_ep_meta_v1"
        const val OFFLINE_EPISODES = "hh3d_offline_episodes_v1"
        const val WATCHED_EPISODES = "hh3d_watched_episodes_v1"
        const val SKIP_INTRO_MAP = "hh3d_skip_intro_sec_v1"
        const val SOURCES = "hh3d_sources_list_v2"
        const val CUSTOM_MOVIES = "hh3d_custom_movies_v2"
        const val SETTINGS = "hh3d_app_settings_v2"
        const val GOOGLE_USER = "hh3d_google_user_v2"
        const val CLOUD_BACKUP = "hh3d_google_cloud_snapshot_v2"
        const val AUDIO_DEFAULT_TM_MIGRATED = "hh3d_audio_default_tm_v1"
        const val SOURCES_V4_TWO_ONLY = "hh3d_sources_v4_yanhh3d_nguonc_only"
        const val SOURCES_V5_YAN_NOW_NO_NGUONC = "hh3d_sources_v5_yan_now_no_nguonc"
        const val APPEARANCE_DEFAULTS_V45 = "hh3d_appearance_defaults_v45"
        const val SEARCH_HISTORY = "hh3d_search_history_v1"
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    internal fun initForTest(mockPrefs: SharedPreferences) {
        prefs = mockPrefs
    }

    // =====================================================
    // Watch History
    // =====================================================
    fun getHistory(): Map<String, WatchProgress> {
        val raw = prefs.getString(Keys.HISTORY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, WatchProgress>()
            for (k in obj.keys()) {
                val p = obj.optJSONObject(k) ?: continue
                map[k] = WatchProgress(
                    donghuaId = p.optString("donghuaId", k),
                    title = p.optString("title", ""),
                    poster = p.optString("poster", ""),
                    episode = p.optInt("episode", 1),
                    currentTime = p.optDouble("currentTime", 0.0),
                    duration = p.optDouble("duration", 0.0),
                    progressPct = p.optInt("progressPct", 0),
                    server = p.optString("server", ""),
                    audio = p.optString("audio", "Thuyết Minh"),
                    updatedAt = p.optLong("updatedAt", 0L)
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveProgress(progress: WatchProgress) {
        val current = getHistory().toMutableMap()
        current[progress.donghuaId] = progress
        val root = JSONObject()
        for ((k, v) in current) {
            root.put(k, watchProgressToJson(v))
        }
        prefs.edit().putString(Keys.HISTORY, root.toString()).apply()
    }

    fun removeHistoryItem(donghuaId: String) {
        if (donghuaId.isBlank()) return
        val current = getHistory().toMutableMap()
        if (current.remove(donghuaId) == null) return
        val root = JSONObject()
        for ((k, v) in current) {
            root.put(k, watchProgressToJson(v))
        }
        prefs.edit().putString(Keys.HISTORY, root.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(Keys.HISTORY).apply()
    }

    // =====================================================
    // Search History
    // =====================================================
    private const val MAX_SEARCH_HISTORY = 10

    fun getSearchHistory(): List<String> {
        val raw = prefs.getString(Keys.SEARCH_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val q = arr.optString(i, "").trim()
                if (q.isNotEmpty()) list.add(q)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSearchHistory(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return getSearchHistory()
        val current = getSearchHistory().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val capped = current.take(MAX_SEARCH_HISTORY)
        val arr = JSONArray()
        for (item in capped) {
            arr.put(item)
        }
        prefs.edit().putString(Keys.SEARCH_HISTORY, arr.toString()).apply()
        return capped
    }

    fun removeSearchHistoryItem(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return getSearchHistory()
        val current = getSearchHistory().toMutableList()
        val removed = current.removeAll { it.equals(trimmed, ignoreCase = true) }
        if (!removed) return current
        val arr = JSONArray()
        for (item in current) {
            arr.put(item)
        }
        prefs.edit().putString(Keys.SEARCH_HISTORY, arr.toString()).apply()
        return current
    }

    fun clearSearchHistory() {
        prefs.edit().remove(Keys.SEARCH_HISTORY).apply()
    }

    // =====================================================
    // Watched Episodes & Per-Series Skip Intro
    // =====================================================
    fun getAllWatchedEpisodesMap(): Map<String, Set<Int>> {
        val raw = prefs.getString(Keys.WATCHED_EPISODES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, Set<Int>>()
            for (k in obj.keys()) {
                val arr = obj.optJSONArray(k) ?: continue
                val set = LinkedHashSet<Int>(arr.length())
                for (i in 0 until arr.length()) {
                    val ep = arr.optInt(i, -1)
                    if (ep > 0) set.add(ep)
                }
                if (set.isNotEmpty()) map[k] = set
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getWatchedEpisodes(donghuaId: String): Set<Int> {
        if (donghuaId.isBlank()) return emptySet()
        return getAllWatchedEpisodesMap()[donghuaId].orEmpty()
    }

    fun markEpisodeWatched(donghuaId: String, episodeNum: Int) {
        if (donghuaId.isBlank() || episodeNum <= 0) return
        val raw = prefs.getString(Keys.WATCHED_EPISODES, null)
        val obj = runCatching { if (raw != null) JSONObject(raw) else JSONObject() }.getOrElse { JSONObject() }
        val existingArr = obj.optJSONArray(donghuaId)
        val set = LinkedHashSet<Int>()
        if (existingArr != null) {
            for (i in 0 until existingArr.length()) {
                val ep = existingArr.optInt(i, -1)
                if (ep > 0) set.add(ep)
            }
        }
        if (!set.add(episodeNum)) return
        val nextArr = JSONArray()
        set.forEach { nextArr.put(it) }
        obj.put(donghuaId, nextArr)
        prefs.edit().putString(Keys.WATCHED_EPISODES, obj.toString()).apply()
    }

    fun getMovieSkipIntroSec(donghuaId: String): Int {
        if (donghuaId.isBlank()) return 85
        val raw = prefs.getString(Keys.SKIP_INTRO_MAP, null) ?: return 85
        return try {
            val sec = JSONObject(raw).optInt(donghuaId, 85)
            sec.coerceIn(15, 240)
        } catch (_: Exception) {
            85
        }
    }

    fun setMovieSkipIntroSec(donghuaId: String, sec: Int) {
        if (donghuaId.isBlank()) return
        val clamped = sec.coerceIn(15, 240)
        val raw = prefs.getString(Keys.SKIP_INTRO_MAP, null)
        val obj = runCatching { if (raw != null) JSONObject(raw) else JSONObject() }.getOrElse { JSONObject() }
        obj.put(donghuaId, clamped)
        prefs.edit().putString(Keys.SKIP_INTRO_MAP, obj.toString()).apply()
    }

    // =====================================================
    // Favorites
    // =====================================================
    fun getFavorites(): List<String> {
        val raw = prefs.getString(Keys.FAVORITES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toggleFavorite(id: String): Boolean {
        val favs = getFavorites().toMutableList()
        val idx = favs.indexOf(id)
        val isFav = if (idx >= 0) {
            favs.removeAt(idx)
            false
        } else {
            favs.add(0, id)
            true
        }
        val arr = JSONArray()
        favs.forEach { arr.put(it) }
        prefs.edit().putString(Keys.FAVORITES, arr.toString()).apply()
        return isFav
    }

    // =====================================================
    // Favorite New-Episode Meta
    // =====================================================
    fun getFavoriteMetaMap(): Map<String, FavoriteEpisodeMeta> {
        val raw = prefs.getString(Keys.FAVORITES_EP_META, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, FavoriteEpisodeMeta>()
            for (k in obj.keys()) {
                val m = obj.optJSONObject(k) ?: continue
                map[k] = FavoriteEpisodeMeta(
                    donghuaId = m.optString("donghuaId", k),
                    lastSeenEp = m.optInt("lastSeenEp", 1),
                    latestKnownEp = m.optInt("latestKnownEp", 1),
                    newEpCount = m.optInt("newEpCount", 0),
                    lastCheckedAt = m.optLong("lastCheckedAt", 0L)
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveFavoriteMetaMap(map: Map<String, FavoriteEpisodeMeta>) {
        val root = JSONObject()
        for ((k, v) in map) {
            root.put(
                k,
                JSONObject().apply {
                    put("donghuaId", v.donghuaId)
                    put("lastSeenEp", v.lastSeenEp)
                    put("latestKnownEp", v.latestKnownEp)
                    put("newEpCount", v.newEpCount)
                    put("lastCheckedAt", v.lastCheckedAt)
                }
            )
        }
        prefs.edit().putString(Keys.FAVORITES_EP_META, root.toString()).apply()
    }

    fun markFavoriteEpisodeSeen(donghuaId: String, latestEp: Int): Map<String, FavoriteEpisodeMeta> {
        val map = getFavoriteMetaMap().toMutableMap()
        val safeEp = latestEp.coerceAtLeast(1)
        val prevKnown = map[donghuaId]?.latestKnownEp ?: 1
        map[donghuaId] = FavoriteEpisodeMeta(
            donghuaId = donghuaId,
            lastSeenEp = safeEp,
            latestKnownEp = maxOf(safeEp, prevKnown),
            newEpCount = 0,
            lastCheckedAt = System.currentTimeMillis()
        )
        saveFavoriteMetaMap(map)
        return map
    }

    // =====================================================
    // Offline HLS Episodes Index
    // =====================================================
    fun getOfflineEpisodes(): Map<String, OfflineEpisodeRecord> {
        val raw = prefs.getString(Keys.OFFLINE_EPISODES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, OfflineEpisodeRecord>()
            for (k in obj.keys()) {
                val o = obj.optJSONObject(k) ?: continue
                val urlsArr = o.optJSONArray("cachedUrls")
                val urls = if (urlsArr != null) {
                    List(urlsArr.length()) { i -> urlsArr.optString(i) }
                } else emptyList()
                map[k] = OfflineEpisodeRecord(
                    key = o.optString("key", k),
                    donghuaId = o.optString("donghuaId", ""),
                    title = o.optString("title", ""),
                    poster = o.optString("poster", ""),
                    episodeNum = o.optInt("episodeNum", 1),
                    episodeName = o.optString("episodeName", ""),
                    serverName = o.optString("serverName", ""),
                    streamUrl = o.optString("streamUrl", ""),
                    localM3u8Path = o.optString("localM3u8Path", ""),
                    cachedUrls = urls,
                    segmentCount = o.optInt("segmentCount", 0),
                    approxBytes = o.optLong("approxBytes", 0L),
                    savedAt = o.optLong("savedAt", 0L)
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveOfflineEpisode(record: OfflineEpisodeRecord) {
        val map = getOfflineEpisodes().toMutableMap()
        map[record.key] = record
        val root = JSONObject()
        for ((k, v) in map) {
            root.put(
                k,
                JSONObject().apply {
                    put("key", v.key)
                    put("donghuaId", v.donghuaId)
                    put("title", v.title)
                    put("poster", v.poster)
                    put("episodeNum", v.episodeNum)
                    put("episodeName", v.episodeName)
                    put("serverName", v.serverName)
                    put("streamUrl", v.streamUrl)
                    put("localM3u8Path", v.localM3u8Path)
                    put("cachedUrls", JSONArray(v.cachedUrls))
                    put("segmentCount", v.segmentCount)
                    put("approxBytes", v.approxBytes)
                    put("savedAt", v.savedAt)
                }
            )
        }
        prefs.edit().putString(Keys.OFFLINE_EPISODES, root.toString()).apply()
    }

    fun deleteOfflineEpisode(key: String) {
        val map = getOfflineEpisodes().toMutableMap()
        map.remove(key)
        val root = JSONObject()
        for ((k, v) in map) {
            root.put(
                k,
                JSONObject().apply {
                    put("key", v.key)
                    put("donghuaId", v.donghuaId)
                    put("title", v.title)
                    put("poster", v.poster)
                    put("episodeNum", v.episodeNum)
                    put("episodeName", v.episodeName)
                    put("serverName", v.serverName)
                    put("streamUrl", v.streamUrl)
                    put("localM3u8Path", v.localM3u8Path)
                    put("cachedUrls", JSONArray(v.cachedUrls))
                    put("segmentCount", v.segmentCount)
                    put("approxBytes", v.approxBytes)
                    put("savedAt", v.savedAt)
                }
            )
        }
        prefs.edit().putString(Keys.OFFLINE_EPISODES, root.toString()).apply()
    }

    // =====================================================
    // Sources
    // =====================================================
    fun getSources(): List<DonghuaSource> {
        val raw = prefs.getString(Keys.SOURCES, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                if (arr.length() > 0) {
                    val list = List(arr.length()) { i -> sourceFromJson(arr.getJSONObject(i)) }
                    if (!prefs.contains(Keys.SOURCES_V5_YAN_NOW_NO_NGUONC)) {
                        val customOnly = list.filter { it.isCustom }
                        val migrated = InitialData.DEFAULT_BUILT_IN_SOURCES + customOnly
                        saveSources(migrated)
                        prefs.edit().putString(Keys.SOURCES_V5_YAN_NOW_NO_NGUONC, "1").apply()
                        return migrated
                    }
                    return list
                }
            } catch (_: Exception) {
            }
        }
        prefs.edit().putString(Keys.SOURCES_V5_YAN_NOW_NO_NGUONC, "1").apply()
        saveSources(InitialData.DEFAULT_BUILT_IN_SOURCES)
        return InitialData.DEFAULT_BUILT_IN_SOURCES
    }

    fun saveSources(sources: List<DonghuaSource>) {
        val arr = JSONArray()
        sources.forEach { arr.put(sourceToJson(it)) }
        prefs.edit().putString(Keys.SOURCES, arr.toString()).apply()
    }

    fun addCustomSource(source: DonghuaSource) {
        val list = getSources().toMutableList()
        list.add(source)
        saveSources(list)
    }

    data class ToggleSourceResult(
        val ok: Boolean,
        val sources: List<DonghuaSource>,
        val reason: String? = null
    )

    fun toggleSourceEnabled(id: String): ToggleSourceResult {
        val sources = getSources()
        val target = sources.firstOrNull { it.id == id }
            ?: return ToggleSourceResult(false, sources, "Không tìm thấy nguồn")
        val enabledCount = sources.count { it.enabled }
        if (target.enabled && enabledCount <= 1) {
            return ToggleSourceResult(false, sources, "Phải giữ ít nhất 1 nguồn phim hoạt động!")
        }
        val updated = sources.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        saveSources(updated)
        return ToggleSourceResult(true, updated)
    }

    fun deleteSource(id: String) {
        val updated = getSources().filterNot { it.id == id }
        saveSources(updated)
    }

    // =====================================================
    // Settings
    // =====================================================
    fun getSettings(): AppSettings {
        val raw = prefs.getString(Keys.SETTINGS, null)
        if (raw == null) {
            prefs.edit()
                .putString(Keys.AUDIO_DEFAULT_TM_MIGRATED, "1")
                .putString(Keys.APPEARANCE_DEFAULTS_V45, "1")
                .apply()
            return AppSettings()
        }
        return try {
            val obj = JSONObject(raw)
            val theme = ThemePreset.fromWire(obj.optString("theme", "pearl")).wireValue
            var defaultAudio = obj.optString("defaultAudio", "Thuyết Minh")
            if (defaultAudio != "Vietsub" && defaultAudio != "Thuyết Minh") {
                defaultAudio = "Thuyết Minh"
            }
            var preferredSource = obj.optString("preferredSource", "YanHH3DNow")
            val validSourceIds = getSources().map { it.id }.toSet()
            if (preferredSource.isBlank() ||
                preferredSource == "NguonC" ||
                !validSourceIds.contains(preferredSource)
            ) {
                preferredSource = "YanHH3DNow"
            }

            if (!prefs.contains(Keys.AUDIO_DEFAULT_TM_MIGRATED)) {
                defaultAudio = "Thuyết Minh"
                prefs.edit().putString(Keys.AUDIO_DEFAULT_TM_MIGRATED, "1").apply()
            }

            val extObj = obj.optJSONObject("extensions")
            val extensions = ExtensionSettings(
                adblock = extObj?.optBoolean("adblock", true) ?: true,
                aiUpscale = extObj?.optBoolean("aiUpscale", false) ?: false,
                bilingualSub = extObj?.optBoolean("bilingualSub", false) ?: false
            )
            val darkModeRaw = obj.optString("darkMode", "")
            val darkMode = when (darkModeRaw) {
                "light", "dark" -> darkModeRaw
                else -> if (theme == "dark") "dark" else "light"
            }

            val needsV45Migration = !prefs.contains(Keys.APPEARANCE_DEFAULTS_V45)
            val loaded = AppSettings(
                deviceMode = obj.optString("deviceMode", "auto"),
                theme = theme,
                darkMode = darkMode,
                glassBlur = if (needsV45Migration) 32 else obj.optInt("glassBlur", 32).coerceIn(8, 40),
                glassOpacity = if (needsV45Migration) 52 else obj.optInt("glassOpacity", 52).coerceIn(10, 90),
                ambientOrbs = if (needsV45Migration) true else obj.optBoolean("ambientOrbs", true),
                preferredSource = preferredSource,
                cloudAutoSync = obj.optBoolean("cloudAutoSync", true),
                autoNext = obj.optBoolean("autoNext", true),
                autoSkipIntro = obj.optBoolean("autoSkipIntro", false),
                seekStep = obj.optInt("seekStep", 10),
                bilingualSub = obj.optBoolean("bilingualSub", false),
                defaultAudio = defaultAudio,
                defaultQuality = obj.optString("defaultQuality", "4K 60FPS"),
                aspectRatio = obj.optString("aspectRatio", "16:9"),
                volume = obj.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f),
                playbackSpeed = obj.optDouble("playbackSpeed", 1.0).toFloat().coerceIn(0.25f, 2f),
                extensions = extensions,
                subtitleScale = obj.optDouble("subtitleScale", 0.055).toFloat().coerceIn(0.035f, 0.095f),
                reducedMotion = obj.optBoolean("reducedMotion", false),
                nightModeDrc = obj.optBoolean("nightModeDrc", false),
                autoClearCacheOnExit = obj.optBoolean("autoClearCacheOnExit", false),
                autoUpdateApp = obj.optBoolean("autoUpdateApp", true),
                updateManifestUrl = AppUpdateChecker.resolveEffectiveManifestUrl(obj.optString("updateManifestUrl", "")),
                showHomeHero = if (needsV45Migration) true else obj.optBoolean("showHomeHero", true),
                showHomeContinue = if (needsV45Migration) true else obj.optBoolean("showHomeContinue", true),
                showHomeTrending = if (needsV45Migration) true else obj.optBoolean("showHomeTrending", true)
            )
            if (needsV45Migration) {
                prefs.edit()
                    .putString(Keys.SETTINGS, settingsToJson(loaded).toString())
                    .putString(Keys.APPEARANCE_DEFAULTS_V45, "1")
                    .apply()
            }
            loaded
        } catch (_: Exception) {
            AppSettings()
        }
    }

    fun saveSettings(updated: AppSettings): AppSettings {
        prefs.edit()
            .putString(Keys.SETTINGS, settingsToJson(updated).toString())
            .putString(Keys.APPEARANCE_DEFAULTS_V45, "1")
            .apply()
        return updated
    }

    fun resetSettingsToDefaults(): AppSettings {
        val defaults = AppSettings()
        return saveSettings(defaults)
    }

    // =====================================================
    // Google User
    // =====================================================
    fun getUser(): UserProfile? {
        val raw = prefs.getString(Keys.GOOGLE_USER, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            UserProfile(
                name = obj.optString("name", "Đạo Hữu Tu Tiên"),
                email = obj.optString("email", ""),
                avatar = obj.optString("avatar", "Đ"),
                lastSync = if (obj.isNull("lastSync")) null else obj.optLong("lastSync")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveUser(user: UserProfile?) {
        if (user == null) {
            prefs.edit().remove(Keys.GOOGLE_USER).apply()
        } else {
            val obj = JSONObject().apply {
                put("name", user.name)
                put("email", user.email)
                put("avatar", user.avatar)
                put("lastSync", user.lastSync)
            }
            prefs.edit().putString(Keys.GOOGLE_USER, obj.toString()).apply()
        }
    }

    // =====================================================
    // Custom Movies
    // =====================================================
    fun getCustomMovies(): List<DonghuaMovie> {
        val raw = prefs.getString(Keys.CUSTOM_MOVIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> movieFromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCustomMovie(movie: DonghuaMovie) {
        addCustomMovies(listOf(movie))
    }

    fun addCustomMovies(movies: List<DonghuaMovie>) {
        if (movies.isEmpty()) return
        val existing = getCustomMovies()
        val map = LinkedHashMap<String, DonghuaMovie>()
        movies.forEach { map[it.slug ?: it.id] = it }
        existing.forEach { m ->
            val key = m.slug ?: m.id
            if (!map.containsKey(key)) map[key] = m
        }
        val arr = JSONArray()
        map.values.take(120).forEach { arr.put(movieToJson(it)) }
        prefs.edit().putString(Keys.CUSTOM_MOVIES, arr.toString()).apply()
    }

    fun getAllMovies(): List<DonghuaMovie> = getCustomMovies() + InitialData.INITIAL_DONGHUA_CATALOG

    // =====================================================
    // Backup / Restore & Cloud Snapshot
    // =====================================================
    fun exportFullBackup(): String {
        val historyObj = JSONObject()
        getHistory().forEach { (k, v) -> historyObj.put(k, watchProgressToJson(v)) }

        val favArr = JSONArray(getFavorites())
        val srcArr = JSONArray().apply { getSources().forEach { put(sourceToJson(it)) } }
        val customArr = JSONArray().apply { getCustomMovies().forEach { put(movieToJson(it)) } }
        val watchedRaw = prefs.getString(Keys.WATCHED_EPISODES, null)
        val skipIntroRaw = prefs.getString(Keys.SKIP_INTRO_MAP, null)

        val payload = JSONObject().apply {
            put("version", "3.5.0-universal")
            put("timestamp", System.currentTimeMillis())
            put("history", historyObj)
            put("favorites", favArr)
            put("sources", srcArr)
            put("customMovies", customArr)
            put("settings", settingsToJson(getSettings()))
            if (!watchedRaw.isNullOrBlank()) {
                runCatching { JSONObject(watchedRaw) }.getOrNull()?.let { put("watchedEpisodes", it) }
            }
            if (!skipIntroRaw.isNullOrBlank()) {
                runCatching { JSONObject(skipIntroRaw) }.getOrNull()?.let { put("skipIntroSec", it) }
            }
            getUser()?.let { u ->
                put("user", JSONObject().apply {
                    put("name", u.name)
                    put("email", u.email)
                    put("avatar", u.avatar)
                    put("lastSync", u.lastSync)
                })
            }
        }
        return payload.toString(2)
    }

    fun importFullBackup(jsonString: String): Boolean {
        return try {
            val payload = JSONObject(jsonString)
            val ed = prefs.edit()
            payload.optJSONObject("history")?.let { ed.putString(Keys.HISTORY, it.toString()) }
            payload.optJSONArray("favorites")?.let { ed.putString(Keys.FAVORITES, it.toString()) }
            val srcs = payload.optJSONArray("sources") ?: payload.optJSONArray("sourcesList")
            srcs?.let { ed.putString(Keys.SOURCES, it.toString()) }
            payload.optJSONArray("customMovies")?.let { ed.putString(Keys.CUSTOM_MOVIES, it.toString()) }
            payload.optJSONObject("settings")?.let {
                ed.putString(Keys.SETTINGS, it.toString())
                ed.putString(Keys.APPEARANCE_DEFAULTS_V45, "1")
            }
            payload.optJSONObject("user")?.let { ed.putString(Keys.GOOGLE_USER, it.toString()) }
            payload.optJSONObject("watchedEpisodes")?.let { ed.putString(Keys.WATCHED_EPISODES, it.toString()) }
            payload.optJSONObject("skipIntroSec")?.let { ed.putString(Keys.SKIP_INTRO_MAP, it.toString()) }
            ed.apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun exportCompactSyncCode(): String {
        val rawBytes = exportFullBackup().toByteArray(Charsets.UTF_8)
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(rawBytes) }
        val compressed = baos.toByteArray()
        return "HH3D1:" + encodeUrlBase64(compressed)
    }

    fun importCompactSyncCode(code: String): Boolean {
        val clean = code.trim()
        if (!clean.startsWith("HH3D1:")) return importFullBackup(clean)
        return try {
            val b64 = clean.removePrefix("HH3D1:").trim()
            val compressed = decodeUrlBase64(b64) ?: return false
            val json = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(compressed))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            importFullBackup(json)
        } catch (_: Exception) {
            false
        }
    }

    private fun encodeUrlBase64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val hasB1 = i + 1 < bytes.size
            val hasB2 = i + 2 < bytes.size
            val b1 = if (hasB1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(alphabet[(triple ushr 18) and 0x3F])
            sb.append(alphabet[(triple ushr 12) and 0x3F])
            if (hasB1) sb.append(alphabet[(triple ushr 6) and 0x3F])
            if (hasB2) sb.append(alphabet[triple and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    private fun decodeUrlBase64(str: String): ByteArray? {
        if (str.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream((str.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (ch in str) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '-', '+' -> 62
                '_', '/' -> 63
                '=' -> break
                else -> if (ch.isWhitespace()) continue else return null
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun saveCloudSnapshot(): Long {
        val ts = System.currentTimeMillis()
        val json = exportFullBackup()
        prefs.edit().putString(Keys.CLOUD_BACKUP, json).apply()
        getUser()?.let { saveUser(it.copy(lastSync = ts)) }
        return ts
    }

    fun getCloudSnapshotTimestamp(): Long? {
        val raw = prefs.getString(Keys.CLOUD_BACKUP, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val ts = obj.optLong("timestamp", 0L)
            if (ts > 0L) ts else null
        } catch (_: Exception) {
            null
        }
    }

    fun restoreCloudSnapshot(): Boolean {
        val raw = prefs.getString(Keys.CLOUD_BACKUP, null) ?: return false
        return importFullBackup(raw)
    }

    data class CloudSnapshotStats(
        val timestamp: Long,
        val historyCount: Int,
        val favoritesCount: Int,
        val customSourcesCount: Int
    )

    fun getCloudSnapshotStats(): CloudSnapshotStats? {
        val raw = prefs.getString(Keys.CLOUD_BACKUP, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val ts = obj.optLong("timestamp", 0L)
            val histCount = obj.optJSONObject("history")?.length() ?: 0
            val favCount = obj.optJSONArray("favorites")?.length() ?: 0
            val srcs = obj.optJSONArray("sources") ?: obj.optJSONArray("sourcesList")
            var customSrc = 0
            if (srcs != null) {
                for (i in 0 until srcs.length()) {
                    if (srcs.optJSONObject(i)?.optBoolean("isCustom", false) == true) {
                        customSrc++
                    }
                }
            }
            CloudSnapshotStats(ts, histCount, favCount, customSrc)
        } catch (_: Exception) {
            null
        }
    }

    // JSON helpers
    private fun watchProgressToJson(v: WatchProgress) = JSONObject().apply {
        put("donghuaId", v.donghuaId)
        put("title", v.title)
        put("poster", v.poster)
        put("episode", v.episode)
        put("currentTime", v.currentTime)
        put("duration", v.duration)
        put("progressPct", v.progressPct)
        put("server", v.server)
        put("audio", v.audio)
        put("updatedAt", v.updatedAt)
    }

    private fun sourceToJson(s: DonghuaSource) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("url", s.url)
        put("hostname", s.hostname)
        put("type", s.type)
        put("ping", s.ping)
        put("quality", s.quality)
        put("desc", s.desc)
        put("enabled", s.enabled)
        put("isCustom", s.isCustom)
        put("addedAt", s.addedAt)
    }

    private fun sourceFromJson(o: JSONObject) = DonghuaSource(
        id = o.optString("id"),
        name = o.optString("name"),
        url = o.optString("url"),
        hostname = if (o.isNull("hostname")) null else o.optString("hostname"),
        type = o.optString("type", "Smart Scraper"),
        ping = o.optString("ping", "15ms"),
        quality = o.optString("quality", "1080p"),
        desc = o.optString("desc", ""),
        enabled = o.optBoolean("enabled", true),
        isCustom = o.optBoolean("isCustom", false),
        addedAt = if (o.isNull("addedAt")) null else o.optLong("addedAt")
    )

    private fun settingsToJson(s: AppSettings) = JSONObject().apply {
        put("deviceMode", s.deviceMode)
        put("theme", s.theme)
        put("darkMode", s.darkMode)
        put("glassBlur", s.glassBlur)
        put("glassOpacity", s.glassOpacity)
        put("ambientOrbs", s.ambientOrbs)
        put("preferredSource", s.preferredSource)
        put("cloudAutoSync", s.cloudAutoSync)
        put("autoNext", s.autoNext)
        put("autoSkipIntro", s.autoSkipIntro)
        put("seekStep", s.seekStep)
        put("bilingualSub", s.bilingualSub)
        put("defaultAudio", s.defaultAudio)
        put("defaultQuality", s.defaultQuality)
        put("aspectRatio", s.aspectRatio)
        put("volume", s.volume.toDouble())
        put("playbackSpeed", s.playbackSpeed.toDouble())
        put("subtitleScale", s.subtitleScale.toDouble())
        put("reducedMotion", s.reducedMotion)
        put("nightModeDrc", s.nightModeDrc)
        put("autoClearCacheOnExit", s.autoClearCacheOnExit)
        put("autoUpdateApp", s.autoUpdateApp)
        put("updateManifestUrl", s.updateManifestUrl)
        put("showHomeHero", s.showHomeHero)
        put("showHomeContinue", s.showHomeContinue)
        put("showHomeTrending", s.showHomeTrending)
        put("extensions", JSONObject().apply {
            put("adblock", s.extensions.adblock)
            put("aiUpscale", s.extensions.aiUpscale)
            put("bilingualSub", s.extensions.bilingualSub)
        })
    }

    private fun movieToJson(m: DonghuaMovie) = JSONObject().apply {
        put("id", m.id)
        put("title", m.title)
        put("originalTitle", m.originalTitle)
        put("slug", m.slug)
        put("studio", m.studio)
        put("latestEp", m.latestEp)
        put("totalEp", m.totalEp)
        put("rating", m.rating)
        put("views", m.views)
        put("viewsNum", m.viewsNum)
        put("quality", m.quality)
        put("audioModes", JSONArray(m.audioModes))
        put("sources", JSONArray(m.sources))
        put("genres", JSONArray(m.genres))
        put("scheduleDay", m.scheduleDay)
        put("scheduleTime", m.scheduleTime)
        put("poster", m.poster)
        put("thumb", m.thumb)
        put("streamIndex", m.streamIndex)
        put("description", m.description)
        put("customWebUrl", m.customWebUrl)
    }

    private fun movieFromJson(o: JSONObject): DonghuaMovie {
        fun jsonStrings(key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return List(arr.length()) { i -> arr.optString(i) }
        }
        return DonghuaMovie(
            id = o.optString("id"),
            title = o.optString("title"),
            originalTitle = o.optString("originalTitle"),
            slug = if (o.isNull("slug")) null else o.optString("slug"),
            studio = o.optString("studio", "Custom Web"),
            latestEp = o.optInt("latestEp", 1),
            totalEp = o.optInt("totalEp", 24),
            rating = o.optDouble("rating", 9.6),
            views = o.optString("views", "1.0M"),
            viewsNum = o.optDouble("viewsNum", 1.0),
            quality = o.optString("quality", "1080p"),
            audioModes = jsonStrings("audioModes").ifEmpty { listOf("Vietsub", "Thuyết Minh") },
            sources = jsonStrings("sources"),
            genres = jsonStrings("genres").ifEmpty { listOf("Tu Tiên") },
            scheduleDay = o.optString("scheduleDay", "Chủ Nhật"),
            scheduleTime = o.optString("scheduleTime", "10:00"),
            poster = o.optString("poster", ""),
            thumb = if (o.isNull("thumb")) null else o.optString("thumb"),
            streamIndex = o.optInt("streamIndex", 0),
            description = if (o.isNull("description")) null else o.optString("description"),
            customWebUrl = if (o.isNull("customWebUrl")) null else o.optString("customWebUrl")
        )
    }
}
