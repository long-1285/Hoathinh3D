package com.hoathinh3d.donghua

import com.hoathinh3d.donghua.data.model.CatalogAudioFilter
import com.hoathinh3d.donghua.data.model.CatalogPageResult
import com.hoathinh3d.donghua.data.model.CatalogSortMode
import com.hoathinh3d.donghua.data.model.CatalogStatusFilter
import com.hoathinh3d.donghua.data.model.DonghuaMovie
import com.hoathinh3d.donghua.data.model.EpisodeItem
import com.hoathinh3d.donghua.data.model.ServerGroup
import com.hoathinh3d.donghua.data.offline.HlsOfflineManager
import com.hoathinh3d.donghua.data.source.CatalogQueryEngine
import com.hoathinh3d.donghua.data.source.MovieRepository
import com.hoathinh3d.donghua.data.source.NguonCApiClient
import com.hoathinh3d.donghua.player.EmbedFallbackView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NguonCApiAndRepoTest {

    @Test
    fun parseFirstInt_handlesSlashesWithoutConcatenatingDigits() {
        assertEquals(12, NguonCApiClient.parseFirstInt("12/12", 1))
        assertEquals(135, NguonCApiClient.parseFirstInt("Hoàn tất (135/156)", 1))
    }

    @Test
    fun mapNguonCItems_extractsFieldsProperly() {
        val arr = JSONArray().put(
            JSONObject().apply {
                put("id", "nc_1")
                put("slug", "tien-nghich")
                put("name", "Tiên Nghịch")
                put("original_name", "Xian Ni")
                put("language", "Thuyết Minh")
                put("current_episode", "Tập 50")
                put("total_episodes", "120")
                put("poster_url", "https://img.test/poster.jpg")
            }
        )
        val movies = NguonCApiClient.mapNguonCItems(arr, "NguonC")
        assertEquals(1, movies.size)
        assertEquals(50, movies[0].latestEp)
        assertEquals(120, movies[0].totalEp)
        assertTrue(movies[0].audioModes.first() == "Thuyết Minh")
    }

    @Test
    fun selectPreferredServerIdx_prioritizesThuyetMinhThenHls() {
        val groups = listOf(
            ServerGroup("YanHH3D • Thuyết minh 1080 V1", listOf(EpisodeItem("Tập 1", "tap-1", linkEmbed = "https://e.test/1"))),
            ServerGroup("Trình phát Tích hợp • Vietsub", listOf(EpisodeItem("Tập 1", "tap-1", linkM3u8 = "https://hls.test/2.m3u8"))),
            ServerGroup("NguonC • Lồng Tiếng", listOf(EpisodeItem("Tập 1", "tap-1", linkM3u8 = "https://hls.test/1.m3u8")))
        )
        // Within Thuyết Minh / Lồng Tiếng group, HLS dubbed beats Embed dubbed; Vietsub preference selects Vietsub HLS
        assertEquals(2, MovieRepository.selectPreferredServerIdx(groups, "Thuyết Minh"))
        assertEquals(1, MovieRepository.selectPreferredServerIdx(groups, "Vietsub"))

        val onlySubHls = listOf(
            ServerGroup("YanHH3D • Thuyết minh 1080 V1", listOf(EpisodeItem("Tập 1", "tap-1", linkEmbed = "https://e.test/1"))),
            ServerGroup("Trình phát Tích hợp • Vietsub", listOf(EpisodeItem("Tập 1", "tap-1", linkM3u8 = "https://hls.test/2.m3u8")))
        )
        // Requirement #3: Thuyết Minh default always chooses Thuyết Minh server when available
        assertEquals(0, MovieRepository.selectPreferredServerIdx(onlySubHls, "Thuyết Minh"))

        val groupedNowVsPhimApi = listOf(
            ServerGroup("Trình phát Tích hợp • Thuyết Minh", (1..107).map { EpisodeItem("Tập $it", "tap-$it", linkM3u8 = "https://hls.test/$it.m3u8") }),
            ServerGroup("YanHH3D Now • Thuyết Minh (4K)", (1..80).map { EpisodeItem("Tập $it", "tap-$it", wpServerSv = "hls") })
        )
        // Ưu tiên nguồn gốc YanHH3D Now (4K/nhiều gương CDN độc lập) trước bản tách lẻ PhimAPI
        assertEquals(1, MovieRepository.selectPreferredServerIdx(groupedNowVsPhimApi, "Thuyết Minh"))
    }

    @Test
    fun visibleServerIndices_onlyReturnsDirectHlsServers() {
        val mixed = listOf(
            ServerGroup("YanHH3D Now • Thuyết Minh (4K)", listOf(EpisodeItem("Tập 1", "tap-1", wpServerSv = "hls"))),
            ServerGroup("YanHH3D Now • Thuyết Minh (DLM)", listOf(EpisodeItem("Tập 1", "tap-1", wpServerSv = "embed", linkEmbed = "https://e.test/dlm"))),
            ServerGroup("YanHH3D • VIETSUB 1080 V1", listOf(EpisodeItem("Tập 1", "tap-1", linkM3u8 = "https://cdn.test/1.m3u8"))),
            ServerGroup("YanHH3D • Thuyết minh 1080 V1", listOf(EpisodeItem("Tập 1", "tap-1", linkEmbed = "https://streamfree.vip/embed/1")))
        )
        assertEquals(listOf(0, 2), MovieRepository.visibleServerIndices(mixed))
        assertEquals(listOf(0, 2), MovieRepository.visibleServerIndices(mixed, includeIdx = 3))

        val embedOnly = listOf(
            ServerGroup("YanHH3D • VIETSUB 1080 V1", listOf(EpisodeItem("Tập 1", "tap-1", linkEmbed = "https://streamfree.vip/embed/1"))),
            ServerGroup("YanHH3D • Thuyết minh 1080 V1", listOf(EpisodeItem("Tập 1", "tap-1", linkEmbed = "https://streamfree.vip/embed/2")))
        )
        assertEquals(emptyList<Int>(), MovieRepository.visibleServerIndices(embedOnly))
    }

    @Test
    fun embedFallbackView_wrapsIframeAndExtractsDirectM3u8() {
        val html = EmbedFallbackView.buildIframeWrapperHtml("https://streamfree.vip/embed/abc?autoplay=1")
        assertTrue(html.contains("<iframe"))
        assertTrue(html.contains("referrerpolicy=\"unsafe-url\""))
        assertTrue(html.contains("https://streamfree.vip/embed/abc?autoplay=1"))

        val extracted = EmbedFallbackView.extractM3u8FromHtml(
            """<script>player.setup({file:"https:\/\/cdn.stream.test\/hls\/1080\/index.m3u8?t=99"});</script>"""
        )
        assertEquals("https://cdn.stream.test/hls/1080/index.m3u8?t=99", extracted)

        // streamfree.vip /hls/*.m3u8 trả về payload mã hóa #ENC-AESGCM, phải bỏ qua không gửi ExoPlayer
        assertTrue(EmbedFallbackView.isEncryptedEmbedHost("https://streamfree.vip/embed/vt/OQvjBYhF"))
        assertFalse(EmbedFallbackView.isM3u8StreamUrl("https://streamfree.vip/hls/0000-1111.m3u8?vid=abc"))
        assertTrue(EmbedFallbackView.isM3u8StreamUrl("https://cdn.stream.test/hls/1080/index.m3u8"))
    }

    @Test
    fun hlsOfflineManager_parsesMasterAndMediaPlaylists() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            1080p/index.m3u8
        """.trimIndent()
        val parsedMaster = HlsOfflineManager.parseM3u8Lines(master)
        assertTrue(parsedMaster.isMaster)
        assertEquals(listOf("720p/index.m3u8", "1080p/index.m3u8"), parsedMaster.variants)
        assertEquals(
            "https://cdn.example.com/hls/1080p/index.m3u8",
            HlsOfflineManager.resolvePlaylistUrl("https://cdn.example.com/hls/master.m3u8", "1080p/index.m3u8")
        )

        val media = """
            #EXTM3U
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsedMedia = HlsOfflineManager.parseM3u8Lines(media)
        assertFalse(parsedMedia.isMaster)
        assertEquals(listOf("seg0.ts", "seg1.ts"), parsedMedia.segments)
    }

    private fun sampleMovie(
        id: String,
        title: String,
        latestEp: Int,
        totalEp: Int = latestEp,
        rating: Double = 9.5,
        viewsNum: Double = 10.0,
        audioModes: List<String> = listOf("Vietsub", "Thuyết Minh"),
        genres: List<String> = listOf("Tu Tiên")
    ) = DonghuaMovie(
        id = id,
        slug = id,
        title = title,
        originalTitle = title,
        studio = "Test",
        latestEp = latestEp,
        totalEp = totalEp,
        rating = rating,
        views = "${viewsNum}M",
        viewsNum = viewsNum,
        quality = "4K",
        audioModes = audioModes,
        sources = listOf("YanHH3D Now"),
        genres = genres,
        scheduleDay = "Chủ Nhật",
        scheduleTime = "09:00",
        poster = ""
    )

    @Test
    fun catalogQueryEngine_filtersAudioAndSortsAccurately() {
        val m1 = sampleMovie(
            id = "tien-nghich",
            title = "Tiên Nghịch",
            latestEp = 168,
            rating = 9.9,
            viewsNum = 90.0,
            audioModes = listOf("Thuyết Minh", "Vietsub")
        )
        val m2 = sampleMovie(
            id = "dau-pha",
            title = "Đấu Phá Thương Khung",
            latestEp = 135,
            rating = 9.8,
            viewsNum = 120.0,
            audioModes = listOf("Vietsub", "Thuyết Minh")
        )
        val m3 = sampleMovie(
            id = "an-the",
            title = "Ám Hà Truyện",
            latestEp = 200,
            rating = 9.4,
            viewsNum = 50.0,
            audioModes = listOf("Thuyết Minh")
        )

        val list = listOf(m1, m2, m3)

        // Filter Thuyết Minh chỉ chọn phim ưu tiên Thuyết Minh thật sự (không lấy phim Vietsub mặc định)
        val dubbed = CatalogQueryEngine.filterAndSort(
            allMovies = list,
            activeGenreMovies = null,
            remoteSearchResults = emptyList(),
            selectedGenre = "Tất cả",
            searchQuery = "",
            audioFilter = CatalogAudioFilter.DUBBED,
            sortMode = CatalogSortMode.DEFAULT
        )
        assertEquals(listOf("tien-nghich", "an-the"), dubbed.map { it.id })

        // Sort nhiều tập nhất
        val byEp = CatalogQueryEngine.filterAndSort(
            allMovies = list,
            activeGenreMovies = null,
            remoteSearchResults = emptyList(),
            selectedGenre = "Tất cả",
            searchQuery = "",
            audioFilter = CatalogAudioFilter.ALL,
            sortMode = CatalogSortMode.MOST_EPISODES
        )
        assertEquals(listOf("an-the", "tien-nghich", "dau-pha"), byEp.map { it.id })

        // Sort đánh giá cao
        val byRating = CatalogQueryEngine.filterAndSort(
            allMovies = list,
            activeGenreMovies = null,
            remoteSearchResults = emptyList(),
            selectedGenre = "Tất cả",
            searchQuery = "",
            audioFilter = CatalogAudioFilter.ALL,
            sortMode = CatalogSortMode.TOP_RATED
        )
        assertEquals(listOf("tien-nghich", "dau-pha", "an-the"), byRating.map { it.id })

        // Sort Tên A -> Z chuẩn tiếng Việt không dấu
        val byTitle = CatalogQueryEngine.filterAndSort(
            allMovies = list,
            activeGenreMovies = null,
            remoteSearchResults = emptyList(),
            selectedGenre = "Tất cả",
            searchQuery = "",
            audioFilter = CatalogAudioFilter.ALL,
            sortMode = CatalogSortMode.TITLE_AZ
        )
        assertEquals(listOf("an-the", "dau-pha", "tien-nghich"), byTitle.map { it.id })
    }

    @Test
    fun mergeCatalogPages_deduplicatesAndPreservesOrder() {
        val page1 = listOf(
            sampleMovie("a", "Phim A", latestEp = 10, genres = listOf("Tu Tiên")),
            sampleMovie("b", "Phim B", latestEp = 20)
        )
        val page2 = listOf(
            sampleMovie("b", "Phim B", latestEp = 24, genres = listOf("Huyền Huyễn")),
            sampleMovie("c", "Phim C", latestEp = 5)
        )
        val merged = MovieRepository.mergeCatalogPages(page1, page2)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals(24, merged[1].latestEp)
        assertTrue(merged[1].genres.contains("Huyền Huyễn"))

        val pageResult = CatalogPageResult(page1, page = 1, totalPages = 5)
        assertTrue(pageResult.hasMore)
        assertFalse(CatalogPageResult(page1, page = 5, totalPages = 5).hasMore)
    }

    @Test
    fun catalogQueryEngine_filtersStatusAndSkipsProxyForFastCdns() {
        val ongoing = sampleMovie("on", "Đang Chiếu", latestEp = 40, totalEp = 100)
            .copy(statusLabel = "Đang chiếu")
        val done = sampleMovie("done", "Hoàn Thành", latestEp = 52, totalEp = 52)
            .copy(statusLabel = "Hoàn thành")
        val list = listOf(ongoing, done)

        val resOngoing = CatalogQueryEngine.filterAndSort(
            allMovies = list,
            activeGenreMovies = null,
            remoteSearchResults = emptyList(),
            selectedGenre = "Tất cả",
            searchQuery = "",
            statusFilter = CatalogStatusFilter.ONGOING
        )
        assertEquals(listOf("on"), resOngoing.map { it.id })

        val resDone = CatalogQueryEngine.filterAndSort(
            allMovies = list,
            activeGenreMovies = null,
            remoteSearchResults = emptyList(),
            selectedGenre = "Tất cả",
            searchQuery = "",
            statusFilter = CatalogStatusFilter.COMPLETED
        )
        assertEquals(listOf("done"), resDone.map { it.id })

        assertEquals(
            "https://phimimg.com/upload/poster.jpg",
            MovieRepository.getOptimizedPosterUrl("https://phimimg.com/upload/poster.jpg")
        )
        assertEquals(
            "https://yanhh3d.now/storage/poster.png",
            MovieRepository.getOptimizedPosterUrl("https://yanhh3d.now/storage/poster.png")
        )
        assertTrue(
            MovieRepository.getOptimizedPosterUrl("https://other-slow.example.com/a.jpg")
                .contains("wsrv.nl")
        )
    }

    @Test
    fun dohParser_and_appDiagnostics_handleValidAndMalformedInputs() {
        val sampleDoh = """
            {
              "Status": 0,
              "Answer": [
                {"name": "yanhh3d.now.", "type": 5, "data": "alias.example.com."},
                {"name": "yanhh3d.now.", "type": 1, "data": "104.21.12.34"},
                {"name": "yanhh3d.now.", "type": 1, "data": "999.1.1.1"},
                {"name": "yanhh3d.now.", "type": 1, "data": "172.67.150.22"}
              ]
            }
        """.trimIndent()
        val addrs = com.hoathinh3d.donghua.core.HttpClientProvider.parseDohAnswersJson("yanhh3d.now", sampleDoh)
        assertEquals(listOf("104.21.12.34", "172.67.150.22"), addrs.map { it.hostAddress })
        assertTrue(com.hoathinh3d.donghua.core.HttpClientProvider.parseDohAnswersJson("yanhh3d.now", "{invalid").isEmpty())

        com.hoathinh3d.donghua.core.AppDiagnostics.clear()
        for (i in 1..60) {
            com.hoathinh3d.donghua.core.AppDiagnostics.log("Test", "msg-$i")
        }
        assertEquals(50, com.hoathinh3d.donghua.core.AppDiagnostics.count())
        val dump = com.hoathinh3d.donghua.core.AppDiagnostics.dump()
        assertFalse(dump.contains("msg-10\n"))
        assertTrue(dump.contains("msg-60"))
        com.hoathinh3d.donghua.core.AppDiagnostics.clear()
        assertEquals(0, com.hoathinh3d.donghua.core.AppDiagnostics.count())
    }

    @Test
    fun catalogQueryEngine_countsAndFiltersMultiSourceSearch() {
        val mNow = sampleMovie("tien-nghich", "Tiên Nghịch", latestEp = 168).copy(sources = listOf("YanHH3D Now"))
        val mBoth = sampleMovie("dau-pha", "Đấu Phá Thương Khung", latestEp = 135).copy(sources = listOf("YanHH3D Now", "YanHH3D"))
        val mYan = sampleMovie("the-gioi", "Thế Giới Hoàn Mỹ", latestEp = 200).copy(sources = listOf("YanHH3D"))

        val hits = CatalogQueryEngine.countSearchHitsBySource(
            allMovies = listOf(mNow, mBoth, mYan),
            remoteSearchResults = emptyList(),
            searchQuery = "a"
        ).toMap()
        assertEquals(2, hits["YanHH3D"])
        assertEquals(1, hits["YanHH3D Now"])

        val onlyYan = CatalogQueryEngine.filterAndSort(
            allMovies = listOf(mNow, mBoth, mYan),
            activeGenreMovies = null,
            remoteSearchResults = emptyList(),
            selectedGenre = "Tất cả",
            searchQuery = "the",
            searchSourceFilter = "YanHH3D"
        )
        assertEquals(listOf("the-gioi"), onlyYan.map { it.id })
    }

    @Test
    fun compatTls_parsesModernRootsPemAndBuildsCompositeTrustManager() {
        val pemFile = java.io.File("src/main/res/raw/modern_roots.pem")
        assertTrue("modern_roots.pem must exist", pemFile.exists())
        val bytes = pemFile.readBytes()
        val certs = com.hoathinh3d.donghua.core.CompatTls.parseCertificatesFromPem(bytes)
        assertTrue("Expected >= 10 modern Root CAs, got ${certs.size}", certs.size >= 10)
        assertTrue(certs.any { it.subjectX500Principal.name.contains("ISRG Root X2") })
        assertTrue(certs.any { it.subjectX500Principal.name.contains("ISRG Root X1") })

        val tm = com.hoathinh3d.donghua.core.CompatTls.buildTrustManager(bytes)
        assertTrue(tm.acceptedIssuers.size >= certs.size)
    }

    @Test
    fun appUpdateChecker_parsesCustomAndGithubManifests_andVerifiesSha256() {
        val sampleSha = "a".repeat(64)
        val customJson = """
            {
              "versionCode": 42,
              "versionName": "3.6.0-universal",
              "apkUrl": "https://cdn.example.com/HoatHinh3D-v3.6.0.apk",
              "changelog": "Tăng tốc độ phát trên TV Box",
              "sha256": "SHA256:$sampleSha"
            }
        """.trimIndent()
        val custom = com.hoathinh3d.donghua.core.AppUpdateChecker.parseManifestJson(customJson)
        assertEquals(42, custom?.versionCode)
        assertEquals("3.6.0-universal", custom?.versionName)
        assertEquals("https://cdn.example.com/HoatHinh3D-v3.6.0.apk", custom?.apkUrl)
        assertEquals("Tăng tốc độ phát trên TV Box", custom?.changelog)
        assertEquals(sampleSha, custom?.sha256)

        val ghSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val ghJson = """
            {
              "tag_name": "v3.7.1",
              "body": "Sửa lỗi đồng bộ\nSHA-256: $ghSha",
              "assets": [
                {"browser_download_url": "https://github.com/user/repo/releases/download/v3.7.1/notes.txt"},
                {"browser_download_url": "https://github.com/user/repo/releases/download/v3.7.1/app-debug.apk"}
              ]
            }
        """.trimIndent()
        val gh = com.hoathinh3d.donghua.core.AppUpdateChecker.parseManifestJson(ghJson)
        assertEquals(0, gh?.versionCode)
        assertEquals("3.7.1", gh?.versionName)
        assertEquals("https://github.com/user/repo/releases/download/v3.7.1/app-debug.apk", gh?.apkUrl)
        assertEquals(ghSha, gh?.sha256)

        val tmpFile = java.io.File.createTempFile("hh3d_sha_test", ".bin").apply {
            writeBytes("hoathinh3d-update-payload".toByteArray())
            deleteOnExit()
        }
        val computed = com.hoathinh3d.donghua.core.AppUpdateChecker.computeSha256(tmpFile)
        assertTrue(com.hoathinh3d.donghua.core.AppUpdateChecker.verifySha256(tmpFile, computed.uppercase()))
        assertFalse(com.hoathinh3d.donghua.core.AppUpdateChecker.verifySha256(tmpFile, "f".repeat(64)))
        assertTrue(com.hoathinh3d.donghua.core.AppUpdateChecker.verifySha256(tmpFile, ""))

        assertEquals(null, com.hoathinh3d.donghua.core.AppUpdateChecker.parseManifestJson("""{"invalid":true}"""))

        assertTrue(com.hoathinh3d.donghua.core.AppUpdateChecker.isNewerVersion(36, "3.5.0", currentCode = 35, currentName = "3.5.0-universal"))
        assertFalse(com.hoathinh3d.donghua.core.AppUpdateChecker.isNewerVersion(34, "4.0.0", currentCode = 35, currentName = "3.5.0-universal"))
        assertTrue(com.hoathinh3d.donghua.core.AppUpdateChecker.isNewerVersion(0, "3.6.0", currentCode = 35, currentName = "3.5.0-universal"))
        assertFalse(com.hoathinh3d.donghua.core.AppUpdateChecker.isNewerVersion(0, "3.5.0", currentCode = 35, currentName = "3.5.0-universal"))

        val ghWithCodeJson = """
            {
              "tag_name": "v42",
              "body": "versionCode: 42\nSHA-256: $ghSha",
              "assets": [
                {"browser_download_url": "https://github.com/long-1285/Hoathinh3D/releases/download/v42/HoatHinh3d.apk"}
              ]
            }
        """.trimIndent()
        val ghWithCode = com.hoathinh3d.donghua.core.AppUpdateChecker.parseManifestJson(ghWithCodeJson)
        assertEquals(42, ghWithCode?.versionCode)
        assertEquals("42", ghWithCode?.versionName)

        assertEquals(
            com.hoathinh3d.donghua.core.AppUpdateChecker.DEFAULT_MANIFEST_URL,
            com.hoathinh3d.donghua.core.AppUpdateChecker.resolveEffectiveManifestUrl("   ")
        )
        assertEquals(
            "https://raw.githubusercontent.com/long-1285/Hoathinh3D/main/version.json?t=12345",
            com.hoathinh3d.donghua.core.AppUpdateChecker.withCacheBuster(
                com.hoathinh3d.donghua.core.AppUpdateChecker.DEFAULT_MANIFEST_URL,
                12345L
            )
        )

        val badApk = java.io.File.createTempFile("hh3d_bad_apk", ".apk").apply {
            writeBytes(ByteArray(120_000) { 0x41 })
            deleteOnExit()
        }
        val goodApk = java.io.File.createTempFile("hh3d_good_apk", ".apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(120_000))
            deleteOnExit()
        }
        assertFalse(com.hoathinh3d.donghua.core.AppUpdateChecker.isValidApkArchive(badApk))
        assertTrue(com.hoathinh3d.donghua.core.AppUpdateChecker.isValidApkArchive(goodApk))
    }

    @Test
    fun webSourceCategories_renameFirstChipToTheLoaiPhim_andSyncLiveWebCategories() {
        assertEquals("Thể loại phim", com.hoathinh3d.donghua.data.model.InitialData.GENRES_DATA.first().label)
        assertEquals("Thể loại phim", com.hoathinh3d.donghua.data.source.YanHh3dNowScraper.STATIC_CATEGORIES.first().label)

        val portalHtml = """
            <html><body>
              <nav>
                <a href="/">Trang chủ</a>
                <a href="/danh-sach/phim-hot">Phim Hot</a>
                <a href="/danh-sach/phim-chieu-rap">Phim Chiếu Rạp</a>
                <a href="/danh-sach/phim-le">Phim Lẻ</a>
                <a href="/danh-sach/phim-bo">Phim Bộ</a>
                <a href="/danh-sach/hoat-hinh">Phim Hoạt Hình</a>
                <a href="/the-loai/hanh-dong">Phim Hành Động</a>
                <a href="/the-loai/tinh-cam">Tình Cảm</a>
                <a href="/lien-he">Liên hệ</a>
              </nav>
            </body></html>
        """.trimIndent()
        val htmlCats = com.hoathinh3d.donghua.data.source.CustomSourceParser.parseCategoriesFromHtml(portalHtml, "https://phimweb.example")
        assertEquals("Thể loại phim", htmlCats.first().label)
        val labels = htmlCats.map { it.label }
        assertTrue(labels.contains("Phim Hot"))
        assertTrue(labels.contains("Phim Chiếu Rạp"))
        assertTrue(labels.contains("Phim Lẻ"))
        assertTrue(labels.contains("Phim Bộ"))
        assertTrue(labels.contains("Phim Hoạt Hình"))
        assertTrue(labels.contains("Phim Hành Động"))
        assertTrue(labels.contains("Tình Cảm"))
        assertFalse(labels.contains("Liên hệ"))

        val apiCats = com.hoathinh3d.donghua.data.source.CustomSourceParser.parseCategoriesFromApiJson(
            """[{"name":"Hành Động","slug":"hanh-dong"},{"name":"Tình Cảm","slug":"tinh-cam"}]""",
            "https://phimapi.com",
            isPhimApi = true
        )
        assertEquals("Thể loại phim", apiCats.first().label)
        assertTrue(apiCats.any { it.label == "Phim Hot" })
        assertTrue(apiCats.any { it.label == "Phim Chiếu Rạp" })
        assertTrue(apiCats.any { it.label == "Tình Cảm" })
    }

    @Test
    fun mergeCatalogPages_replacesDeadPosterAndDeduplicatesSameTitleOrSlug() {
        val deadStub = com.hoathinh3d.donghua.data.model.DonghuaMovie(
            id = "tghm",
            slug = "the-gioi-hoan-my",
            title = "Thế Giới Hoàn Mỹ",
            originalTitle = "Perfect World",
            studio = "Foch Film",
            latestEp = 288,
            totalEp = 300,
            rating = 9.8,
            views = "22.1M",
            viewsNum = 22.1,
            quality = "4K 60FPS",
            audioModes = listOf("Thuyết Minh"),
            sources = listOf("YanHH3D Now"),
            genres = listOf("Huyền Huyễn"),
            scheduleDay = "Thứ 6",
            scheduleTime = "09:00",
            poster = "https://phimimg.com/upload/vod/20231128-1/dead.jpg"
        )
        val liveCard = deadStub.copy(
            id = "yannow_the-gioi-hoan-my-thuyet-minh-tieng-viet",
            slug = "the-gioi-hoan-my-thuyet-minh-tieng-viet",
            poster = "https://yanhh3d.now/storage/movies/the-gioi-hoan-my-1782129775.png",
            thumb = "https://yanhh3d.now/storage/movies/the-gioi-hoan-my-1782129775.png"
        )

        val merged = MovieRepository.mergeCatalogPages(listOf(deadStub), listOf(liveCard))
        assertEquals(1, merged.size)
        assertEquals("https://yanhh3d.now/storage/movies/the-gioi-hoan-my-1782129775.png", merged.first().poster)
        assertEquals("yannow_the-gioi-hoan-my-thuyet-minh-tieng-viet", merged.first().id)

        // Mọi phim mặc định trong InitialData đều phải dùng link poster sống hợp lệ (không dùng link phimimg đã chết)
        assertTrue(
            com.hoathinh3d.donghua.data.model.InitialData.INITIAL_DONGHUA_CATALOG.none {
                MovieRepository.isDeadOrBlankPoster(it.poster)
            }
        )
    }

    @Test
    fun extractPosterUrl_ignoresDataUriAndResolvesRelativeLazyAttrs() {
        val html = """
            <div>
              <img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
                   data-lazy-src="/storage/movies/demo-poster.jpg" />
            </div>
        """.trimIndent()
        val img = org.jsoup.Jsoup.parse(html, "https://yanhh3d.now").selectFirst("img")
        assertEquals(
            "https://yanhh3d.now/storage/movies/demo-poster.jpg",
            MovieRepository.extractPosterUrl(img, "https://yanhh3d.now")
        )
    }
}
