package com.hoathinh3d.donghua

import android.content.SharedPreferences
import com.hoathinh3d.donghua.core.LanSyncManager
import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.data.model.AppSettings
import com.hoathinh3d.donghua.data.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrefsStoreTest {

    private class InMemorySharedPrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                map.putAll(pending)
            }
        }
    }

    @Before
    fun setUp() {
        PrefsStore.initForTest(InMemorySharedPrefs())
    }

    @Test
    fun defaultSourcesAndSettings_matchWebDefaults() {
        val sources = PrefsStore.getSources()
        assertEquals(3, sources.size)
        assertEquals("YanHH3DNow", sources[0].id)
        assertEquals("YanHH3D", sources[1].id)
        assertEquals("VSMov", sources[2].id)

        val settings = PrefsStore.getSettings()
        assertEquals("YanHH3DNow", settings.preferredSource)
        assertEquals("Thuyết Minh", settings.defaultAudio)
        assertEquals("Auto", settings.defaultQuality)
        assertEquals("pearl", settings.theme)
        assertEquals("light", settings.darkMode)
        assertEquals(52, settings.glassOpacity)
        assertEquals(32, settings.glassBlur)
        assertTrue(settings.ambientOrbs)
        assertEquals(10, settings.seekStep)
        assertEquals(listOf("pearl", "dark"), com.hoathinh3d.donghua.data.model.ThemePreset.entries.map { it.wireValue })
        assertEquals("pearl", com.hoathinh3d.donghua.data.model.ThemePreset.fromWire("sakura").wireValue)
        assertEquals("pearl", com.hoathinh3d.donghua.data.model.ThemePreset.fromWire("aqua").wireValue)
        assertEquals(listOf("light", "dark"), com.hoathinh3d.donghua.data.model.DarkModePreference.entries.map { it.wireValue })
        assertEquals("light", com.hoathinh3d.donghua.data.model.DarkModePreference.fromWire("system").wireValue)

        val pearlLight = com.hoathinh3d.donghua.core.ThemeManager.palette("pearl", 32, 52, "light")
        assertFalse(pearlLight.isDark)
        assertEquals(android.graphics.Color.parseColor("#F6F8FC"), pearlLight.bgPrimary)

        val sunsetDark = com.hoathinh3d.donghua.core.ThemeManager.palette("dark", 32, 52, "dark")
        assertTrue(sunsetDark.isDark)
        assertEquals(android.graphics.Color.parseColor("#040507"), sunsetDark.bgPrimary)
        assertEquals(android.graphics.Color.parseColor("#E2AE58"), sunsetDark.accentFrom)
    }

    @Test
    fun exportAndImportBackup_roundTripsHistoryFavoritesAndSettings() {
        PrefsStore.saveProgress(
            WatchProgress(
                donghuaId = "dptk",
                title = "Đấu Phá Thương Khung",
                poster = "https://img.test/dptk.jpg",
                episode = 12,
                currentTime = 320.5,
                duration = 1200.0,
                progressPct = 27,
                server = "NguonC • Lồng Tiếng",
                audio = "Thuyết Minh",
                updatedAt = 1700000000000L
            )
        )
        assertTrue(PrefsStore.toggleFavorite("dptk"))
        PrefsStore.saveSettings(
            AppSettings(
                theme = "dark",
                darkMode = "dark",
                glassOpacity = 75,
                seekStep = 15,
                subtitleBottomPct = 0.14f,
                subtitleBgAlpha = 0,
                subtitleTextColor = android.graphics.Color.YELLOW
            )
        )

        val exported = PrefsStore.exportFullBackup()
        assertTrue(exported.contains("3.5.0-universal"))

        // Reset store and re-import
        PrefsStore.initForTest(InMemorySharedPrefs())
        assertTrue(PrefsStore.getHistory().isEmpty())
        assertFalse(PrefsStore.getFavorites().contains("dptk"))

        assertTrue(PrefsStore.importFullBackup(exported))
        assertEquals(12, PrefsStore.getHistory()["dptk"]?.episode)
        assertTrue(PrefsStore.getFavorites().contains("dptk"))
        assertEquals("dark", PrefsStore.getSettings().theme)
        assertEquals("dark", PrefsStore.getSettings().darkMode)
        assertEquals(75, PrefsStore.getSettings().glassOpacity)
        assertEquals(15, PrefsStore.getSettings().seekStep)
        assertEquals(0.14f, PrefsStore.getSettings().subtitleBottomPct, 0.001f)
        assertEquals(0, PrefsStore.getSettings().subtitleBgAlpha)
        assertEquals(android.graphics.Color.YELLOW, PrefsStore.getSettings().subtitleTextColor)
    }

    @Test
    fun cloudSnapshotStats_and_resetSettingsToDefaults_workCorrectly() {
        PrefsStore.toggleFavorite("dptk")
        PrefsStore.saveSettings(AppSettings(theme = "dark", darkMode = "system", glassOpacity = 80, seekStep = 30, glassBlur = 36))
        PrefsStore.saveCloudSnapshot()

        val stats = PrefsStore.getCloudSnapshotStats()
        assertTrue(stats != null)
        assertEquals(1, stats?.favoritesCount)
        assertEquals(0, stats?.historyCount)
        assertEquals(0, stats?.customSourcesCount)

        val reset = PrefsStore.resetSettingsToDefaults()
        assertEquals("pearl", reset.theme)
        assertEquals("light", reset.darkMode)
        assertEquals(52, reset.glassOpacity)
        assertEquals(32, reset.glassBlur)
        assertTrue(reset.ambientOrbs)
        assertEquals(10, reset.seekStep)
        // Favorites preserved after settings reset
        assertTrue(PrefsStore.getFavorites().contains("dptk"))
    }

    @Test
    fun watchedEpisodes_skipIntroSec_and_compactSyncCode_roundTripProperly() {
        PrefsStore.markEpisodeWatched("tiennghich", 1)
        PrefsStore.markEpisodeWatched("tiennghich", 2)
        PrefsStore.markEpisodeWatched("tiennghich", 1) // duplicate ignored
        PrefsStore.setMovieSkipIntroSec("tiennghich", 105)
        PrefsStore.toggleFavorite("tiennghich")

        assertEquals(setOf(1, 2), PrefsStore.getWatchedEpisodes("tiennghich"))
        assertEquals(105, PrefsStore.getMovieSkipIntroSec("tiennghich"))
        assertEquals(85, PrefsStore.getMovieSkipIntroSec("other_movie"))

        val syncCode = PrefsStore.exportCompactSyncCode()
        assertTrue(syncCode.startsWith("HH3D1:"))

        PrefsStore.initForTest(InMemorySharedPrefs())
        assertTrue(PrefsStore.getWatchedEpisodes("tiennghich").isEmpty())

        assertTrue(PrefsStore.importCompactSyncCode(syncCode))
        assertEquals(setOf(1, 2), PrefsStore.getWatchedEpisodes("tiennghich"))
        assertEquals(105, PrefsStore.getMovieSkipIntroSec("tiennghich"))
        assertTrue(PrefsStore.getFavorites().contains("tiennghich"))

        assertEquals(
            "http://192.168.1.25:8765/sync?pin=4821",
            LanSyncManager.normalizeLanSyncUrl("192.168.1.25", "4821")
        )
        assertEquals(
            "http://192.168.1.25:8766/sync?pin=9999",
            LanSyncManager.normalizeLanSyncUrl("http://192.168.1.25:8766/sync?pin=9999", "")
        )
    }

    @Test
    fun reducedMotionAndNightDrc_persistAndReset_and_lanDiscoveryPacketParses() {
        val updated = PrefsStore.saveSettings(AppSettings(reducedMotion = true, nightModeDrc = true))
        assertTrue(updated.reducedMotion)
        assertTrue(updated.nightModeDrc)

        val reloaded = PrefsStore.getSettings()
        assertTrue(reloaded.reducedMotion)
        assertTrue(reloaded.nightModeDrc)

        val reset = PrefsStore.resetSettingsToDefaults()
        assertFalse(reset.reducedMotion)
        assertFalse(reset.nightModeDrc)

        val validPeer = LanSyncManager.parseDiscoveryPacket(
            """{"app":"HH3D","device":"Pixel 8","ip":"192.168.1.50","port":8765}"""
        )
        assertEquals("Pixel 8", validPeer?.deviceName)
        assertEquals("192.168.1.50", validPeer?.ip)
        assertEquals(8765, validPeer?.port)

        assertEquals(null, LanSyncManager.parseDiscoveryPacket("""{"app":"OTHER","ip":"1.2.3.4","port":8765}"""))
        assertEquals(null, LanSyncManager.parseDiscoveryPacket("""{"app":"HH3D","ip":"","port":8765}"""))
        assertEquals(null, LanSyncManager.parseDiscoveryPacket("not-json"))
    }

    @Test
    fun removeHistoryItem_deletesSingleEntryOnly_and_quarterSpeedPersists() {
        PrefsStore.saveProgress(
            WatchProgress("a", "Phim A", "", 1, 10.0, 100.0, 10, "Srv", "Thuyết Minh", 100L)
        )
        PrefsStore.saveProgress(
            WatchProgress("b", "Phim B", "", 2, 20.0, 100.0, 20, "Srv", "Vietsub", 200L)
        )
        PrefsStore.removeHistoryItem("a")
        val hist = PrefsStore.getHistory()
        assertFalse(hist.containsKey("a"))
        assertTrue(hist.containsKey("b"))

        val saved = PrefsStore.saveSettings(AppSettings(playbackSpeed = 0.25f))
        assertEquals(0.25f, saved.playbackSpeed, 0.001f)
        assertEquals(0.25f, PrefsStore.getSettings().playbackSpeed, 0.001f)
    }

    @Test
    fun autoClearCacheAndAutoUpdate_persistAndReset_andSubtabsRenumbered() {
        val saved = PrefsStore.saveSettings(
            AppSettings(
                autoClearCacheOnExit = true,
                autoUpdateApp = false,
                updateManifestUrl = "https://example.com/hh3d/version.json",
                showHomeHero = false,
                showHomeContinue = false,
                showHomeTrending = true
            )
        )
        assertTrue(saved.autoClearCacheOnExit)
        assertFalse(saved.autoUpdateApp)
        assertEquals("https://example.com/hh3d/version.json", saved.updateManifestUrl)
        assertFalse(saved.showHomeHero)
        assertFalse(saved.showHomeContinue)
        assertTrue(saved.showHomeTrending)

        val reloaded = PrefsStore.getSettings()
        assertTrue(reloaded.autoClearCacheOnExit)
        assertFalse(reloaded.autoUpdateApp)
        assertEquals("https://example.com/hh3d/version.json", reloaded.updateManifestUrl)
        assertFalse(reloaded.showHomeHero)
        assertFalse(reloaded.showHomeContinue)
        assertTrue(reloaded.showHomeTrending)

        val reset = PrefsStore.resetSettingsToDefaults()
        assertFalse(reset.autoClearCacheOnExit)
        assertTrue(reset.autoUpdateApp)
        assertEquals(com.hoathinh3d.donghua.core.AppUpdateChecker.DEFAULT_MANIFEST_URL, reset.updateManifestUrl)
        assertTrue(reset.showHomeHero)

        val blankMigrated = PrefsStore.saveSettings(AppSettings(updateManifestUrl = ""))
        assertEquals("", blankMigrated.updateManifestUrl)
        assertEquals(
            com.hoathinh3d.donghua.core.AppUpdateChecker.DEFAULT_MANIFEST_URL,
            PrefsStore.getSettings().updateManifestUrl
        )
        assertTrue(reset.showHomeContinue)
        assertTrue(reset.showHomeTrending)

        val tabs = com.hoathinh3d.donghua.data.model.SettingsSubtab.values()
        assertEquals(5, tabs.size)
        assertEquals("1. Trình phát tích hợp", tabs.first().label)
        assertEquals("5. Tiện ích mở rộng", tabs.last().label)
    }

    @Test
    fun searchHistory_addRemoveClear_andCapsAt10() {
        assertTrue(PrefsStore.getSearchHistory().isEmpty())

        // Add 1st item
        val list1 = PrefsStore.addSearchHistory("Đấu Phá Thương Khung")
        assertEquals(1, list1.size)
        assertEquals("Đấu Phá Thương Khung", list1[0])

        // Add 2nd item
        val list2 = PrefsStore.addSearchHistory("Tiên Nghịch")
        assertEquals(2, list2.size)
        assertEquals("Tiên Nghịch", list2[0])
        assertEquals("Đấu Phá Thương Khung", list2[1])

        // Add duplicate (case-insensitive) -> moves to top
        val list3 = PrefsStore.addSearchHistory("đấu phá thương khung")
        assertEquals(2, list3.size)
        assertEquals("đấu phá thương khung", list3[0])
        assertEquals("Tiên Nghịch", list3[1])

        // Fill past 10 items
        for (i in 1..12) {
            PrefsStore.addSearchHistory("Từ khóa $i")
        }
        val capped = PrefsStore.getSearchHistory()
        assertEquals(10, capped.size)
        assertEquals("Từ khóa 12", capped.first())

        // Remove single item
        val removed = PrefsStore.removeSearchHistoryItem("Từ khóa 12")
        assertEquals(9, removed.size)
        assertFalse(removed.contains("Từ khóa 12"))

        // Clear all
        PrefsStore.clearSearchHistory()
        assertTrue(PrefsStore.getSearchHistory().isEmpty())
    }

    @Test
    fun favoritesSnapshot_preservesAll10MoviesAcrossPagesAndBackup() {
        for (i in 1..10) {
            val movie = com.hoathinh3d.donghua.data.model.DonghuaMovie(
                id = "fav_movie_$i",
                title = "Phim Yêu Thích $i",
                originalTitle = "Orig $i",
                slug = "fav-movie-$i",
                studio = "YanHH3D Now",
                latestEp = 10 + i,
                totalEp = 50,
                rating = 9.7,
                views = "1M",
                viewsNum = 1.0,
                quality = "1080p",
                audioModes = listOf("Thuyết Minh"),
                sources = listOf("YanHH3DNow"),
                genres = listOf("Tu Tiên"),
                scheduleDay = "Thứ 2",
                scheduleTime = "10:00",
                poster = "https://img.test/m$i.jpg"
            )
            assertTrue(PrefsStore.toggleFavorite(movie.id, movie))
        }

        val favIds = PrefsStore.getFavorites()
        val favMap = PrefsStore.getFavoriteMoviesMap()
        assertEquals(10, favIds.size)
        assertEquals(10, favMap.size)
        assertEquals("Phim Yêu Thích 10", favMap["fav_movie_10"]?.title)

        val backupJson = PrefsStore.exportFullBackup()
        PrefsStore.initForTest(InMemorySharedPrefs())
        assertTrue(PrefsStore.getFavorites().isEmpty())
        assertTrue(PrefsStore.getFavoriteMoviesMap().isEmpty())

        assertTrue(PrefsStore.importFullBackup(backupJson))
        assertEquals(10, PrefsStore.getFavorites().size)
        assertEquals(10, PrefsStore.getFavoriteMoviesMap().size)
        assertEquals("https://img.test/m1.jpg", PrefsStore.getFavoriteMoviesMap()["fav_movie_1"]?.poster)

        // Removing 1 favorite removes both ID and snapshot cleanly
        assertFalse(PrefsStore.toggleFavorite("fav_movie_5"))
        assertEquals(9, PrefsStore.getFavorites().size)
        assertEquals(9, PrefsStore.getFavoriteMoviesMap().size)

        // Batch mark watched & clear
        PrefsStore.markEpisodesWatched("tiennghich", listOf(1, 2, 3, 4))
        assertEquals(setOf(1, 2, 3, 4), PrefsStore.getWatchedEpisodes("tiennghich"))
        PrefsStore.clearWatchedEpisodes("tiennghich")
        assertTrue(PrefsStore.getWatchedEpisodes("tiennghich").isEmpty())

        // Verify v58 migration enforces Auto + Thuyết Minh once on legacy prefs, then preserves user changes
        val legacyPrefs = InMemorySharedPrefs()
        legacyPrefs.edit().putString(
            PrefsStore.Keys.SETTINGS,
            """{"defaultQuality":"4K 60FPS","defaultAudio":"Vietsub"}"""
        ).apply()
        PrefsStore.initForTest(legacyPrefs)
        val migrated = PrefsStore.getSettings()
        assertEquals("Auto", migrated.defaultQuality)
        assertEquals("Thuyết Minh", migrated.defaultAudio)

        val userChanged = PrefsStore.saveSettings(migrated.copy(defaultAudio = "Vietsub", defaultQuality = "1080p Pro"))
        assertEquals("Vietsub", userChanged.defaultAudio)
        assertEquals("1080p Pro", PrefsStore.getSettings().defaultQuality)
    }
}
