# PROJECT MEMORY: HOATHINH3D UNIVERSAL (ANDROID NATIVE KOTLIN)

> **Dành cho AI Agent tiếp theo:** 
> Vui lòng đọc kỹ file này trước khi thực hiện bất kỳ thay đổi nào trong dự án. 
> File này đóng vai trò là "Bộ nhớ dài hạn" ghi lại kiến trúc Android Native hiện tại, quy tắc biên dịch OpenJDK 21, lịch sử phát triển tới Phiên 23 và trạng thái bàn giao.

---

## ⚠️ QUY TẮC BẮT BUỘC ĐỐI VỚI MỌI AI AGENT

1. **Đối chiếu & đồng bộ mã nguồn Git trước khi bắt đầu phiên debug mới:**
   - Kho lưu trữ Git chính thức: `https://github.com/long-1285/Hoathinh3D.git` (nhánh `main`).
   - Khi người dùng yêu cầu *"Đọc memory dự án và tiếp tục phát triển dự án"*, **BẮT BUỘC** kiểm tra, so sánh đối chiếu mã nguồn hiện tại trên máy với mã nguồn trên Git (`git fetch` / so sánh commit/diff); nếu trên Git mới hơn thì kéo/clone phiên bản mới nhất về máy trước rồi mới tiếp tục thực hiện debug/phát triển trên phiên bản mới nhất đó.
   - **TUYỆT ĐỐI KHÔNG** lưu token, mật khẩu hoặc thông tin xác thực Git vào mã nguồn, `.git/config`, `PROJECT_MEMORY.md` hay bất kỳ file nào trong dự án.
2. **Cập nhật Memory sau mỗi phiên làm việc / debug:**
   - Khi kết thúc phiên debug hoặc thêm tính năng, **BẮT BUỘC** cập nhật mục [5. Nhật Ký Debug & Thay Đổi](#5-nhật-ký-debug--thay-đổi-debug--changelog) (thêm phiên mới lên đầu), [6. Tổng Kết Bàn Giao](#6-tổng-kết-bàn-giao--trạng-thái-hiện-tại-handoff-status) và [7. Prompt Chuyển Giao](#7-prompt-chuyển-giao-cho-ai-agent-phiên-sau).
3. **Bắt buộc chạy Test & Build file APK sau mỗi lần thay đổi code:**
   - Tăng `versionCode` (theo số phiên) và `versionName` trong `android/app/build.gradle` khi chốt phiên mới.
   - Người dùng yêu cầu có file `.apk` mới nhất để test trực tiếp trên điện thoại / Android TV Box sau mỗi phiên:
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
     # Script tự động ép JAVA_HOME = OpenJDK 21 -> .\gradlew.bat testDebugUnitTest -> .\gradlew.bat assembleDebug -> copy ra HoatHinh3d.apk
     ```
   - Chạy `.\gradlew.bat lintDebug` bảo đảm 0 lỗi `NewApi`, xác nhận `testDebugUnitTest` qua 100%, xác nhận file APK tại `E:\Project\3_HoatHinh3D\HoatHinh3d.apk` và báo kích thước/đường dẫn/SHA-256 chính xác cho người dùng.
4. **Bắt buộc xuất bản OTA, đẩy mã nguồn & Release mới nhất lên Git sau mỗi phiên debug:**
   - Sau khi kiểm thử, build APK và cập nhật `PROJECT_MEMORY.md` xong ở mỗi phiên debug, **BẮT BUỘC** commit với thông điệp phiên bản rõ ràng (ví dụ: `feat(v44): ...` hoặc `fix(session-44): ...`), đẩy mã nguồn lên `https://github.com/long-1285/Hoathinh3D.git`, rồi chạy:
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\publish-ota.ps1 -SkipBuild -Changelog "Mô tả ngắn phiên bản..."
     ```
   - Script `publish-ota.ps1` tự động tải `HoatHinh3d.apk` lên GitHub Releases (tag `v<versionCode>`) và cập nhật `version.json` (kèm `versionCode`, `versionName`, `apkUrl`, `sha256`, `changelog`). Commit + push `version.json` lên `main` ngay sau khi Release asset đã live để thiết bị bấm **Kiểm tra cập nhật OTA** luôn nhận diện được phiên bản mới nhất trên Git và tải về cài đặt.

---

## 1. THÔNG TIN DỰ ÁN & MỤC TIÊU
- **Tên ứng dụng:** Hoạt Hình 3D (Package `HH3D v${versionName}` — Giao diện chính hiển thị `Hoạt Hình` + badge `3D`)
- **Package ID:** `com.hoathinh3d.donghua` (`minSdk 23` / `targetSdk 35`, `versionCode 58`, `versionName "3.6.8-v58"`)
- **Kiến trúc hiện tại:** **100% Android Native (Kotlin + XML Views + ViewBinding + AndroidX Media3 ExoPlayer 1.5.1)** trong `E:\Project\3_HoatHinh3D\android\`. *(Thư mục `src/` React/Vite cũ chỉ giữ làm tài liệu tham chiếu, không còn dùng Capacitor WebView cho UI chính).*
- **Nền tảng hỗ trợ:**
  - Điện thoại Android (`MOBILE` - 3 cột cân đối, gesture sáng/âm lượng/tua ngang, khóa xoay dọc)
  - Máy tính bảng & Giả lập PC (`TABLET` - 4 cột)
  - Android TV Box / Google TV (`TV` - `LEANBACK_LAUNCHER`, 5 cột + horizontal rows, D-Pad không rớt focus)

---

## 2. MÔI TRƯỜNG BIÊN DỊCH & CÔNG CỤ (DEV ENVIRONMENT)

| Thành phần | Đường dẫn / Phiên bản | Ghi chú quan trọng |
| :--- | :--- | :--- |
| **JDK (Java)** | OpenJDK 21 (hoặc JDK 17) | ⚠️ **BẮT BUỘC:** Gradle 8.11.1 không chạy với JBR 25 (`major version 69`). `build-apk.ps1` tự dò `JAVA_HOME` (OpenJDK 21/17) trên mọi máy Windows. |
| **Android SDK** | `%LOCALAPPDATA%\Android\Sdk` (hoặc `ANDROID_HOME`) | Compile/Target SDK 35 (Android 15), Min SDK 23 (Android 6.0). `build-apk.ps1` tự dò trên máy mới. |
| **Native Stack** | Kotlin `2.0.21`, Media3 ExoPlayer `1.5.1`, OkHttp `4.12.0`, Jsoup `1.18.3`, Glide `4.16.0`, Material `1.12.0` | Project Gradle đặt tại `android/` (không phụ thuộc `node_modules`) |
| **Build & OTA** | `build-apk.ps1` + `publish-ota.ps1` | `build-apk.ps1` build Debug APK; `publish-ota.ps1` xuất bản GitHub Release `v<code >` + cập nhật `version.json` OTA |
| **Git Repo** | `https://github.com/long-1285/Hoathinh3D.git` | Nhánh chính `main` (tuyệt đối không lưu token lên Git/mã nguồn) |

---

## 3. KIẾN TRÚC MÃ NGUỒN NATIVE (`android/app/src/`)

```text
E:\Project\3_HoatHinh3D\
├── GEMINI.md                                      # Bộ nhớ & quy tắc always_on tự đồng bộ theo repo Git
├── version.json                                   # Manifest OTA chính thức (raw.githubusercontent.com/long-1285/Hoathinh3D/main/version.json)
├── build-apk.ps1                                  # Script 1-click Test + Build APK với OpenJDK 21
├── publish-ota.ps1                                # Script xuất bản GitHub Release + cập nhật version.json OTA (không lưu token)
├── HoatHinh3D-Universal-debug.apk                 # File APK đầu ra mới nhất
├── android/app/src/main/java/com/hoathinh3d/donghua/
│   ├── HoatHinhApp.kt                             # Application init (PrefsStore, HttpClientProvider)
│   ├── core/
│   │   ├── DeviceProfileResolver.kt               # Nhận diện Mobile / Tablet / TV
│   │   ├── HttpClientProvider.kt                  # OkHttp singleton (ConnectionPool, DNS cache 5m)
│   │   ├── PrefsStore.kt                          # SharedPreferences v2, migration NguonC -> YanHH3DNow, JSON Backup/Restore
│   │   └── ThemeManager.kt                        # 4 themes Liquid Glass (Pearl, Aqua, Sakura, Dark)
│   ├── data/
│   │   ├── model/Models.kt & InitialData.kt       # 2 nguồn mặc định: YanHH3DNow (yanhh3d.now) & YanHH3D (yanhh3d.ee)
│   │   ├── offline/HlsOfflineManager.kt           # Tải & phát offline .m3u8 (hỗ trợ cắt PNG header ngụy trang TS)
│   │   └── source/
│   │       ├── YanHh3dNowScraper.kt               # Laravel scraper (18+ thể loại, lịch chiếu CSRF AJAX, streamrpt1/cccc)
│   │       ├── YanHh3dScraper.kt                  # WP-JSON + Archive scraper (432+ phim, Top Xem Nhiều, 8 server, dox_ajax_player)
│   │       ├── NguonCApiClient.kt                 # Dự phòng nội bộ PhimAPI (.m3u8 backfill nếu cần, đã gỡ NguonC khỏi UI/nguồn)
│   │       ├── CustomSourceParser.kt              # Probe & parser nguồn Web người dùng tự thêm
│   │       ├── VietnameseSearch.kt                # Tìm kiếm không dấu, viết tắt (dptk, tghm, pntt...)
│   │       └── MovieRepository.kt                 # Điều phối đa nguồn, gộp chi tiết/tập, ưu tiên Thuyết Minh
│   ├── player/
│   │   ├── PlayerActivity.kt                      # Trình phát Media3 ExoPlayer giao diện CloudStream APK v4.9.1
│   │   ├── PlayerHelpers.kt                       # ZeroDelaySeekCoalescer (0ms seek đầu), AspectMode, Drawer types
│   │   ├── InstantLoadControl.kt                  # Buffer ~500ms phản hồi tức thì + BackBuffer 25s
│   │   ├── HlsCacheProvider.kt                    # SimpleCache 256MB LRU + SanitizingTsDataSource (khử PNG-TS)
│   │   └── EmbedFallbackView.kt                   # Real <iframe> WebView fallback + sniffer (bỏ qua payload mã hóa streamfree.vip)
│   └── ui/
│       ├── MainActivity.kt                        # Header, Search, Spinner nguồn, 5 Bottom Tabs, Sleep Timer
│       ├── home/HomeFragment.kt                   # Hero, Tiếp tục xem, BXH, TV Rows, Chip thể loại động, Lưới phim
│       ├── schedule/ScheduleFragment.kt           # Lịch chiếu Thứ 2..CN hợp nhất YanHH3DNow + YanHH3D
│       ├── history/HistoryFragment.kt             # Lịch sử xem
│       ├── favorites/FavoritesFragment.kt         # Tủ phim + Cảnh báo tập mới + Quản lý bản lưu Offline
│       ├── detail/MovieDetailActivity.kt          # Chi tiết phim, Lưu Offline HLS (không còn nút Embed Online)
│       └── settings/SettingsFragment.kt           # 7 subtabs cài đặt (+Web presets yanhh3d.now & yanhh3d.ee)
└── android/app/src/test/java/com/hoathinh3d/donghua/
    ├── YanHh3dNowScraperTest.kt, YanHh3dScraperTest.kt, NguonCApiAndRepoTest.kt,
    ├── PrefsStoreTest.kt, PlayerHelpersTest.kt, VietnameseSearchTest.kt
```

---

## 4. QUY ƯỚC NGHIỆP VỤ & KỸ THUẬT CỐT LÕI

1. **2 Nguồn Chính Thức (`YanHH3DNow` mặc định & `YanHH3D`, đã bỏ `NguonC`):**
   - **YanHH3DNow (`https://yanhh3d.now`):** Laravel HTML/AJAX, 18+ danh mục (`/moi-cap-nhat`, `/dang-chieu`, `/hoan-thanh`, `/hoat-hinh-4k/3d/2d/ai`, `/phim-le`, `/the-loai/*`), 2 tab `Thuyết Minh` & `Vietsub` (`/sever2/`). Stream `streamrpt1.site` giải mã `data-obf -> pU` (bỏ `sU`), `var cccc` (`o1/v/` & `play-fb-v8`), `data-stream-url` (`sever2`) và `/embed/` (`master.m3u8?storage=drive`), cắt header ảnh ngụy trang MPEG-TS (kể cả URL CDN không có đuôi ảnh như `tiktokcdn.com` và `lh3.googleusercontent.com`) qua `HlsCacheProvider.SanitizingTsDataSource`.
   - **YanHH3D (`https://yanhh3d.ee`):** WP-JSON (`/categories`, `/status`, `/posts`, `/showtimes`, `post_options=11`) + trang `/yanhh3d-moi-cap-nhat`, `/top-yanhh3d-xem-nhieu`. Không nhân bản link `PhimAPI` sang 4–8 máy chủ giả; `PhimAPI` (`Trình phát Tích hợp • Thuyết Minh/Vietsub`) chạy độc lập làm nguồn dự phòng `.m3u8` sạch, báo lỗi nếu phim chỉ có `streamfree.vip` (`#ENC-AESGCM`).
   - **Ưu tiên Thuyết Minh & Nguồn Phát:** `AppSettings.defaultAudio = "Thuyết Minh"`. `MovieRepository.selectPreferredServerIdx` chọn nhóm `Thuyết Minh / Lồng Tiếng` trước, trong nhóm đó ưu tiên luồng trực tiếp > nguồn gốc nhiều gương CDN (`YanHH3D Now` > `Trình phát Tích hợp`) > nhiều tập nhất. `PlayerUtils.findEpisodeIdxByNumber` hỗ trợ ánh xạ cả tập gộp (`Tập 21-25`).
2. **Trình Phát CloudStream APK v4.9.1 & Seek 0ms:**
   - `PlayerActivity` giữ nguyên `SeekParameters.CLOSEST_SYNC`, `ZeroDelaySeekCoalescer` (0ms cú chạm/D-Pad đầu tiên), `SurfaceView` chuẩn (tránh tunneling stall trên TV Box).
   - Giao diện HUD CloudStream: Top bar tối giản tiêu đề giữa + nút tập/tập sau phải, 3 nút vector trắng trung tâm, SeekBar thumb tròn xanh mint `#2CE59B`, hàng 6 nút ngang dưới (`Khóa`, `Thu phóng`, `Phụ đề`, `Thuyết minh`, `Chỉnh phụ đề`, `Nguồn`).
3. **Persistence & Migration Keys:**
   - `hh3d_audio_default_tm_v1` (mặc định Thuyết Minh), `hh3d_sources_v5_yan_now_no_nguonc` (migrates built-in sources sang `[YanHH3DNow, YanHH3D]`, giữ nguyên `isCustom = true`).

---

## 5. NHẬT KÝ DEBUG & THAY ĐỔI (DEBUG & CHANGELOG)

### [2026-11-28] - Phiên 58: Bỏ Chia Sẻ Trong Trình Phát, Mặc Định `Auto • Thuyết Minh` Theo Mạng Thực Tế & Ẩn Thanh Chọn Máy Chủ Con Trong Chi Tiết Phim (`v3.6.8-v58`)
- **Mục tiêu người dùng:**
  1. Bỏ tính năng chia sẻ trong trình phát (`PlayerActivity`).
  2. Chất lượng phim mặc định ban đầu là **`Auto`** — tự chọn nguồn **Thuyết Minh** (co giãn theo chất lượng mạng thực tế).
  3. Không hiển thị hàng chip máy chủ con (`YanHH3D Now • (4K)`, `(1080)`, `(HD)`, `Trình phát Tích hợp`...) trong màn hình Chi tiết phim.
- **Thay đổi kỹ thuật đã thực hiện:**
  1. **Gỡ nút Chia sẻ trong Trình phát (`activity_player.xml`, `PlayerActivity.kt`):** Xóa `btnQuickShare` khỏi thanh điều khiển trên cùng của trình phát và xóa handler `shareCurrentMovieOrEpisode()` không còn dùng.
  2. **Mặc định ban đầu `Auto • Thuyết Minh` (`PrefsStore.kt`, `SettingsFragment.kt`, `PlayerActivity.kt`, `MovieDetailActivity.kt`):**
     - Bổ sung migration `Keys.QUALITY_AUTO_TM_DEFAULT_V58` trong `PrefsStore.getSettings()` để đảm bảo khởi tạo/nâng cấp đặt `defaultQuality = "Auto"` và `defaultAudio = "Thuyết Minh"` (sau đó vẫn lưu giữ tùy chỉnh của người dùng nếu họ đổi trong Cài đặt).
     - Cập nhật nhãn tùy chọn trong `SettingsFragment` (`"Auto • Thuyết minh (Theo mạng thực tế)"`).
     - Sửa `PlayerActivity` (`btnQuickQuality`, `txtPlayerBadge`) luôn hiển thị **`Auto`** ngay lúc mới mở khi bật `Auto` (thay vì hiển thị chuỗi `movie.quality` như `4K`) và dùng `AdaptiveTrackSelection.Factory`; `MovieDetailActivity.syncAudioAndServerSelection()` ưu tiên nguồn `Thuyết Minh` theo `settings.defaultAudio` (chỉ tự chuyển `Vietsub` khi phim chưa có bản Thuyết Minh).
  3. **Ẩn hàng chip máy chủ con ở Chi tiết phim (`activity_movie_detail.xml`, `MovieDetailActivity.kt`):** Ẩn vĩnh viễn `serverChipsScroll` (`View.GONE`), giữ nguyên 2 tab nguồn âm thanh **`🔊 Thuyết Minh`** / **`💬 Vietsub`**, ứng dụng tự động chọn máy chủ tối ưu và tự chuyển dự phòng khi lỗi trong trình phát.
- **Kiểm chứng:** `build-apk.ps1` + `gradlew.bat lintDebug` chạy thành công (`BUILD SUCCESSFUL`): **56/56** unit tests PASS (`PrefsStoreTest` đã bổ sung kiểm tra migration `QUALITY_AUTO_TM_DEFAULT_V58`), `lintDebug` 0 lỗi, `Cert SHA-256` khớp `e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`, `HoatHinh3d.apk` đạt **11.7 MB** (`12,267,375 bytes`, `APK SHA-256: ecabc261849594f72694db74f130ed67ecdcea704e5877d6e75a8fecf66a937e`). *Lưu ý: phiên 58 chưa test trực tiếp trên phần cứng thật.*

### [2026-11-28] - Phiên 57: Chuẩn Hóa Đa Nguồn (`MovieSourceProvider`), Khai Thác Toàn Diện `vsmov.com` (`VsmovSourceProvider`), `HeadlessWebViewSniffer` & Truyền Header ExoPlayer (`v3.6.7-v57`)
- **Mục tiêu người dùng:**
  1. Tạo interface chuẩn hóa đa nguồn `MovieSourceProvider` (`MovieItem`, `MovieDetail`, `Episode`, `VideoStream`) để cắm thêm website mới dễ dàng.
  2. Phân tích ngược & triển khai đầy đủ cho `https://vsmov.com/`: Trang chủ/Danh mục (phân trang), Tìm kiếm URL Encoding tiếng Việt, Bộ lọc nâng cao (Thể loại/Năm/Quốc gia/Sắp xếp), Chi tiết đa Server, Giải mã `.m3u8`/`.mp4` + `HeadlessWebViewSniffer` bắt link qua `shouldInterceptRequest` kèm `Referer`/`Origin`/`User-Agent`, CookieJar + Retry trên `Dispatchers.IO`.
  3. Truyền `VideoStream` + HTTP Headers vào `DefaultHttpDataSource.Factory` của Media3 ExoPlayer tránh `403 Forbidden`, và tích hợp chính thức nguồn **VSMov** vào app.
- **Kết quả khảo sát kỹ thuật `vsmov.com` & `streamvsmov.com`:**
  - `vsmov.com` cung cấp JSON API chính chủ: `/api/danh-sach/{phim-moi-cap-nhat|phim-bo|phim-le|subteam}?page=`, `/api/the-loai`, `/api/quoc-gia`, `/api/the-loai/{slug}?page=&country=&year=&sort_field=`, `/api/quoc-gia/{slug}?page=&year=&sort_field=`, `/api/tim-kiem?keyword=&limit=`, `/api/phim/{slug}`.
  - Trang embed `v*.streamvsmov.com/video/<uuid>` chứa `playerOptions.signedMasterUrl` (`.m3u8` kèm chữ ký `expires`/`signature`), `subtitles`, `thumb`; các phân đoạn trên CDN (`p25.streamvsmov.com/file/.../file-tiktok_*.png`) dùng **header PNG ngụy trang 452 byte** trước MPEG-TS (`0x47` chu kỳ 188 byte), tương thích hoàn toàn với `SanitizingTsDataSource` có sẵn trong `HlsCacheProvider` (`MAX_IMAGE_HEADER_PEEK_BYTES = 8192`).
- **Thay đổi kỹ thuật đã thực hiện:**
  1. **Kiến trúc đa nguồn (`MovieSourceProvider.kt`):** Định nghĩa `MovieSourceProvider`, `MovieItem`, `ServerData`, `MovieDetail`, `Episode`, `VideoStream`, `MovieFilter`, `TaxonomyOption`, `ProviderHomeFeed` kèm bridge `toDonghuaMovie()` và `toMovieDetailResult()`.
  2. **Provider `VsmovSourceProvider.kt` (`dispatchers.IO`, CookieJar + Backoff Retry):** Triển khai đầy đủ 6 nhóm tính năng JSON API + HTML Jsoup fallback, chuẩn hóa tên Server (`"Vietsub\r\n #1"` → `"Vietsub #1"`), và `extractStreamFromEmbedHtml()` + `HeadlessWebViewSniffer` fallback.
  3. **`HeadlessWebViewSniffer.kt` & `EmbedFallbackView.kt`:** Tách Sniffer `WebView` ẩn (`shouldInterceptRequest`) dùng chung, trả `VideoStream` kèm `Referer`, `Origin`, `User-Agent`, `Cookie`.
  4. **Media3 Custom Header & Tích hợp App (`Models.kt`, `HlsCacheProvider.kt`, `InitialData.kt`, `PrefsStore.kt`, `CustomSourceParser.kt`, `MovieRepository.kt`, `MovieDetailActivity.kt`, `PlayerActivity.kt`):**
     - Bổ sung `ExoPlayerStreamHelper` (`DefaultHttpDataSource.Factory` với `setDefaultRequestProperties(stream.headers)`) và `HlsCacheProvider.registerStreamHeaders()` lưu header theo domain gốc (`.streamvsmov.com`) để cả playlist lẫn CDN phân đoạn không lỗi `403`.
     - Thêm nguồn `VSMov` vào `InitialData.DEFAULT_BUILT_IN_SOURCES` + migration `Keys.SOURCES_V57_WITH_VSMOV` (giữ nguyên nguồn mặc định ban đầu `YanHH3DNow`, đồng thời cho phép chuyển sang `VSMov`, tìm kiếm chung và prewarm/phát trực tiếp trong `MovieDetailActivity` & `PlayerActivity`).
- **Kiểm chứng:** `build-apk.ps1` + `gradlew.bat lintDebug` chạy thành công (`BUILD SUCCESSFUL`): **56/56** unit tests PASS (`VsmovSourceProviderTest`: 4 test mới PASS, `PrefsStoreTest`: 9 test PASS), `lintDebug` 0 lỗi, `Cert SHA-256` khớp `e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`, `HoatHinh3d.apk` đạt **11.7 MB** (`12,268,539 bytes`, `APK SHA-256: f3ea5ed92209587152272c2d58e78f75df10a9d9281a567207080589765b4749`). *Lưu ý: phiên 57 chưa test trực tiếp trên phần cứng thật.*

### [2026-11-28] - Phiên 56: Nút `Làm mới` TV & `Ngẫu nhiên`, Nhắc Tập Mới Yêu Thích Trên Home, Lịch Sử Kèm Ngày & Nhảy Tập Chưa Xem / Đánh Dấu Đã Xem Hết (`v3.6.6-v56`)
- **Mục tiêu người dùng:** Triển khai 3 nâng cấp UX/QoL thiết thực đã đề xuất cho app xem phim online, bảo toàn độ mượt và tiết kiệm RAM trên MiTV4-ANSM0 (Android 6.0.1 API 23):
  1. Bổ sung nút **Làm mới danh sách trên TV** (do TV tắt pull-to-refresh để giữ D-Pad) và nút chọn phim **Ngẫu nhiên** ngay Trang chủ.
  2. Bổ sung **Nhắc tự động tập mới của phim Yêu thích** ngay trên màn hình chính khi phát hiện `newEpCount > 0`.
  3. Hiển thị **ngày xem tương đối (`Hôm nay`, `Hôm qua`, `dd/MM`)** trong Lịch sử; thêm nút **nhảy tới tập kế tiếp chưa xem** và nút **Đánh dấu đã xem hết / Bỏ đánh dấu** trong Chi tiết phim.
- **Thay đổi kỹ thuật đã thực hiện:**
  1. **Nút `↻ Làm mới` (TV) & `🎲 Ngẫu nhiên` (Mọi thiết bị) (`layout_home_header.xml`, `HomeFragment.kt`, `PlayerHelpers.kt`):**
     - Thêm `PlayerUtils.pickRandomMovie(...)`; thêm `btnHomeRandom` (`🎲 Ngẫu nhiên`) và `btnRefreshTvFeed` (`↻ Làm mới`, chỉ hiện khi `isTv`) tại thanh tiêu đề Kho Nguồn Phim trên Trang chủ.
  2. **Nhắc tập mới phim Yêu thích trên Trang chủ (`layout_home_header.xml`, `HomeFragment.kt`):**
     - Thêm dải `favUpdatesPanel` (`HorizontalScrollView` dùng pill `TextView` nhẹ không tạo thêm bitmap/RecyclerView để giữ RAM thấp trên MiTV4), tự hiển thị khi có phim trong `st.favorites` có `newEpCount > 0`; chạm vào chip mở thẳng Chi tiết phim, kèm nút `Xem tất cả ›` (`btnFavUpdatesAll`) mở tab Yêu thích (`MainTab.FAVORITES`).
  3. **Lịch sử kèm ngày xem & Nhảy tập chưa xem / Đánh dấu đã xem hết (`PlayerHelpers.kt`, `PrefsStore.kt`, `HistoryFragment.kt`, `activity_movie_detail.xml`, `MovieDetailActivity.kt`):**
     - Thêm `PlayerUtils.formatRelativeDayLabel(updatedAt, nowMs)` dùng `java.util.Calendar` (tương thích API 23, không dùng `java.time`) để gắn nhãn `Hôm nay` / `Hôm qua` / `dd/MM` vào meta từng phim trong `HistoryFragment`.
     - Thêm `PlayerUtils.nextUnwatchedEpisodeNumber(...)`, `PrefsStore.markEpisodesWatched(...)` và `PrefsStore.clearWatchedEpisodes(...)`; màn hình Chi tiết phim thêm `btnDetailNextUnwatched` (`⏭ Tập X (Chưa xem)`) và nút `btnToggleMarkAllWatched` (`✓ Đã xem hết` / `↺ Bỏ đánh dấu`).
- **Kiểm chứng:** `build-apk.ps1` + `gradlew.bat lintDebug` chạy thành công (`BUILD SUCCESSFUL`): **52/52** unit tests PASS (`PlayerHelpersTest` 12 — đã test `formatRelativeDayLabel`, `nextUnwatchedEpisodeNumber`, `pickRandomMovie`; `PrefsStoreTest` 9 — đã test `markEpisodesWatched` & `clearWatchedEpisodes`), `lintDebug` 0 lỗi, `Cert SHA-256` khớp `e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`, `HoatHinh3d.apk` đạt **11.64 MB** (`12,210,628 bytes`, `APK SHA-256: 260c97909cb6ceda5106955e95f0553a7e77932b983cf914eb0472048e2faa22`). *Lưu ý: phiên 56 chưa test trực tiếp trên phần cứng thật.*

### [2026-11-28] - Phiên 55: Header `Hoạt Hình 3D`, Chất Lượng Mặc Định `Auto` (ABR), Sửa Triệt Để Mục Yêu Thích (10/10 Phim), Gom Menu Thiết Bị Vào `Auto` & Hẹn Giờ Tắt / AudioFocus (`v3.6.5-v55`)
- **Mục tiêu người dùng:**
  1. Trên giao diện chính, bỏ dòng phụ `Donghua 3D • Liquid Glass UI` và phiên bản ở Header, chỉ hiển thị **`Hoạt Hình`** kèm biểu tượng nhỏ **`3D`** bên cạnh.
  2. Đặt chất lượng phim mặc định ban đầu là **`Auto`** (tự co giãn theo chất lượng mạng thực tế).
  3. Sửa triệt để lỗi mục **Yêu thích**: chọn 10 phim nhưng chỉ hiển thị 9 phim.
  4. Mục **Lựa chọn thiết bị phù hợp** trong Cài đặt Giao diện: ẩn 4 nút rời rạc, gom gọn vào **1 menu `Auto`**.
  5. Kiểm tra tổng thể và bổ sung tính năng thiết thực cho app xem phim online.
- **Thay đổi kỹ thuật đã thực hiện:**
  1. **Header giao diện chính gọn sạch (`activity_main.xml`, `MainActivity.kt`):** Đặt `txtHeaderBrand = "Hoạt Hình"`, `txtHeaderBadge = "3D"`, ẩn `txtHeaderSub` (`View.GONE`) trên mọi thiết bị.
  2. **Chất lượng mặc định `Auto` & ABR theo băng thông thực (`Models.kt`, `PrefsStore.kt`, `SettingsFragment.kt`, `PlayerActivity.kt`):**
     - Đổi `AppSettings.defaultQuality = "Auto"` kèm `Keys.QUALITY_AUTO_DEFAULT_V55`; `SettingsFragment` đưa `Auto (Theo mạng thực tế)` lên đầu.
     - `PlayerActivity` dùng `AdaptiveTrackSelection.Factory(4_000, 10_000, 10_000, 0.75f)` để tự tăng/hạ độ phân giải theo mạng thực tế, vẫn giữ trần bảo vệ phần cứng của `DeviceProfileResolver` cho TV RAM thấp.
  3. **Sửa triệt để lỗi thiếu phim Yêu thích (`PrefsStore.kt`, `MainActivity.kt`, `MovieDetailActivity.kt`, `FavoritesFragment.kt`, `MovieRepository.kt`):**
     - Bổ sung `Keys.FAVORITE_MOVIES` (`getFavoriteMoviesMap()`, `saveFavoriteMovieSnapshot()`, `backfillFavoriteMovies()`) và đưa `favoriteMovies` vào `exportFullBackup`/`importFullBackup`.
     - `FavoritesFragment` dựng danh sách trực tiếp từ `st.favorites.distinct()` (khớp theo cả `id` và `slug` trên feed sống + `st.trendingTop10` + `activeGenreMovies` + `remoteSearchResults` → fallback `st.favoriteMoviesMap` → `watchHistory`), bảo đảm **chọn 10 hiện đủ 10/10** bất kể phim ở trang 2+, tìm kiếm hay nguồn phụ; thêm menu nhấn giữ `Xem ngay` / `Chi tiết` / `Bỏ Yêu thích`.
  4. **Gom chọn thiết bị vào 1 menu `Auto` (`SettingsFragment.kt`):** Thay `buttonGrid` 4 nút trong `renderAppearanceTab` bằng `spinnerRow` gọn gàng mặc định `Auto (<Thiết bị nhận diện>)`.
  5. **Bổ sung tiện ích xem phim online chuẩn mực (`PlayerActivity.kt`):** Bật `setAudioAttributes(USAGE_MEDIA, true)` (nhường AudioFocus khi có cuộc gọi) + `setHandleAudioBecomingNoisy(true)` (tự tạm dừng khi rút tai nghe/Bluetooth) và thêm **Hẹn giờ tắt (`Tắt / 15p / 30p / 45p / 60p`)** trong Drawer cài đặt phát.
- **Kiểm chứng:** `build-apk.ps1` + `gradlew.bat lintDebug` chạy thành công (`BUILD SUCCESSFUL`): **51/51** unit tests PASS (`PrefsStoreTest` đã thêm test 10 phim yêu thích + round-trip backup & mặc định `"Auto"`), `lintDebug` 0 lỗi, `Cert SHA-256` khớp `e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`, `HoatHinh3d.apk` đạt **11.64 MB** (`12,201,519 bytes`, `APK SHA-256: 9e72e079f58c596bb04ce23fe194076ca9cb55bbb07556e6341bc00413da2b56`). *Lưu ý: phiên 55 chưa test trực tiếp trên phần cứng thật.*

### [2026-11-28] - Phiên 54: Đổi Tên Hiển Thị Thành `HH3D`, Gọn Thanh Thể Loại Trang Chủ, Bỏ Offline/Chia Sẻ & Phân 2 Nguồn Thuyết Minh / Vietsub Trong Chi Tiết Phim (`v3.6.4-v54`)
- **Mục tiêu người dùng:**
  1. Thay tên app trên giao diện chính từ `HoatHinh3D` thành **`HH3D`** (vẫn kèm số phiên bản tự động: `HH3D v3.6.4-v54`).
  2. Bỏ hiển thị hàng chip ngang (`Phim Hot`, `Phim Chiếu Rạp`, `Phim Lẻ`, `Phim Bộ`, ...) bên cạnh nút `Thể loại phim ▾` trên Trang chủ.
  3. Bỏ mục **Lưu Offline** và **Chia sẻ** trong phần Chi tiết phim.
  4. Trong phần Chi tiết phim, phân mục Chọn máy chủ thành **2 Nguồn: Thuyết Minh và Vietsub**, **mặc định chọn nguồn Thuyết Minh**.
- **Thay đổi kỹ thuật đã thực hiện:**
  1. **Tên App `HH3D` kèm phiên bản (`build.gradle`, `activity_main.xml`, `MainActivity.kt`):**
     - Cập nhật `resValue "string", "app_name", "HH3D v${versionName}"` & `title_activity_main` (`HH3D v3.6.4-v54`); cập nhật `txtHeaderBrand`, `contentDescription` và hộp thoại thoát thành `HH3D`.
  2. **Thu gọn thanh Thể loại Trang chủ (`HomeFragment.kt`):**
     - `b.rvGenres` chỉ nạp đúng nút đầu tiên (`Thể loại phim ▾` / `Thể loại phim: <đang chọn> ▾`), ẩn toàn bộ dải chip phụ bên phải; bấm nút vẫn mở **Bảng Liquid Glass Thể Loại Phim** đầy đủ mọi thể loại đồng bộ thời gian thực.
  3. **Bỏ Offline & Chia sẻ và phân tách 2 Nguồn Thuyết Minh / Vietsub trong Chi tiết phim (`activity_movie_detail.xml`, `MovieDetailActivity.kt`):**
     - Xóa `btnDetailOffline`, `btnDetailShare`, `observeOfflineServiceProgress()`, `enqueueOfflineDownloads()` khỏi màn hình Chi tiết phim.
     - Thêm `audioGroupContainer` hiển thị rõ 2 tab **`🔊 Thuyết Minh`** (mặc định được chọn khi phim có bản Thuyết Minh) và **`💬 Vietsub`**, kèm `txtAudioServerNotice` thông báo nếu nguồn chưa có bản dịch và danh sách máy chủ con lọc đúng theo nhóm âm thanh đang chọn (`serverChipsContainer`).
- **Kiểm chứng:** `build-apk.ps1` + `gradlew.bat lintDebug` chạy thành công (`BUILD SUCCESSFUL`): **50/50** unit tests PASS, `lintDebug` 0 lỗi, `Cert SHA-256` khớp `e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`, `HoatHinh3d.apk` đạt **11.63 MB** (`12,194,068 bytes`, `APK SHA-256: 341993dda4010f30c89d82a2cb7cd28af141d468fd4a0ebec52878097229a4b9`). *Lưu ý: phiên 54 chưa test cài đặt trực tiếp trên thiết bị thật.*

### [2026-11-28] - Phiên 53: Chuẩn Hóa & Đồng Bộ Chữ Ký Số Dùng Chung Giữa 2 Máy Build (Công Ty & Nhà) Để OTA Không Bao Giờ Xung Đột Gói (`v3.6.3-v53`)
- **Nguyên nhân gốc rễ lỗi xung đột chữ ký:** Trước Phiên 53, `assembleDebug` sử dụng `~/.android/debug.keystore` ngẫu nhiên của từng máy tính (`PC Công ty` khác `PC Nhà`), khiến Android từ chối cài đè OTA với lỗi *"Đã xảy ra lỗi với chữ ký nhà phát triển của APK"*.
- **Giải pháp đồng bộ chữ ký số an toàn (`v3.6.3-v53`):**
  1. **Quản lý Shared Keystore ngoài Git (`setup-signing.ps1`, `.gitignore`):**
     - Bổ sung `.gitignore` chặn tuyệt đối `keystore.properties`, `signing/`, `android/signing/`, `*.p12`, `*.pfx` (bên cạnh `*.keystore`, `*.jks`).
     - Viết [`setup-signing.ps1`](file:///E:/Project/3_HoatHinh3D/setup-signing.ps1) hỗ trợ `-Init` (sinh `hoathinh3d-shared.jks` PKCS12 RSA 2048-bit hạn 10,000 ngày tại `%USERPROFILE%\.hoathinh3d\`), `-AdoptLocalDebug` (nếu muốn giữ chữ ký `debug.keystore` cũ của máy kia), và `-Verify`.
     - Chỉ lưu dấu vân tay công khai `SHA-256` tại `android/signing-sha256.txt` (`e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`) trên Git để kiểm chứng chéo mà không làm lộ private key hay mật khẩu.
  2. **Tích hợp Gradle + Chặn Fail-Closed trong `build-apk.ps1` & `publish-ota.ps1` (`android/app/build.gradle`):**
     - `build.gradle` đọc cấu hình ký theo thứ tự `HH3D_KEYSTORE_PATH` → `android/keystore.properties` → `%USERPROFILE%/.hoathinh3d/keystore.properties`, gán `signingConfigs.sharedDebug` cho cả `debug` và `release`.
     - Cả `build-apk.ps1` và `publish-ota.ps1` bắt buộc chạy `apksigner verify --print-certs` đối chiếu với `android/signing-sha256.txt`, chặn ngay nếu máy build chưa đồng bộ `.hoathinh3d` hoặc ký lệch chứng chỉ.
- **Kiểm chứng:** Đã tạo Shared Keystore trên máy hiện tại, xác minh `setup-signing.ps1 -Verify` khớp `e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`, chạy `build-apk.ps1` + `lintDebug` thành công (**50/50** unit tests PASS, `lintDebug` 0 lỗi, `HoatHinh3d.apk` đạt **11.63 MB**, `12,198,569 bytes`, `SHA-256: c6f858a0ef2c19ecc41a099f76222d025ad732bb7839623669e17bc01517b47b`).

### [2026-11-28] - Phiên 52: Nén WebP & Giảm Dung Lượng APK, Tìm Kiếm Giọng Nói, Lọc Quảng Cáo HLS, Dò Giải Mã TV & Gắn Phiên Bản Vào Tên App (`v3.6.2-v52`)
- **Mục tiêu người dùng:**
  1. Hiện thực hóa các tinh hoa rút ra từ đối chiếu kỹ thuật với `hh3d.apk`, vẫn giữ tiêu chí **Load Nhanh – Mượt** và tối ưu cho **Xiaomi MiTV4**.
  2. Bổ sung số phiên bản sau tên App theo mỗi phiên App mới tạo ra.
- **Thay đổi kỹ thuật đã thực hiện:**
  1. **Tối ưu dung lượng APK & RAM khởi động (`res/*.webp`, `build.gradle`):**
     - Nén & resize `app_avatar.jpg` (`2000×2000`, `713,160 B`) thành `app_avatar.webp` (`192×192`, `15,110 B`), đồng thời chuyển `tv_banner.png`, `splash.png` và các `ic_launcher*.png` sang `.webp` (tiết kiệm **`1,589.0 KB`** asset ảnh raster và giảm giải mã Bitmap mở màn ~`16 MB` → `~147 KB` cho MiTV4).
     - Bổ sung `resourceConfigurations += ["vi", "en"]` trong `defaultConfig` để cắt toàn bộ chuỗi đa ngôn ngữ dư thừa của Media3/AppCompat khỏi `resources.arsc`. Tổng kích thước `HoatHinh3d.apk` Debug giảm từ **`13.84 MB` (`14,516,440 B`)** xuống còn **`11.63 MB` (`12,198,455 B`)**.
  2. **Tự động gắn số phiên bản vào tên App (`build.gradle`, `strings.xml`, `activity_main.xml`, `MainActivity.kt`):**
     - Dùng `resValue "string", "app_name", "HoatHinh3D v${versionName}"` và `title_activity_main` trong `build.gradle` + gán `binding.txtHeaderBrand.text = getString(R.string.app_name)` (`HoatHinh3D v3.6.2-v52`) cùng badge gọn `3D`.
  3. **Tìm kiếm bằng giọng nói không cần xin quyền ghi âm (`ic_lg_mic.xml`, `activity_main.xml`, `MainActivity.kt`):**
     - Thêm nút Micro `btnVoiceSearch` trong thanh tìm kiếm (`nextFocusLeft`/`nextFocusRight` chuẩn TV D-Pad) dùng `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (`vi-VN`), fallback toast an toàn khi thiết bị không cài Google Voice Search.
  4. **Lọc quảng cáo HLS tại chỗ & dò năng lực giải mã phần cứng (`HlsCacheProvider.kt`, `DeviceProfileResolver.kt`, `SettingsFragment.kt`):**
     - Bổ sung `HlsPlaylistAdFilter` tích hợp ngay vào `SanitizingTsDataSource` (0 overhead cho `.ts`/`.mp4`/Master playlist) để cắt block quảng cáo `#EXT-X-DISCONTINUITY` / domain quảng cáo khi bật Extension Chặn quảng cáo.
     - Bổ sung `DeviceProfileResolver.detectVideoDecoderCapability()` (dùng `MediaCodecList(REGULAR_CODECS)` chuẩn API 23+, phân biệt software codec theo tên để tránh `NewApi`) tích hợp vào `recommendedMaxVideoHeight` và hiển thị trong mục **Kiểm tra hệ thống**.
  5. **Tùy chỉnh phụ đề trực tiếp trên video đang phát (`Models.kt`, `PrefsStore.kt`, `PlayerActivity.kt`):**
     - Bổ sung `subtitleBottomPct` (`4%–30%`), `subtitleBgAlpha` (`0/175/235`) và `subtitleTextColor` (`Trắng`/`Vàng`), cho phép bấm chỉnh vị trí lên/xuống `±2%`, màu chữ và độ mờ nền ngay trong Drawer `PLAYBACK_SETTINGS`.
- **Kiểm chứng:** `build-apk.ps1` + `gradlew.bat lintDebug` chạy thành công (`BUILD SUCCESSFUL`): **50/50** unit tests PASS (`PlayerHelpersTest` đã thêm test `HlsPlaylistAdFilter` + cap decoder `1080p`; `PrefsStoreTest` đã thêm roundtrip tùy chỉnh phụ đề), `lintDebug` 0 lỗi, `HoatHinh3d.apk` đạt **11.63 MB** (`12,198,455 bytes`, `SHA-256: b3c28292503d52bcf5688d693ec9d4163c4c84a22e7f8dedefe3a966463a4df7`). *Lưu ý: phiên 52 chưa chạy đo trực tiếp qua ADB trên phần cứng MiTV4-ANSM0.*

### [2026-11-28] - Phiên 51: Bỏ Niên Phiên Nổi Bật Trên MiTV4, Thu Gọn Giao Diện Còn 2 Chế Độ & Tối Ưu Tải/Mượt Trên TV (`v3.6.1-v51`)
- **Mục tiêu người dùng:**
  1. Bỏ mục "Niên phiên nổi bật" (`heroCard`) khỏi giao diện Trang chủ trên **Xiaomi MiTV4 / Android TV**.
  2. Thu gọn mục **Giao diện** chỉ còn đúng **2 chế độ**: **1. Sáng dịu Ngọc Trai (`PEARL`)** và **2. Hoàng Hôn Đêm (`DARK`)**.
  3. Tiếp tục tối ưu giao diện, thao tác D-Pad, luồng tải dữ liệu và độ mượt tổng thể cho MiTV4 (`Android 6.0.1 API 23`, `1.6 GB RAM`, `armeabi-v7a`, `Mali-450 MP`).
- **Thay đổi kỹ thuật đã thực hiện:**
  1. **Ẩn Hero Banner trên Android TV (`HomeFragment.kt`):** Khi `isTv == true`, ẩn `b.heroCard` ("Niên phiên nổi bật") và hủy request Glide của `imgHeroBg` để giải phóng RAM bitmap và overdraw phần đầu trang chủ TV; tắt `itemAnimator` trên các hàng TV khi `tvLite == true` và dùng diff signature (`lastTvThemeSig`, `lastTvMetaSig`) + `PAYLOAD_META` thay cho `notifyDataSetChanged()` toàn cục.
  2. **Thu gọn hệ thống Giao diện còn 2 chế độ (`Models.kt`, `ThemeManager.kt`, `PrefsStore.kt`, `SettingsFragment.kt`, `MainActivity.kt`, `PrefsStoreTest.kt`):**
     - `ThemePreset` chỉ còn `PEARL` ("1. Sáng dịu Ngọc Trai") và `DARK` ("2. Hoàng Hôn Đêm"); `ThemePreset.fromWire` map `"dark"` về `DARK` và mọi giá trị cũ khác (`"sakura"`, `"aqua"`, ...) về `PEARL`.
     - `PrefsStore` bổ sung `Keys.APPEARANCE_TWO_MODES_V51` tách biệt khỏi `needsV45Migration` (bảo toàn thông số backup khi `importFullBackup`).
     - `SettingsFragment.renderAppearanceTab` thay cặp DarkMode + 3 preset cũ bằng lưới 2 chế độ rõ ràng; `MainActivity` đồng bộ nút chuyển nhanh trên Header & TV Rail (`tvRailThemeLabel`: `"Hoàng Hôn Đêm"` / `"Sáng Ngọc Trai"`).
  3. **Mở rộng tối ưu overdraw & luồng tải toàn cục:**
     - `ThemePalette` thêm cờ `liteRender: Boolean` và truyền đồng bộ từ `MainActivity.getPalette()` & `MovieDetailActivity` vào `liquidGlassSheetDrawable`, `glassPanelDrawable`, `pillDrawable`, `accentGradientDrawable`, `navTabDrawable`, bỏ qua `applyRoundedOutline` và animation chuyển tab khi `shouldUseReducedMotion` bật.
     - `YanHh3dNowScraper.fetchHtmlAndCaptureSession` bổ sung `Mutex` + `homeHtmlCache` (`15_000L` ms) tái sử dụng cho `fetchRealtimeTrendingTop10` để startup chỉ GET 1 lần `https://yanhh3d.now/`.
     - `MainActivity.onResume()` throttle `syncRealtimeTrendingNow` tối thiểu `45_000L` ms (`lastRealtimeSyncMs`) khi quay lại từ Player/Detail; `ScheduleFragment` chỉ `notifyDataSetChanged()` khi `lastSchedThemeSig` thay đổi; `activity_main.xml` bổ sung `nextFocusLeft/Down/Up` chuẩn cho D-Pad TV.
- **Kiểm chứng:** `build-apk.ps1` + `gradlew.bat lintDebug` chạy thành công (`BUILD SUCCESSFUL`): **49/49** unit tests PASS (`PrefsStoreTest` đã cập nhật cho 2 chế độ), `lintDebug` 0 lỗi, `HoatHinh3d.apk` đạt **13.84 MB** (`14,516,440 bytes`, `SHA-256: 1f816f792ddfd3d54ba3ca6c67dd3f3df75107d413de3ab2e5ed8de656a87b4d`). *Lưu ý: phiên 51 chưa chạy đo trực tiếp qua ADB trên phần cứng MiTV4-ANSM0.*

### [2026-11-27] - Phiên 50: Tắt 100% Giao Diện Động Ở Chế Độ TV & Thiết Bị RAM Siêu Thấp Cho Xiaomi MiTV4 (`v3.6.0-v50`)
- **Yêu cầu người dùng:**
  - Quét mạng LAN kết nối ADB tới MiTV4 (`192.168.2.9:5555`) để kiểm tra cấu hình và tắt toàn bộ giao diện động ở chế độ TV có thể gây chậm, giật lag cho MiTV4.
- **Hiện trạng kết nối ADB thực tế:**
  - Đã quét dải mạng LAN `192.168.2.1..254` (IP laptop `192.168.2.25`): phát hiện duy nhất **`192.168.2.9`** (MAC `b0-41-1d-d2-9f-13`) đang mở cổng `5555`, tuy nhiên `adb connect 192.168.2.9:5555` trả về trạng thái `offline` (chưa hoàn tất bắt tay `adbd` trên TV, cần xác nhận hộp thoại *"Allow USB debugging?"* hoặc bật/tắt lại ADB Debugging trên TV).
- **Thay đổi tối ưu đã thực hiện:**
  1. **Ép tắt 100% hiệu ứng động ở mọi thiết bị chế độ TV & Ultra-Low RAM ([DeviceProfileResolver.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/core/DeviceProfileResolver.kt)):**
     - Cập nhật `shouldUseReducedMotion(...)`: trả về `true` cho toàn bộ `ResolvedDeviceProfile.TV` và `isUltraLowRamDevice(context)` (`<= 1800 MB`), đảm bảo `animDuration()` trên toàn bộ overlay/tab trả về `0L`, `ambientOrbs` bị ẩn hoàn toàn (`View.GONE`).
  2. **Loại bỏ `RippleDrawable`, `specularSheenLayer` & Touch Scale Animation khi `liteRender` ([ThemeManager.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/core/ThemeManager.kt), [MainActivity.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Thêm `liteRender: Boolean = false` vào `navTabDrawable` và `liquidGlassSheetDrawable`: bỏ lớp `specularSheenLayer` thứ 2 (giảm 50% overdraw HWUI trên GPU Mali-450) và trả về `StateListDrawable` thuần thay vì `RippleDrawable` khi `liteRender == true`.
     - Trong `attachLiquidNavTapEffect(..., enableAnimation = false)`: reset `scaleX/scaleY = 1f`, hủy mọi animation đang chạy và gỡ `setOnTouchListener(null)`.
     - Truyền `liteRender = isLite` cho thanh Header, `tvNavRail`, `bottomNavContainer`, `floatingSearchContainer`, `searchBarBox` và các nút nổi.
  3. **Tắt `Hero Auto-Rotate`, `clipToOutline` & `RecyclerView.itemAnimator` trên TV ([HomeFragment.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [ScheduleFragment.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/schedule/ScheduleFragment.kt), [HistoryFragment.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/history/HistoryFragment.kt), [FavoritesFragment.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/favorites/FavoritesFragment.kt)):**
     - Dừng `startHeroAutoRotate()` khi `isLite == true` (không tự xoay tải ảnh Glide nền liên tục trên TV); bỏ `applyRoundedOutline(b.heroCard, 24f)` khi `isLite == true`.
     - Đặt `itemAnimator = null` trên tất cả `RecyclerView` (`rvHomeFeed`, `rvTrending`, `rvGenres`, `rvContinue`, `rvSchedule`, `rvHistory`, `rvFavorites`) khi `isLite == true`.
  4. **Tắt animation `btnSkipIntro` ở Player & bỏ `elevation` shadow khi đổi focus D-Pad ([PlayerActivity.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [ThemeManager.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/core/ThemeManager.kt)):**
     - Kiểm tra `shouldUseReducedMotion` trong `maybeShowSkipIntroForFiveSeconds()` để ẩn/hiện tức thì nút Bỏ qua Intro.
     - Trong `ThemeManager.attachTvCardFocusEffect`: khi `enableScale = false` (chế độ TV/Lite), gỡ hẳn `OnFocusChangeListener` và giữ `elevation = 0f` (tránh vẽ bóng đổ động trên GPU Mali-450 mỗi lần bấm phím D-Pad).
- **Kiểm thử & Build (bao gồm kiểm thử trực tiếp trên Xiaomi MiTV4-ANSM0 qua ADB `192.168.2.9:5555`):**
  - Thiết bị thật xác nhận: `MiTV4-ANSM0`, Android `6.0.1` (API `23`), `armeabi-v7a`, `1920x1080` (`density 320`), RAM `1.62 GB` (`1,663,176 kB`, trống ~`882 MB`), bộ nhớ `/data` `5.0 GB` (trống `1.9 GB`).
  - Kết quả đo runtime sau tối ưu: **0 crash / 0 ANR**, `TOTAL PSS = 164.0 MB`, `TextureCache = 3.67 MB`, `LayerCache = 0`, `Slow UI thread = 0`, `Slow bitmap uploads = 0` trong phiên D-Pad kiểm thử.
  - `testDebugUnitTest`: **49/49 tests PASSED** (0 failures, 0 errors, 0 skipped); `lintDebug`: **BUILD SUCCESSFUL** (0 errors).
  - APK mới nhất: `E:\Duan\01_HH3D\HoatHinh3d.apk` (**13.90 MB**, `14,571,088 bytes`, `SHA-256: 2560468b88a6c0739852c200c700d9ed26fa4e495f3271d1499893c862fc6867`).

### [2026-11-27] - Phiên 49: Tìm Kiếm Thông Minh (Lịch Sử & Từ Khóa Hot), Kéo Để Làm Mới (Pull-to-Refresh), Khóa Màn Hình Tự Ẩn 3s & Chia Sẻ Nhanh (`v3.5.9-v49`)
- **Yêu cầu người dùng:**
  - Triển khai tối ưu và hoàn thiện 3 định hướng phát triển cho phiên mới:
    1. **Tìm kiếm thông minh:** Lưu lịch sử tìm kiếm gần đây + gợi ý từ khóa Hot trực quan cho cả Mobile và Android TV Remote.
    2. **Kéo để làm mới (Pull-to-refresh):** Vuốt xuống làm mới nhanh cho Trang chủ và Lịch chiếu trên Mobile/Tablet.
    3. **Tối ưu Trình phát & Chi tiết phim:** Hoàn thiện tính năng Khóa màn hình chống chạm nhầm (nút mở khóa mờ tự ẩn sau 3s, tương thích Android 6.0.1) và thêm nút Chia sẻ nhanh phim/tập phim.
- **Thay đổi đã thực hiện:**
  1. **Tìm kiếm thông minh — Lịch sử & Gợi ý từ khóa Hot ([PrefsStore.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt), [activity_main.xml](file:///E:/Duan/01_HH3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Bổ sung `Keys.SEARCH_HISTORY` và các hàm `getSearchHistory`, `addSearchHistory` (chống trùng không phân biệt hoa thường, giới hạn 10 mục mới nhất), `removeSearchHistoryItem`, `clearSearchHistory` vào `PrefsStore`.
     - Mở rộng `floatingSearchContainer` với `searchSuggestionsPanel` dạng Liquid Glass gồm 2 hàng chip cuộn ngang: **TỪ KHÓA THỊNH HÀNH** (`HOT_KEYWORDS`: *Đấu Phá Thương Khung, Tiên Nghịch, Thế Giới Hoàn Mỹ, Thôn Phệ Tinh Không, Già Thiên, Phàm Nhân Tu Tiên, Bách Luyện Thành Thần, Vạn Giới Độc Tôn, Tuyệt Thế Đường Môn, Nhất Niệm Vĩnh Hằng*) và **TÌM KIẾM GẦN ĐÂY** kèm nút `✕` xóa từng mục và nút `Xóa lịch sử`.
     - Tự động ẩn bảng gợi ý khi người dùng bắt đầu nhập từ khóa và lưu lịch sử ngay khi thực hiện tìm kiếm (`runLiveSearch`), hỗ trợ đầy đủ D-Pad focus trên TV.
  2. **Kéo để làm mới (Pull-to-refresh) ([build.gradle](file:///E:/Duan/01_HH3D/android/app/build.gradle), [fragment_home.xml](file:///E:/Duan/01_HH3D/android/app/src/main/res/layout/fragment_home.xml), [fragment_schedule.xml](file:///E:/Duan/01_HH3D/android/app/src/main/res/layout/fragment_schedule.xml), [HomeFragment.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [ScheduleFragment.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/schedule/ScheduleFragment.kt)):**
     - Tích hợp `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` bọc `rvHomeFeed` (`swipeRefreshHome`) và `rvSchedule` (`swipeRefreshSchedule`).
     - Đồng bộ màu spinner theo `ThemePalette` (`p.accentFrom`, `p.accentTo`, `p.bgSecondary`), gọi `host?.reloadActiveSourceFeed()` / `loadScheduleForDay(activeDay)` khi kéo xuống, tự tắt `isRefreshing` sau khi nạp xong và tự động vô hiệu hóa gesture trên `ResolvedDeviceProfile.TV` để không cản trở D-Pad.
  3. **Tối ưu Khóa màn hình tự ẩn 3s & Chia sẻ nhanh ([activity_player.xml](file:///E:/Duan/01_HH3D/android/app/src/main/res/layout/activity_player.xml), [PlayerActivity.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [activity_movie_detail.xml](file:///E:/Duan/01_HH3D/android/app/src/main/res/layout/activity_movie_detail.xml), [MovieDetailActivity.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt), [PlayerHelpers.kt](file:///E:/Duan/01_HH3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerHelpers.kt)):**
     - Thiết kế lại `btnUnlockScreen` thành thẻ kính `LinearLayout` đặt tại `top|start` (tránh che phụ đề dưới đáy), dùng VectorDrawable `@drawable/ic_cs_lock` + chữ `"Chạm mở khóa"` thay cho emoji `🔒` (tránh lỗi ô vuông `▯` trên Android 6.0.1).
     - Bổ sung `unlockHideHandler` tự làm mờ và ẩn `btnUnlockScreen` sau 3 giây khi đang khóa (`isLocked = true`); chạm bất kỳ vị trí nào trên màn hình sẽ đánh thức lại nút mở khóa 3s mà vẫn chặn 100% gesture vuốt sáng/âm lượng/tua/double-tap.
     - Thêm `PlayerUtils.formatShareText`, nút `btnQuickShare` ở thanh trên `PlayerActivity` và `btnDetailShare` trong `MovieDetailActivity` mở Android Sharesheet (`Intent.ACTION_SEND`).
  4. **Cập nhật bộ dò môi trường [build-apk.ps1](file:///E:/Duan/01_HH3D/build-apk.ps1):**
     - Hỗ trợ tự dò thêm `JAVA_HOME` / `ANDROID_HOME` từ biến môi trường User/Machine và thư mục `C:\Android\jdk*` / `C:\Android` trên máy mới.
- **Kiểm thử & Build:**
  - Bổ sung unit test `searchHistory_addRemoveClear_andCapsAt10` ([PrefsStoreTest.kt](file:///E:/Duan/01_HH3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt)) và `formatShareText_handlesMovieAndEpisodeCleanly` ([PlayerHelpersTest.kt](file:///E:/Duan/01_HH3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt)).
  - `testDebugUnitTest`: **49/49 tests PASSED** (`NguonCApiAndRepoTest` 16, `PlayerHelpersTest` 10, `PrefsStoreTest` 8, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 6, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
  - `lintDebug`: **BUILD SUCCESSFUL** (0 errors). *(Chưa kiểm thử trực tiếp trên thiết bị thật qua ADB ở phiên này).*
  - APK mới nhất: `E:\Duan\01_HH3D\HoatHinh3d.apk` (**13.84 MB**, `14,514,180 bytes`, `SHA-256: 5e540226a7a86aed7967aa63918acba441e538791f824582791354d80d0d8bd6`).

### [2026-11-27] - Phiên 48: Sửa Triệt Để Lỗi Trùng Phim & Mất Poster Trang Chủ, Tối Ưu Toàn Diện Giao Diện & Nguồn Phát Đa Thiết Bị (`v3.5.8-v48`)
- **Yêu cầu người dùng:**
  1. Xử lý lỗi hiện trùng lặp phim nhưng không hiện poster phim trên giao diện màn hình chính (theo ảnh minh họa: *Đấu Phá Thương Khung*, *Tiên Nghịch*, *Thế Giới Hoàn Mỹ*, *Thôn Phệ Tinh Không* xen giữa các hàng phim thật nhưng trống ảnh bìa).
  2. Tối ưu lại một lần nữa nguồn phát, giao diện Trang chủ, bố cục các mục con và Trình phát khoa học & hài hòa nhất cho Điện thoại (`MOBILE`), Giả lập PC/Tablet (`TABLET`) và Android TV (`TV`).
- **Thay đổi đã thực hiện:**
  1. **Xử lý tận gốc lỗi trùng phim & mất poster (`MainActivity.kt`, `InitialData.kt`, `MovieRepository.kt`, `YanHh3dNowScraper.kt`, `YanHh3dScraper.kt`, `CustomSourceParser.kt`, `SettingsFragment.kt`, `MovieCardAdapter.kt`, `bg_poster_placeholder.xml`):**
     - **Loại bỏ chèn dữ liệu tĩnh sau feed sống (`MainActivity.loadLiveDonghuaFeed`):** Xóa đoạn gọi `PrefsStore.getAllMovies()` khi đã nhận được danh sách phim từ Web (`moviesTask.isNotEmpty()`); chỉ giữ phim `+Web` người dùng tự thêm (`customWebUrl`) rồi gộp qua `MovieRepository.mergeCatalogPages(...)`. Đồng bộ áp dụng hợp nhất tương tự cho `runLiveSearch`, `refreshCustomMovies`, `reloadAllPersistedState`, `resolveMovieForProgress` và `ScheduleFragment`.
     - **Thay toàn bộ link ảnh `phimimg.com` đã chết (HTTP 404) & cập nhật slug chuẩn (`InitialData.kt`, `CustomSourceParser.kt`, `SettingsFragment.kt`):** Cập nhật 8 mục dự phòng `INITIAL_DONGHUA_CATALOG` dùng ảnh sống xác thực từ `yanhh3d.now/storage/movies/...` + slug thật trên `YanHH3D Now`; xóa mọi link `phimimg.com/upload/vod/202...` cứng còn sót trong parser/thêm nguồn.
     - **Chuẩn hóa trích xuất ảnh & khử trùng lặp hợp nhất (`MovieRepository.kt`, `YanHh3dNowScraper.kt`, `YanHh3dScraper.kt`):** Viết helper dùng chung `isDeadOrBlankPoster`, `normalizePosterUrl`, `extractPosterUrl` (ưu tiên `data-src` / `data-lazy-src` / `data-original` / `data-url` / `src` / `srcset`, bỏ qua `data:image/` và `blank.gif`), và thống nhất logic gộp `mergeMoviePair` cho `mergeMovieLists`, `mergeCatalogPages` & `CatalogQueryEngine` (khử trùng theo slug chuẩn hóa lẫn tiêu đề chuẩn hóa khi có bản ghi dùng ảnh chết, luôn giữ poster sống tốt hơn).
     - **Placeholder ảnh chuyên biệt API 23+ (`bg_poster_placeholder.xml`, `MovieCardAdapter.kt`, `ScheduleFragment.kt`, `MovieDetailActivity.kt`, `HomeFragment.kt`):** Tạo vector `bg_poster_placeholder.xml` gọn nhẹ và gắn `.placeholder(...)`/`.error(...)` + xóa tải Glide cũ khi URL trống/hỏng, không còn ô trống đen trên mọi màn hình.
  2. **Tối ưu Nguồn phát, Trang chủ, Mục con & Trình phát đa thiết bị (`MovieRepository.kt`, `PlayerActivity.kt`, `MovieDetailActivity.kt`, `HomeFragment.kt`, `MovieCardAdapter.kt`, `ScheduleFragment.kt`, `FavoritesFragment.kt`, `HistoryFragment.kt`, `SettingsFragment.kt`):**
     - **Nguồn phát & Chi tiết phim:** `fetchMovieDetailFast` cập nhật poster sống từ nguồn phụ nếu nguồn đầu trống/hỏng và khử trùng các `ServerGroup` trùng tên; `MovieDetailActivity` đồng bộ cập nhật poster mới từ detail, cân kích thước backdrop (`216dp` / `250dp` / `266dp`) & poster (`104×144dp` / `118×162dp`) theo Mobile / PC-Tablet / TV.
     - **Trang chủ & Mục con:** Đồng bộ lề ngang `hPad` theo thiết bị (`14dp` Mobile / `18dp` Tablet-PC / `22dp` TV) trên cả 5 tab (`Home`, `Schedule`, `History`, `Favorites`, `Settings`), cân tỷ lệ Hero Banner (`186dp` / `232dp` / `248dp`), chiều rộng card Top 10 BXH (`136dp` / `156dp` / `172dp`) và chiều cao card lưới `MovieCardAdapter` (`148dp` / `192dp` / `198dp`).
     - **Trình phát (`PlayerActivity`):** Đồng bộ Drawer danh sách tập (`renderEpisodesDrawer`) theo thứ tự **mới nhất trước** (`Tập N -> Tập 1`) bằng `PlayerUtils.descendingEpisodeDisplayIndices` & `buildDescendingEpisodeRanges`, tự mở đúng trang chứa tập đang phát; cân chỉnh cụm nút giữa & thanh đáy riêng cho Điện thoại dọc/ngang, Giả lập PC/Tablet và TV.
- **Kiểm thử & Build:**
  - Bổ sung 2 test hồi quy vào `NguonCApiAndRepoTest.kt` (`mergeCatalogPages_replacesDeadPosterAndDeduplicatesSameTitleOrSlug`, `extractPosterUrl_ignoresDataUriAndResolvesRelativeLazyAttrs`).
  - `testDebugUnitTest`: **47/47 tests PASSED** (0 failures, 0 errors, 0 skipped); `lintDebug`: **BUILD SUCCESSFUL** (0 errors). *(Chưa kiểm thử trực tiếp trên thiết bị thật qua ADB ở phiên này).*

### [2026-11-27] - Phiên 47: Thiết Kế Lại Giao Diện Cài Đặt Dạng Danh Sách Phân Nhóm Trên Điện Thoại (`v3.5.7-v47`)
- **Yêu cầu người dùng:**
  1. Bố trí lại giao diện hiển thị các tác vụ trong mục **Trung tâm Cài đặt Hệ thống** trên thiết bị **Điện thoại** giống giao diện hiển thị của ảnh mẫu đính kèm (menu phân nhóm thẻ bo góc kèm tiêu đề nhóm, ô biểu tượng bo tròn trái, vạch phân cách mảnh thò đầu và mũi tên `›` bên phải; chạm vào mục để mở trang chi tiết có thanh quay lại `←`).
- **Thay đổi đã thực hiện:**
  1. **Điều hướng hai tầng cho Cài đặt trên Mobile (`MainActivity.kt`):**
     - Thêm trường `isSettingsDetailOpen: Boolean = false` vào `MainState`, thêm `closeSettingsDetail()` trên `AppHost` / `MainActivity`.
     - Khi chuyển sang tab `MainTab.SETTINGS` hoặc chạm lại biểu tượng `Cài đặt` khi đang ở trang con trên `MOBILE`, app đưa về danh sách chính `Thiết lập`; vẫn giữ cơ chế deep-link `setSettingsSubtab(SettingsSubtab.SOURCES)` (VD từ nút `Quản lý nguồn Web`) mở thẳng trang con tương ứng.
     - Cập nhật `onBackPressedDispatcher` để nhấn nút Back phần cứng trên Điện thoại khi đang xem chi tiết mục cài đặt sẽ quay về danh sách `Thiết lập` trước khi thoát ra Trang chủ.
  2. **Giao diện Menu Cài Đặt Phân Nhóm Liquid Glass (`SettingsFragment.kt`):**
     - Chỉ áp dụng trên `ResolvedDeviceProfile.MOBILE` (giữ nguyên thanh 5 pill ngang cho **Tablet / Giả lập PC** và **Android TV** để không làm gián đoạn D-Pad).
     - Khi ở danh sách gốc (`!isSettingsDetailOpen`): ẩn banner & thanh cuộn pill ngang, hiển thị thanh đầu `← Thiết lập` + huy hiệu `v3.5.7-v47`, chia 2 phân nhóm thẻ kính bo góc `18dp`:
       - **TRẢI NGHIỆM & PHÁT**: `Trình phát tích hợp`, `Giao diện`, `Tiện ích mở rộng`.
       - **HỆ THỐNG & DỮ LIỆU**: `Nguồn phim (+Web)`, `Cập nhật và Sao lưu`.
     - Mỗi hàng dùng ô biểu tượng VectorDrawable bo góc `36dp` (`navFeatureIconDrawable`), nhãn rõ nét, vạch chia `1px` inset (`marginStart 64dp`, màu `glassSubtleBorder`), mũi tên `ic_chevron_down` xoay `-90f` và hiệu ứng chạm gợn sóng + nhún nhẹ khi bật animation (`!reducedMotion`).
     - Khi mở mục chi tiết (`isSettingsDetailOpen == true`): hiển thị thanh `← <Tên mục>` ở trên cùng và tự cuộn lên đầu trang, đồng thời giữ nguyên trang đang mở khi người dùng bật/tắt công tắc hay đổi giao diện.
- **Kiểm thử & Build:**
  - `testDebugUnitTest`: **45/45 tests PASSED** (0 failures, 0 errors, 0 skipped); `lintDebug`: **BUILD SUCCESSFUL** (0 errors). *(Chưa kiểm thử giao diện trực tiếp trên thiết bị thật qua ADB ở phiên này).*

### [2026-11-27] - Phiên 46: Đồng Bộ Lưới `Lịch sử` Giống `Yêu thích`, Làm Nổi Bật & Hiệu Ứng Chạm Thanh Điều Hướng Kính, Chủ Đề `Hoàng Hôn Đêm Sâu` (`v3.5.6-v46`)
- **Yêu cầu người dùng:**
  1. Bố trí lại giao diện hiển thị phim mục **Lịch sử** giống giao diện hiển thị của mục **Yêu thích**.
  2. Trên nền thanh trong suốt Trang chủ, làm nổi bật 5 biểu tượng tính năng (**Khám phá - Lịch chiếu - Lịch sử - Yêu thích - Cài đặt**) và tạo hiệu ứng khi chạm.
  3. Đổi chủ đề **Đêm Cực Quang** thành màu chủ đạo **Vàng Hoàng Hôn dịu nhẹ** trên **nền tối đêm đen sâu**.
- **Thay đổi đã thực hiện:**
  1. **Giao diện `Lịch sử` đồng nhất với `Yêu thích` (`fragment_history.xml`, `HistoryFragment.kt`, `MovieCardAdapter.kt`):**
     - Chuyển `fragment_history.xml` sang `NestedScrollView` (`historyScroll`) + `rvHistory` và thay `HistoryAdapter` banner ngang cũ bằng **`MovieCardAdapter`** trên lưới `GridLayoutManager` (`3` cột Điện thoại, `4` cột Tablet/PC, `5` cột TV).
     - Hiển thị thẻ poster dọc đầy đủ badge chất lượng, tập, điểm rating, tim yêu thích, thanh tiến độ xem và dòng `Tập X • MM:SS (Y%)`; chạm vào thẻ phát tiếp ngay tại đúng phút/giây đang xem dở, nhấn giữ (`onLongSelect`) mở menu thao tác nhanh (*Tiếp tục phát*, *Chi tiết & Tập*, *Yêu thích*, *Xóa khỏi Lịch sử*).
  2. **Nổi bật 5 biểu tượng điều hướng & hiệu ứng chạm Liquid Glass (`activity_main.xml`, `ThemeManager.kt`, `MainActivity.kt`):**
     - Thêm `ThemeManager.navFeatureIconDrawable(...)` tạo viên kính mờ nổi bật riêng cho từng biểu tượng trên cả thanh điều hướng đáy (`navHome..navSettings`) và thanh trái TV (`tvRailHome..tvRailSettings`), đồng bộ với màu nhấn riêng từng tính năng khi chưa chọn và gradient rực sắc trắng khi đang kích hoạt.
     - Nâng cấp `ThemeManager.navTabDrawable(...)` bọc trong **`RippleDrawable`** gợn sóng trong suốt chuẩn API 23+ và bổ sung `ThemeManager.attachLiquidNavTapEffect(...)` tạo hiệu ứng nhấn đàn hồi (`OvershootInterpolator`) + bật sáng phóng nhẹ icon khi chạm (tự bỏ qua animation trên máy bật `reducedMotion`).
  3. **Chủ đề `Hoàng Hôn Đêm Sâu` (`Models.kt`, `ThemeManager.kt`, `colors.xml`):**
     - Cập nhật `ThemePreset.DARK` thành **`Hoàng Hôn Đêm Sâu`** với nền đêm đen sâu (`#040507` → `#0C0E14`), chữ ngà ấm (`#F7F3EA`), viền & nhấn **Vàng Hoàng Hôn dịu nhẹ** (`#E2AE58` → `#D1844B`), đồng thời đồng bộ viền focus trên TV theo màu vàng hoàng hôn khi chọn chủ đề này.
- **Kiểm thử & Build:**
  - `testDebugUnitTest`: **45/45 tests PASSED** (0 failures/errors/skipped); `lintDebug`: **BUILD SUCCESSFUL** (0 errors). *(Chưa chạy thử trực tiếp trên thiết bị thật qua ADB ở phiên này).*
  - APK mới nhất: `E:\Project\3_HoatHinh3D\HoatHinh3d.apk` (`13.80 MB`, `14,474,398 bytes`, `SHA-256: b1f26eb57521a13f2676aa85953c8ad30b35311acd57e84db1ccabcbfb91ba80`).

### [2026-11-27] - Phiên 45: Tập Mới Nhất Trước, Thể Loại Phim Thời Gian Thực, 3 Cột Điện Thoại, Tinh Gọn Giao Diện Cài Đặt & 3 Chủ Đề Dịu Nhẹ (`v3.5.5-v45`)
- **Yêu cầu người dùng (10 tác vụ):**
  1. Sắp xếp số tập trong Chi tiết phim theo thứ tự **tập mới nhất trước**.
  2. Thực thi tính năng mục **`Thể loại phim`** trên Trang chủ và đồng bộ thời gian thực theo nguồn Web các chủ đề: *Phim Hot, Phim Chiếu Rạp, Phim Lẻ, Phim Bộ, Phim Hoạt Hình, Phim Hành Động, Tình Cảm...*
  3. Chế độ Điện thoại tăng lên **3 hàng (3 cột)** hiển thị phim.
  4. Bỏ mục chỉnh **Độ trong suốt** (mặc định chung `52%`); bỏ mục chỉnh **Độ mờ** (mặc định chung `32%`); bỏ mục **Hiệu ứng nền** (mặc định chung **Bật**).
  5. Bỏ mục **Hệ thống** trong **Chế độ Sáng / Tối & Vật liệu**; bỏ mục **Tùy biến bố cục Trang chủ** trong **Giao diện**.
  6. Bỏ chủ đề **Xanh Băng (`AQUA`)**, tinh chỉnh **3 chủ đề còn lại** (`Ngọc Trai Dịu Nhẹ`, `Anh Đào Phấn Nhạt`, `Đêm Cực Quang Dịu`) dịu mắt và thanh thoát hơn.
  7. Tối ưu bố cục Trang chủ, các mục con và Trình phát cho Điện thoại, Giả lập PC và Android TV.
- **Thay đổi đã thực hiện:**
  1. **Sắp xếp tập mới nhất trước trong Chi tiết phim (`PlayerHelpers.kt`, `MovieDetailActivity.kt`):**
     - Bổ sung `PlayerUtils.descendingEpisodeDisplayIndices` & `PlayerUtils.buildDescendingEpisodeRanges` sắp xếp tập giảm dần (`Tập N -> Tập 1`) cùng thanh phân trang `50 tập/trang` (`Tập 120 - 71`, `Tập 70 - 21`, ...), giữ nguyên thứ tự phát tiến `Tập N -> N+1` trong `PlayerActivity`.
  2. **Thực thi `Thể loại phim` tương tác & đồng bộ Web thời gian thực (`activity_main.xml`, `MainActivity.kt`, `HomeFragment.kt`, `MovieCardAdapter.kt`, `YanHh3dNowScraper.kt`, `YanHh3dScraper.kt`, `MovieRepository.kt`):**
     - Chạm nút `Thể loại phim ▾` trên Trang chủ mở bảng **Liquid Glass Thể Loại Phim** (`floatingGenrePickerOverlay`, 2 cột Mobile / 3 cột Tablet-PC / 4 cột TV) kèm trạng thái `"🟢 Đồng bộ từ <Nguồn> • HH:mm:ss"` và nút `↻ Đồng bộ` ngay.
     - Đưa 7 chủ đề chuẩn (*Phim Hot, Phim Chiếu Rạp, Phim Lẻ, Phim Bộ, Phim Hoạt Hình, Phim Hành Động, Tình Cảm*) + toàn bộ danh mục HTML/JSON từ nguồn Web vào `YanHh3dNowScraper`, `YanHh3dScraper`, `CustomSourceParser` & `MovieRepository.resolveNowTopicPage` / `resolveYanTopicPage` (TTL 90 giây + tự đồng bộ lại cả chuyên mục đang chọn trong chu kỳ 60s).
  3. **3 cột Điện thoại & 4 cột Tablet/PC (`DeviceProfileResolver.kt`, `MovieCardAdapter.kt`, `HomeFragment.kt`):**
     - `gridSpanCount`: `MOBILE = 3`, `TABLET = 4`, `TV = 5`. Tối ưu kích thước card trên lưới 3 cột điện thoại (`posterFrame` `146dp`, margin `3dp`, padding `6dp`, chữ & badge `11.5sp`/`8.5sp`, nút tim `28dp`) để 3 cột thẳng hàng gọn đẹp không chồng chữ.
  4. **Tinh gọn Cài đặt Giao diện & 3 Chủ đề dịu nhẹ (`Models.kt`, `PrefsStore.kt`, `ThemeManager.kt`, `colors.xml`, `bg_orb_*.xml`, `SettingsFragment.kt`):**
     - Bỏ `AQUA` khỏi `ThemePreset`, tinh chỉnh `PEARL`, `SAKURA`, `DARK` sang tông sương mai dịu mắt (`#F6F8FC`/`#5C6BC0`, `#FDF7F9`/`#C9648A`, `#0A0F1A`/`#6E7CF2`); ẩn tùy chọn `Hệ thống` khỏi nút chọn Sáng/Tối.
     - Ẩn toàn bộ thanh kéo Độ trong suốt, Độ mờ, switch Giọt sáng nền và khối Tùy biến bố cục Trang chủ trong `SettingsFragment.renderAppearanceTab`; áp dụng migration `APPEARANCE_DEFAULTS_V45` đưa mặc định chung về `glassOpacity = 52`, `glassBlur = 32`, `ambientOrbs = true`, `showHomeHero/Continue/Trending = true`.
  5. **Tối ưu bố cục đa thiết bị (`ScheduleFragment.kt`, `item_schedule_movie.xml`, `FavoritesFragment.kt`, `MovieDetailActivity.kt`, `PlayerActivity.kt`):**
     - `ScheduleFragment` & `rvOfflineEpisodes` dùng `GridLayoutManager` `2` cột trên Tablet/PC/TV (`1` cột Mobile); `PlayerActivity.applyResponsivePlayerBars` cân chỉnh tỷ lệ nút Play/Tua giữa màn hình & thanh 6 nút đáy cho Điện thoại dọc, Ngang/PC và Android TV.
- **Kiểm thử & Build:**
  - `testDebugUnitTest`: **45/45 tests PASSED** (`NguonCApiAndRepoTest` 14, `PlayerHelpersTest` 9, `PrefsStoreTest` 7, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 6, `YanHh3dScraperTest` 6 — 0 failures/errors/skipped).
  - `lintDebug`: **BUILD SUCCESSFUL** (0 errors). *(Chưa kiểm thử trực tiếp trên TV/điện thoại qua ADB từ PC ở phiên này).*
  - APK mới nhất: `E:\Project\3_HoatHinh3D\HoatHinh3d.apk` (`13.80 MB`, `14,471,602 bytes`, `SHA-256: 14b98c99da8ff0e1ba82ce2f2e7c1420df338c89d5a53c8dcd4be284a6706de1`).

### [2026-11-27] - Phiên 44: Sửa Triệt Để Lỗi Văng App Sau 1s Trên Xiaomi MiTV4-ANSM0 (`coreLibraryDesugaring` Cho `Jsoup 1.18.3`, Root CA Sectigo E46/R46, Giảm Tải HWUI Mali-450 & Bảo Vệ `Throwable`)
- **Phân tích video thực tế (`video_2026-11-27_17-26-33.mp4`) & truy vết bytecode DEX (`dexdump`):**
  - Video quay trực tiếp trên **Xiaomi MiTV4-ANSM0** (Android 6.0.1 API 23, `armeabi-v7a` 32-bit, Amlogic 4×Cortex-A53 @ 1.5 GHz, Mali-450 MP, RAM 1.6 GB) cho thấy app mở lên màn hình trắng khởi động ~2.5–3s (`00:02`–`00:04`), dựng xong giao diện Trang chủ (`00:05`) với dữ liệu `InitialData`, rồi **văng đột ngột ra Launcher TV ngay sau ~1s (`00:06`)** ở lần gọi dữ liệu mạng đầu tiên (`YanHh3dNowScraper` nhận HTML từ `yanhh3d.now`).
  - Quét `dexdump.exe` toàn bộ 14 file `.dex` trong APK `v43` phát hiện nguyên nhân gốc rễ: dự án chưa bật `coreLibraryDesugaringEnabled true`, trong khi `org.jsoup:jsoup:1.18.3` (`Collector`, `Element`, `StringUtil`, `StreamParser`, `Safelist`), Guava trong Media3 (`CollectCollectors`), `androidx.core.view` (`java.time.Duration`) và `kotlin.*jdk8` gọi trực tiếp API Java 8+ / Android API 24+ (`java.util.stream.Stream`, `Collector`, `Collectors`, `java.util.Optional`, `Map.computeIfAbsent`). Android 6.0.1 (API 23) ném `NoClassDefFoundError` (`java.lang.Error` — lọt qua mọi `catch (Exception)`) ngay khi `Jsoup.parse().select(...)` thực thi sau ~1s, làm app sập tức thì trên TV mà không kịp ghi nhận vào nhánh catch thông thường.
  - Ngoài ra kiểm tra chuỗi chứng chỉ SSL thực tế trên `github.com` cho thấy GitHub đang dùng `CN=Sectigo Public Server Authentication Root E46` (chưa có trong 15 Root CA cũ của `modern_roots.pem`), dễ gây lỗi SSL trên Android 6.0.1 khi kiểm tra OTA.
- **Thay đổi đã thực hiện:**
  1. **Bật Core Library Desugaring cho API 23 (`android/app/build.gradle`):**
     - Bật `compileOptions { coreLibraryDesugaringEnabled true }`, thêm `coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.1.4"`, nâng `versionCode` `43` → **`44`**, `versionName` `"3.5.4-v44"`.
     - Kiểm tra lại toàn bộ file `.dex` trong `HoatHinh3d.apk` bằng `dexdump.exe`: **9,180** lệnh gọi đã được D8 rewrite sang `j$.util.*` / `j$.time.*` (chỉ còn 18 hit nằm bên trong lớp cầu nối nội bộ `Lj$/...Conversions` của chính D8; bằng **0** lời gọi `java.util.stream`/`java.util.Optional`/`Map.computeIfAbsent` thiếu desugar ở Jsoup/Media3/AndroidX/Kotlin).
  2. **Bổ sung Root CA GitHub OTA (`android/app/src/main/res/raw/modern_roots.pem`):**
     - Nạp thêm `CN=Sectigo Public Server Authentication Root E46` và `CN=Sectigo Public Server Authentication Root R46` (tổng cộng **17 Root CA** trong `CompatTls`) để tải `version.json` & `HoatHinh3d.apk` từ GitHub ổn định trên Android 6.0.1.
  3. **Giảm tải GPU Mali-450 & RAM 1.6 GB trên `MiTV4-ANSM0` (`styles.xml`, `ThemeManager.kt`, `MovieCardAdapter.kt`, `HomeFragment.kt`):**
     - Đặt `android:windowBackground="@color/pearl_bg_primary"` cho `AppTheme` & `AppTheme.NoActionBar` để giảm nháy màn hình trắng lúc khởi động lạnh.
     - Thêm `liteRender: Boolean = false` trong `ThemeManager.glassPanelDrawable(...)`: bỏ lớp `specularSheenLayer` thứ 2 cho thẻ card khi bật `reducedMotion`, giảm một nửa overdraw HWUI trên GPU Mali-450.
     - Bỏ `applyRoundedOutline` (`clipToOutline`) cho `MovieCardAdapter` & `ContinueWatchingAdapter` trên máy `isUltraLowRamDevice`, đồng thời giới hạn `sharedTvCardPool` còn tối đa `6` view tái chế, cache `rvHomeFeed`/`rvTrending` còn `2` (`setHasFixedSize(true)` cho `rvTrending`/`rvGenres`), và thu gọn `tvCarouselsContainer` còn `1` hàng (`5` phim) trên máy ultra-low RAM thay vì `3` hàng (`18` card).
  4. **Chống sập tiến trình từ lỗi nền (`MainActivity.kt`, `YanHh3dNowScraper.kt`, `AppUpdateChecker.kt`):**
     - Mở rộng bắt `Throwable` (vẫn `throw` lại `CancellationException`) ở các luồng tải feed, tìm kiếm, lấy tập/thể loại và kiểm tra OTA; lùi thời điểm kiểm tra Auto-Update lúc khởi động từ `2800ms` → `4500ms` để nhường đủ băng thông pool `4` request/host của MiTV4 cho Trang chủ & poster.
- **Kiểm thử & Build:**
  - `testDebugUnitTest`: **43/43 tests PASSED** (0 failures/errors/skipped); `lintDebug`: **BUILD SUCCESSFUL** (0 errors); `dexdump` sau build xác nhận `9180` lời gọi `j$.util.*` desugared sạch sẽ. *(Chưa kết nối ADB trực tiếp tới MiTV4-ANSM0 trên PC để chạy thử runtime sau sửa; cần xác nhận trực tiếp trên TV).*
  - APK mới nhất: `E:\Project\3_HoatHinh3D\HoatHinh3d.apk` (`13.79 MB`, `14,457,442 bytes`, `SHA-256: 70f02511c53ddafa147fd9691baff8e0702bef6ce0e386faeb75f753843fc227`).

### [2026-11-27] - Phiên 43: Tinh Gọn UI Cài Đặt, Đổi Tên Gói APK Thành `HoatHinh3d.apk` & Tối Ưu Toàn Diện Hiệu Năng / Vòng Đời App
- **Yêu cầu người dùng:**
  - Không hiển thị nút `Nhật ký lỗi` và nút/link `URL cập nhật` trong `4. Cập nhật & Sao lưu`.
  - Tối ưu lại tất cả các tính năng của app một cách hoàn chỉnh nhất.
  - Đổi tên gói APK xuất ra từ `HoatHinh3D-Universal-debug.apk` thành **`HoatHinh3d.apk`**.
- **Thay đổi đã thực hiện:**
  1. **Tinh gọn tab `4. Cập nhật & Sao lưu` & dọn dead code (`SettingsFragment.kt`, `MainActivity.kt`):**
     - Gỡ nút `📋 Nhật ký lỗi (...)` và `⚙ URL cập nhật` khỏi lưới nút hệ thống (chỉ giữ 3 nút gọn đẹp: `🔄 Kiểm tra hệ thống`, `🗑 Dọn Cache`, `⬆ Kiểm tra cập nhật`).
     - Gỡ hiển thị chuỗi URL thô dưới switch `Auto Update App khi có bản mới`, xóa method thừa `configureUpdateSourceDialog()` khỏi `AppHost` / `MainActivity` và tinh chỉnh thông báo lỗi nguồn không còn nhắc tới "Nhật ký".
  2. **Đổi tên gói xuất bản chính thức thành `HoatHinh3d.apk` (`build-apk.ps1`, `publish-ota.ps1`, `AppUpdateChecker.kt`, `android/app/build.gradle`):**
     - Nâng `versionCode` `42` → **`43`**, `versionName` `"3.5.3-v43"`.
     - `build-apk.ps1` xuất APK ra `E:\Project\3_HoatHinh3D\HoatHinh3d.apk` (và tự dọn các file `HoatHinh3D-Universal-*.apk` cũ ở gốc).
     - `publish-ota.ps1` tải asset `HoatHinh3d.apk` lên GitHub Releases và ghi `apkUrl` tương ứng vào `version.json`.
  3. **Tối ưu hiệu năng, bộ nhớ & vòng đời toàn app (`HomeFragment.kt`, `MovieCardAdapter.kt`, `MainActivity.kt`, `PlayerActivity.kt`):**
     - **Trang chủ không nháy ảnh (`MovieCardAdapter` + `HomeFragment`):** Bổ sung `PAYLOAD_META` cập nhật riêng tim Yêu thích / thanh tiến độ xem / badge BXH mà không gọi `notifyDataSetChanged()` tải lại poster Glide; cache drawable huy hiệu Top BXH & `ContinueWatchingAdapter` (`applyRoundedOutline` chỉ chạy 1 lần trong `onCreateViewHolder`).
     - **Chống ANR & rò rỉ vòng đời (`MainActivity`):** Hủy toàn bộ `searchJob`, `genreJob`, `loadMoreJob`, `realtimeTrendingJob` và `mainHandler` khi `onDestroy()`; chuyển `HlsCacheProvider.clearCache` & `cleanupOldUpdates` sang luồng nền khi thoát app.
     - **Trình phát tối ưu pin & TV RAM thấp (`PlayerActivity`):** Tạm dừng/khôi phục `progressTicker` theo `onPause()`/`onResume()`; giữ nguyên giới hạn `1080p` trên máy RAM thấp khi chọn lại chất lượng `Tự động (ABR)`; tự đánh dấu đã xem + lưu tiến độ ngay tại `STATE_ENDED`.
- **Kiểm thử & Build:**
  - `testDebugUnitTest`: **43/43 tests PASSED** (0 failures/errors/skipped); `lintDebug`: **BUILD SUCCESSFUL** (0 errors).
  - APK mới nhất: `E:\Project\3_HoatHinh3D\HoatHinh3d.apk` (`12.69 MB`, `13,310,209 bytes`, `SHA-256: fca306d5e737cdc8b89064a648b98ee3c96cb23e8e4fc20c63c79fee09ee7164`).

### [2026-11-27] - Phiên 42: Xuất Bản Bản Kiểm Thử Nâng Cấp OTA (`v3.5.2-v42`, `versionCode 42`)
- **Mục đích:** Tạo bản phát hành mới hơn (`versionCode 42 > 41`) trên GitHub Releases + `version.json` để người dùng cài bản `v3.5.1-v41` (`HoatHinh3D-Universal-v41-install-test.apk`) và kiểm thử trực tiếp luồng tự động kiểm tra → tải → xác thực SHA-256 → nâng cấp OTA lên `v3.5.2-v42`.
- **Thay đổi:**
  - Nâng `versionCode` từ `41` → **`42`**, `versionName` → **`"3.5.2-v42"`** trong `android/app/build.gradle`.
  - Lưu bản cài thử `v3.5.1-v41` tại `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-v41-install-test.apk` (`13,311,097 bytes`, `SHA-256: 0aab55162af61e4c70abce354eded6b1060f93738e1a6d9cbc9770a3124da2a4`).
- **Kiểm thử & Build:**
  - `testDebugUnitTest`: **43/43 tests PASSED** (0 failures/errors/skipped); `lintDebug`: **BUILD SUCCESSFUL** (0 errors).
  - APK mới nhất (`v42`): `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (`12.69 MB`, `13,311,091 bytes`, `SHA-256: 8439544e6517625582bde777d3cc6f54aa2faca644422442b442482fe247d2ce`).

### [2026-11-27] - Phiên 41: Thiết Lập Luồng Cập Nhật OTA Tự Động Qua GitHub (Default Manifest + Fallback Releases API + Tương Thích Android 6.0.1 API 23)
- **Vấn đề / Yêu cầu:**
  - Thiết lập hoàn chỉnh tính năng cập nhật OTA trực tiếp trên app: mặc định trỏ tới kho Git chính thức `https://github.com/long-1285/Hoathinh3D`, nhấn **Kiểm tra cập nhật** tự kiểm tra phiên bản mới nhất trên Git, so sánh `versionCode` / `versionName`, tự tải về xác thực SHA-256 & mở cài đặt.
  - Bổ sung quy tắc bắt buộc cuối mỗi phiên debug phải xuất bản APK lên GitHub Release và cập nhật manifest OTA.
- **Thay đổi đã thực hiện:**
  1. **Mặc định nguồn OTA GitHub & chống cache CDN (`AppUpdateChecker.kt`, `Models.kt`, `PrefsStore.kt`):**
     - Đặt `DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/long-1285/Hoathinh3D/main/version.json"` và `GITHUB_RELEASES_LATEST_URL = "https://api.github.com/repos/long-1285/Hoathinh3D/releases/latest"`.
     - `AppSettings.updateManifestUrl` mặc định trỏ tới `DEFAULT_MANIFEST_URL`; các máy nâng cấp từ bản cũ có giá trị rỗng `""` tự động migrate qua `AppUpdateChecker.resolveEffectiveManifestUrl`.
     - Thêm `withCacheBuster` (`?t=<timestamp>`) + header `Cache-Control: no-cache` để bỏ qua cache 5 phút của CDN `raw.githubusercontent.com` ngay khi vừa push bản mới; tự fallback sang `GITHUB_RELEASES_LATEST_URL` nếu `version.json` lỗi; hỗ trợ đọc `versionCode` / `build` từ body GitHub Release.
  2. **Tải APK an toàn, tương thích Android 6.0.1 (`MiTV4-ANSM0`) & Android 8–15 (`AppUpdateChecker.kt`, `MainActivity.kt`, `SettingsFragment.kt`):**
     - `downloadApkToCache` tách client với timeout tải lớn (`connectTimeout 15s`, `readTimeout 60s`, `callTimeout 5m`), kiểm tra magic header ZIP `'P''K'` (`isValidApkArchive`), cập nhật tiến độ `%` và dung lượng `MB` theo thời gian thực.
     - Trên Android `< 7.0` (API 23 như **Xiaomi MiTV4-ANSM0**), lưu APK tải về vào `externalCacheDir/updates` + `setReadable(true, false)` để `PackageInstaller` (`file://`) đọc được không bị lỗi quyền `/data/user/0/.../cache`; Android `7.0+` dùng `FileProvider`. Tự dọn tệp APK cũ khi khởi động (`cleanupOldUpdates`).
     - Trên Android `8.0+`, lưu `pendingInstallManifest` khi chuyển sang màn hình cấp quyền *"Cài đặt ứng dụng không rõ nguồn gốc"* và tự tiếp tục tải/cài trong `onResume()` ngay khi người dùng quay lại. Nút cấu hình URL đổi sang nút **Mặc định Git** tiện khôi phục.
  3. **Nâng cấp quy trình xuất bản (`android/app/build.gradle`, `publish-ota.ps1`, `version.json`):**
     - Nâng `versionCode` từ `35` → **`41`**, `versionName` → **`"3.5.1-v41"`**.
     - Tạo script `publish-ota.ps1` tự đọc `versionCode` / `versionName`, băm `SHA-256`, tạo/cập nhật GitHub Release tag `v<versionCode>`, upload `HoatHinh3D-Universal-debug.apk` và xuất `version.json` (lấy xác thực từ `GH_TOKEN`/`gh`/`git-credential-manager` trong RAM, không bao giờ lưu token vào file).
- **Kiểm thử & Build:**
  - `testDebugUnitTest`: **43/43 tests PASSED** (`NguonCApiAndRepoTest` 14, `PlayerHelpersTest` 8, `PrefsStoreTest` 7, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures/errors/skipped).
  - `lintDebug`: **BUILD SUCCESSFUL** (0 errors).
  - APK: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (`12.69 MB`, `13,311,097 bytes`, `SHA-256: 0aab55162af61e4c70abce354eded6b1060f93738e1a6d9cbc9770a3124da2a4`).

### [2026-11-27] - Phiên 40: Khắc Phục Văng App & Tối Ưu Toàn Diện Cấu Hình Cho Xiaomi MiTV4-ANSM0 (Android 6.0.1 API 23, `armeabi-v7a` 32-bit, 1.6 GB RAM)
- **Vấn đề / Yêu cầu (theo 3 ảnh minh họa thiết bị thực tế):**
  - Cài vào TV **Xiaomi MiTV4-ANSM0** (*Android 6.0.1 • API 23 • MHC19J • bản vá 01/04/2017 • Kernel 3.14.29*, Chip *Amlogic 4×Cortex-A53 @ 1.5 GHz* hạ xung `70°C`, kiến trúc *`armeabi-v7a` 32-bit*, GPU *Mali-450 MP*, RAM *1.6 GB* còn trống *1.1 GB*, ổ đĩa *5.0 GB* còn trống *2.1 GB*, màn hình *1920×1080 @ 60 Hz*, `xhdpi 2.0`, *Widevine L3 SD*, *H.265*) bị **văng app (crash)** và không hoạt động ổn định.
- **Nguyên nhân cốt lõi đã phát hiện qua Android Lint (`lintDebug`) & đối chiếu API 23 / RAM 1.6 GB:**
  1. **3 lỗi `NewApi` gây `NoClassDefFoundError` / `NoSuchMethodError` trên Android 6.0.1 (API 23):**
     - `YanHh3dNowScraper.decodeStreamrptObf()` gọi `java.util.Base64.getDecoder()` (yêu cầu **API 26+**).
     - `YanHh3dScraper.fetchMovieDetail()` & `CustomSourceParser.parseCategoriesFromApiJson()` gọi `Map.putIfAbsent(...)` (yêu cầu **API 24+**).
  2. **Nguy cơ `InflateException` trên ROM Android 6.0.1 với `vectorDrawables.useSupportLibrary = true`:**
     - `btnHeroPlay`, `btnHeroDetail` ([layout_home_header.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/layout_home_header.xml)) và `btnDetailPlay` ([activity_movie_detail.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_movie_detail.xml)) gắn `android:drawableStart` trỏ trực tiếp vào VectorDrawable trên `<Button>` (`MaterialButton`); đồng thời `item_continue_card.xml` vẫn còn ký tự `▶` gây ô vuông `▯`.
  3. **Áp lực RAM 32-bit `1.6 GB` & CPU `1.5 GHz` (hạ xung `70°C`):**
     - `tvRowAdapters` trong `HomeFragment` thiếu `reducedMotion = isLite`; `ContinueWatchingAdapter`, `HistoryAdapter`, `ScheduleMovieAdapter`, `OfflineEpisodeAdapter` nạp ảnh không có `.override(...)` / `RGB_565`; `HttpClientProvider` mở tới `48` request song song; `InstantLoadControl` đặt buffer `34 MB` & `HlsCacheProvider` prefetch 6 MB segment trên TV `1.6 GB`.
- **Giải pháp đã triển khai:**
  1. **Xử lý sạch 100% lỗi API 23 (`0 errors` trên `lintDebug`) & tương thích VectorDrawable ([YanHh3dNowScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dNowScraper.kt), [YanHh3dScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dScraper.kt), [CustomSourceParser.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/CustomSourceParser.kt), [layout_home_header.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/layout_home_header.xml), [activity_movie_detail.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_movie_detail.xml), [item_continue_card.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/item_continue_card.xml), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):**
     - Viết `decodeBase64Compat()` thuần Kotlin (hỗ trợ cả URL-safe & thiếu padding) thay `java.util.Base64`; thay `putIfAbsent` bằng `if (!containsKey(...))`.
     - Chuyển `btnHeroPlay`, `btnHeroDetail`, `btnDetailPlay` sang `LinearLayout` + `ImageView(app:srcCompat)` + `TextView`; thay nút `▶` trong `item_continue_card.xml` bằng VectorDrawable; bật `AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)` và đổi `Switch` sang `SwitchCompat`.
  2. **Cấu hình riêng cho thiết bị Ultra-Low RAM `<= 1800 MB` (`MiTV4-ANSM0` 1.6 GB) ([DeviceProfileResolver.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/DeviceProfileResolver.kt), [HoatHinhApp.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/HoatHinhApp.kt), [HttpClientProvider.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/HttpClientProvider.kt), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [MovieCardAdapter.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/common/MovieCardAdapter.kt), [HistoryFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/history/HistoryFragment.kt), [ScheduleFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/schedule/ScheduleFragment.kt), [FavoritesFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/favorites/FavoritesFragment.kt)):**
     - Thêm `isUltraLowRamDevice()` (`<= 1800 MB`), `onTrimMemory()` / `onLowMemory()` xả Glide memory, và bộ ghi crash chưa bắt (`hh3d_diag_crash`) tự nạp vào `AppDiagnostics` lần mở sau.
     - Giới hạn OkHttp `maxRequests = 12` / `maxRequestsPerHost = 4` / `ConnectionPool(4)` trên `<= 1800 MB` (giảm nhiệt & native memory 32-bit); chia sẻ `sharedTvCardPool`, giảm `setItemViewCacheSize(4)`, giới hạn TV row `6` phim, đồng bộ `reducedMotion` & ép `RGB_565` + `override(...)` + `dontAnimate()` cho tất cả adapter ảnh.
  3. **Tối ưu Media3 ExoPlayer & Lưu trữ 5 GB ([InstantLoadControl.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/InstantLoadControl.kt), [HlsCacheProvider.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/HlsCacheProvider.kt), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [AndroidManifest.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/AndroidManifest.xml)):**
     - Giảm buffer `InstantLoadControl` xuống `20 MB` (`maxBufferMs 20s`, `backBufferMs 12s`) và `SimpleCache` xuống `64 MB` trên `<= 1800 MB`; tắt prefetch phân đoạn nặng khi đang phát trên MiTV4; bật `DefaultRenderersFactory(this).setEnableDecoderFallback(true)` cho chip Amlogic H.265/H.264; thêm `<uses-feature android:name="android.hardware.wifi" android:required="false" />`.
- **Kiểm tra thực tế:**
  - `.\gradlew.bat lintDebug`: **BUILD SUCCESSFUL** (0 errors; đã hết toàn bộ 4 lỗi `NewApi` trước đó).
  - Mở rộng unit tests trong [YanHh3dNowScraperTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/YanHh3dNowScraperTest.kt) (URL-safe Base64 không padding) & [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt) (`isUltraLowRamMb`).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` (không tạo Release R8 APK):
    - `compileDebugKotlin`: **0 errors, 0 warnings**.
    - `testDebugUnitTest`: **43/43 unit tests PASS** (`NguonCApiAndRepoTest` 14, `PlayerHelpersTest` 8, `PrefsStoreTest` 7, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **13.80 MB** (`14,470,362 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).

### [2026-11-27] - Phiên 39: Gỡ "Dọn Bộ Nhớ Đệm" Khỏi Trình Phát Tích Hợp, Đổi Nhãn "Thể Loại Phim" & Đồng Bộ Thời Gian Thực Danh Mục Từ Nguồn Web
- **Vấn đề / Yêu cầu (theo ảnh minh họa):**
  1. Bỏ nút **`🗑 Dọn bộ nhớ đệm phát HLS`** bên trong tab **`1. Trình phát tích hợp`** (chức năng dọn cache giữ tập trung ở tab `4. Cập nhật & Sao lưu`).
  2. Trên thanh thể loại của **Trang chủ**, đổi nhãn đầu tiên từ **`Tất cả (Mới cập nhật)`** / **`Tất cả thể loại`** / **`Tất cả (...)`** thành **`Thể loại phim`**.
  3. Cập nhật **đồng bộ theo thời gian thực** danh mục tính năng/thể loại trực tiếp từ nguồn web đang chọn (bao gồm *Phim Hot*, *Phim Chiếu Rạp*, *Phim Lẻ*, *Phim Bộ*, *Phim Hoạt Hình*, *Phim Hành Động*, *Tình Cảm*, ...) cho cả nguồn mặc định và mọi nguồn Web tự thêm (`+Web`).
- **Giải pháp đã triển khai:**
  1. **Gỡ nút dọn bộ nhớ đệm khỏi `1. Trình phát tích hợp` ([SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):**
     - Xóa nút `styledButton("🗑 Dọn bộ nhớ đệm phát HLS ($cacheMb)")` trong `renderPlayerTab()`.
  2. **Đổi nhãn đầu chip Trang chủ sang `"Thể loại phim"` ([InitialData.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/InitialData.kt), [YanHh3dNowScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dNowScraper.kt), [YanHh3dScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dScraper.kt), [CustomSourceParser.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/CustomSourceParser.kt), [MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt)):**
     - Cập nhật `label = "Thể loại phim"` (giữ nguyên `id = "Tất cả"` cho bộ lọc nội bộ) và chuẩn hóa qua `MovieRepository.normalizeLeadingGenre()`.
  3. **Đồng bộ thời gian thực danh mục từ nguồn Web ([YanHh3dNowScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dNowScraper.kt), [YanHh3dScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dScraper.kt), [CustomSourceParser.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/CustomSourceParser.kt), [MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Mở rộng `YanHh3dNowScraper.parseCategoriesFromHtml()` nhận cả đường dẫn `/danh-sach/`, `/loai-phim/`, `/quoc-gia/`, `/phim-*`, `/top-*`, và thêm `forceRefresh` cho `YanHh3dNowScraper.fetchCategories()` & `YanHh3dScraper.fetchTaxonomies()`.
     - Xây dựng `CustomSourceParser.parseCategoriesFromHtml()`, `parseCategoriesFromApiJson()`, `fetchCategories()`, và `fetchMoviesByCategory()` cho nguồn Web/API bên ngoài (HTML portal có *Phim Hot*, *Phim Chiếu Rạp*, *Phim Lẻ*, *Phim Bộ*, *Phim Hoạt Hình*, *Phim Hành Động*, *Tình Cảm*, ..., PhimAPI/KKPhim JSON `/the-loai`, NguonC, WP/Laravel), hỗ trợ phân trang danh mục khi bấm chip trên Trang chủ.
     - Giảm TTL cache danh mục xuống **5 phút (`CAT_CACHE_TTL_MS`)**, và trong `MainActivity.loadLiveDonghuaFeed()` + vòng đồng bộ thời gian thực 60s `syncRealtimeTrendingNow()` luôn gọi `MovieRepository.fetchCategoriesForSource(sourceId, forceRefresh = true)` để cập nhật chip Trang chủ ngay khi nguồn Web thay đổi.
- **Kiểm tra thực tế:**
  - Bổ sung unit test `webSourceCategories_renameFirstChipToTheLoaiPhim_andSyncLiveWebCategories` trong [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` (không tạo Release R8 APK):
    - `testDebugUnitTest`: **43/43 unit tests PASS** (`NguonCApiAndRepoTest` 14, `PlayerHelpersTest` 8, `PrefsStoreTest` 7, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **13.80 MB** (`14,470,314 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).

### [2026-11-27] - Phiên 38: Tham Vấn So Sánh zFim (`zitfim.apk`), Xác Thực SHA-256 Auto Update, Làm Gọn Cài Đặt Đa Cột + Bật/Tắt Mục Trang Chủ, Preview Storyboard Khi Tua & Bỏ Hiện Thị Nạp Phim Khởi Động
- **Vấn đề / Yêu cầu:**
  1. Bỏ hiển thị nạp phim khi khởi động app (banner `"⬇ Đang kết nối cụm kho hoạt hình 3D..."` và thông báo `"Đã nạp X phim từ nguồn..."` lúc mới mở app).
  2. Chắt lọc & triển khai 3 nâng cấp từ phân tích `zFim` (`zitfim.apk`):
     - Kiểm tra mã băm **`SHA-256`** khi Auto Update tải APK về trước khi cài đặt.
     - Làm gọn & trau chuốt bố cục **Cài đặt** (`SettingsFragment`: lưới nút đều hàng, công tắc 2 cột gọn gàng trên TV/Tablet) và thêm công tắc **tùy biến ẩn/hiện các mục Trang chủ** (*Banner Hero*, *Tiếp tục xem*, *Top 10 BXH Thịnh Hành*).
     - Khung xem trước **Storyboard / Thumbnail Preview** nổi ngay trên `SeekBar` (và trên `seekPreviewPill` khi có VTT sprite) trong lúc tua phim trên `PlayerActivity`.
- **Giải pháp đã triển khai:**
  1. **Bỏ hiển thị nạp phim khi khởi động ([HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Giữ `txtLoadingFeedBanner` luôn `View.GONE` và thêm tham số `silentOnSuccess = true` cho `loadLiveDonghuaFeed(initialSource, silentOnSuccess = true)` trong `MainActivity.onCreate()` để không hiện banner hay toast nạp phim lúc mở app (vẫn giữ thông báo khi người dùng tự bấm chuyển nguồn).
  2. **Xác thực toàn vẹn `SHA-256` cho Auto Update ([AppUpdateChecker.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/AppUpdateChecker.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - `UpdateManifest` bổ sung `sha256`; `parseManifestJson()` đọc thêm `"sha256"` / `"apkSha256"` / `"hash"` hoặc chuỗi `SHA-256: <64-hex>` trong release body GitHub; bổ sung `computeSha256(file)` & `verifySha256(file, expectedHex)`.
     - `MainActivity.startDownloadAndInstallUpdate()` kiểm tra SHA-256 sau khi tải xong APK; nếu sai checksum sẽ tự xóa tệp lỗi, hiện cảnh báo bảo mật và hủy mở trình cài đặt.
  3. **Làm gọn Cài đặt đa cột & Tùy biến Trang chủ ([Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt)):**
     - Bổ sung helper `buttonGrid()` (chia nút đều cột, cắt gọn text) và `addResponsiveRows()` (sắp 2 cột trên Tablet/TV, 1 cột trên Phone) áp dụng xuyên suốt `renderPlayerTab`, `renderAppearanceTab`, `renderBackupTab`, `renderExtensionsTab`.
     - Thêm `showHomeHero`, `showHomeContinue`, `showHomeTrending` vào `AppSettings` + `PrefsStore`, thêm card **"Tùy Biến Bố Cục Trang Chủ"** trong tab `3. Giao diện`, và áp dụng ẩn/hiện tức thì trong `HomeFragment.bindHeaderContent()`.
  4. **Storyboard / Thumbnail Preview khi tua trên Player ([PlayerHelpers.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerHelpers.kt), [activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt)):**
     - Tạo `StoryboardCue` & `StoryboardParser` (`parseVtt` hỗ trợ `#xywh=x,y,w,h` và URL tương đối, tìm kiếm nhị phân `findCueAt`, định dạng `PlayerUtils.formatSeekDelta`).
     - Thêm thẻ nổi `seekThumbCard` (`164dp`, ảnh sprite `#xywh` cắt `RGB_565` qua `LruCache(2)` hoặc poster/thumb tập phim fallback qua Glide, giờ tua `#2CE59B`, delta `±XXs`, tự trượt theo vị trí thumb `SeekBar` và hỗ trợ D-Pad/vuốt ngang qua `imgSeekPreviewCenter`).
- **Kiểm tra thực tế:**
  - Cập nhật & bổ sung unit tests trong [PrefsStoreTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt), [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt), [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` (không tạo Release R8 APK):
    - `testDebugUnitTest`: **42/42 unit tests PASS** (`NguonCApiAndRepoTest` 13, `PlayerHelpersTest` 8, `PrefsStoreTest` 7, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **13.80 MB** (`14,470,314 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).

### [2026-11-27] - Phiên 37: Gỡ Tab "Tài Khoản", Đồng Bộ Qua Tài Khoản Email, Auto Xóa Cache Khi Thoát & Auto Update App
- **Vấn đề / Yêu cầu (theo ảnh minh họa trong Cài đặt):**
  1. Bỏ mục **`1. Tài khoản`** khỏi thanh phân mục trong Cài đặt và đánh lại số thứ tự 5 tab còn lại (`1. Trình phát tích hợp` → `5. Tiện ích mở rộng`).
  2. Thêm tính năng **Đồng bộ qua Tài khoản Email** trực tiếp vào thẻ **`Đồng Bộ Nhanh Điện Thoại ↔ Android TV`**.
  3. Thêm lựa chọn **Auto xóa Cache khi thoát App** trong thẻ **`Thông Tin Gói APK Native`**.
  4. Thêm tính năng **Auto Update App khi có phiên bản mới** trong thẻ **`Thông Tin Gói APK Native`**.
- **Giải pháp đã triển khai:**
  1. **Gỡ mục `"Tài khoản"` & Cập nhật tiêu đề Cài đặt ([Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [fragment_settings.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/fragment_settings.xml), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3d/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Xóa `SettingsSubtab.ACCOUNT` và `renderAccountTab()`, đổi tab mặc định trong `MainState` sang `SettingsSubtab.PLAYER`, đánh số lại 5 subtabs: `1. Trình phát tích hợp`, `2. Nguồn phim (+Web)`, `3. Giao diện`, `4. Cập nhật & Sao lưu`, `5. Tiện ích mở rộng`.
     - Bỏ chữ `Tài khoản, ` khỏi `txtSettingsBannerSub` trong `fragment_settings.xml`.
  2. **Đồng bộ qua Tài khoản Email trong `Đồng Bộ Nhanh Điện Thoại ↔ Android TV` ([SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Đưa card **📧 Đồng Bộ Qua Tài Khoản Email** vào bên trong `syncBox` (`4. Cập nhật & Sao lưu`), hỗ trợ Liên kết/Đổi Email, Sao lưu Email (`saveCloudSnapshot`), Khôi phục Email (`restoreCloudSnapshot`), Ngắt liên kết, bật/tắt tự động sao lưu qua Email (`cloudAutoSync`), và **✉ Gửi mã qua Email** (`ACTION_SENDTO mailto:` kèm mã `HH3D1:...` + fallback copy clipboard cho TV).
  3. **Auto xóa Cache khi thoát App ([Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Thêm `AppSettings.autoClearCacheOnExit` (lưu/tải bền vững qua `PrefsStore`) và công tắc bật/tắt trong `infoBox`.
     - Trong `MainActivity.onDestroy()`, khi `isFinishing && state.settings.autoClearCacheOnExit` (tránh xóa nhầm khi xoay màn hình), tự động xóa `HlsCacheProvider.clearCache(applicationContext)` + `Glide` memory/disk cache.
  4. **Auto Update App khi có phiên bản mới ([AppUpdateChecker.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/AppUpdateChecker.kt), [AndroidManifest.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/AndroidManifest.xml), [Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Tạo `AppUpdateChecker` hỗ trợ đọc JSON manifest tùy chỉnh (`{versionCode, versionName, apkUrl, changelog}`), GitHub Releases API JSON (`{tag_name, body, assets[].browser_download_url}`) và link `.apk` trực tiếp; so sánh SemVer/`versionCode`, tải APK vào `cacheDir/updates/HoatHinh3D-update.apk` với `%` tiến trình và mở trình cài đặt qua `FileProvider` (cấp quyền `REQUEST_INSTALL_PACKAGES` + hướng dẫn cấp quyền trên Android 8+).
     - Thêm công tắc **Auto Update App khi có phiên bản mới** + nút **`⬆ Kiểm tra cập nhật`** & **`⚙ URL cập nhật`** trong `infoBox`; tự kiểm tra âm thầm sau 2.5s khi mở app nếu `autoUpdateApp` bật và đã cấu hình `updateManifestUrl`.
- **Kiểm tra thực tế:**
  - Bổ sung 2 unit tests: `autoClearCacheAndAutoUpdate_persistAndReset_andSubtabsRenumbered` ([PrefsStoreTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt)) và `appUpdateChecker_parsesCustomAndGithubManifests_andComparesVersions` ([NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt)).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` (không tạo Release R8 APK):
    - `testDebugUnitTest`: **41/41 unit tests PASS** (`NguonCApiAndRepoTest` 13, `PlayerHelpersTest` 7, `PrefsStoreTest` 7, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **13.80 MB** (`14,470,034 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).

### [2026-11-27] - Phiên 36: Tinh Gọn Header Chọn Nguồn, Bỏ Popup "Xem Dở / Xem Tiếp", Thiết Kế Lại "Bỏ Qua Intro 85s" (Hiện 5s) & Tự Phát Tiếp Đúng Phút/Giây Từ "Tiếp Tục Xem"
- **Vấn đề / Yêu cầu (theo ảnh minh họa):**
  1. Bỏ chữ **`Nguồn phim`** trước ô lựa chọn nguồn phim trên thanh Header.
  2. Bỏ khung hiển thị **`Xem dở ở ... • Xem tiếp`** (`resumePromptPill`) trong trình phát khi play phim.
  3. Mục **`Bỏ qua Intro (+85s)`** trong trình phát chỉ hiển thị **5 giây** rồi tự ẩn, đồng thời thiết kế lại giao diện sắc nét, dễ nhìn và thẩm mỹ hơn (khắc phục nền tím nhạt do `MaterialButton` tint làm mờ chữ xanh).
  4. Thiết kế lại tính năng **`Tiếp tục xem`** (và Lịch sử / nút *Tiếp tục Tập X*): khi bấm play là **phát tiếp tục ngay tại đúng số phút, số giây** đang xem dang dở lần trước.
- **Giải pháp đã triển khai:**
  1. **Gỡ chữ `Nguồn phim` trên Header ([activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Xóa `txtHeaderSourcePrefix` khỏi `headerSourceWrap` và bỏ tham chiếu trong `MainActivity.applyThemeAndDeviceLayout()`.
  2. **Bỏ popup `Xem dở / Xem tiếp` & Tự động phát tiếp đúng phút/giây ([activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [PlayerHelpers.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerHelpers.kt), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [HistoryFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/history/HistoryFragment.kt), [MovieCardAdapter.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/common/MovieCardAdapter.kt), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Gỡ bỏ `resumePromptPill`, `txtResumePrompt`, `btnResumeConfirm`, `btnResumeDismiss` khỏi `activity_player.xml` và `PlayerActivity.kt`.
     - Thêm `EXTRA_RESUME_SEC` vào `PlayerActivity.createIntent(..., resumeTimeSec)` & `AppHost.quickPlayMovie(..., resumeTimeSec)`: khi bấm từ **Tiếp tục xem**, **Lịch sử**, hoặc nút **Tiếp tục Tập X** trong Chi tiết phim, trình phát tự động tua và phát tiếp chính xác tại mốc giây/phút đã lưu (cả trước `prepare()` lẫn đối soát lại tại `STATE_READY` cho luồng HLS, kèm toast `"Phát tiếp từ MM:SS"` và không bị `Auto Skip Intro` ghi đè), đồng thời giữ nguyên mốc khi đổi máy chủ trong tập.
     - Cập nhật thẻ **Tiếp tục xem**, **Lịch sử** và nút **Tiếp tục Tập** hiển thị rõ cả mốc thời gian đã xem (`PlayerUtils.formatContinueMeta` → `"Tập 49 • 02:22 (14%)"`).
  3. **Thiết kế lại & Giới hạn 5s cho nút `Bỏ qua Intro +85s` ([bg_cs_skip_intro.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/bg_cs_skip_intro.xml), [activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt)):**
     - Đổi `btnSkipIntro` từ `Button` (bị MaterialButton tint tím làm mờ chữ) sang `LinearLayout` dùng selector kính obsidian tối `#CC0B111E` viền xanh mint `#2CE59B` (`bg_cs_skip_intro`), gồm icon tua tập `#2CE59B` + chữ trắng sắc nét `"Bỏ qua Intro"` + badge `"+85s"` màu xanh mint nổi bật.
     - Hiển thị 1 lần mỗi tập khi bắt đầu phát (`maybeShowSkipIntroForFiveSeconds`), tự động mờ ẩn sau đúng **5 giây (`5,000ms`)** qua `skipIntroHideRunnable` (và không hiện khi đang phát tiếp giữa tập từ mốc `>= 30s`).
- **Kiểm tra thực tế:**
  - Bổ sung unit test `formatContinueMeta_formatsExactMinuteSecondAndPercent` trong [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` (không tạo Release R8 APK):
    - `testDebugUnitTest`: **39/39 unit tests PASS** (`NguonCApiAndRepoTest` 12, `PlayerHelpersTest` 7, `PrefsStoreTest` 6, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **13.80 MB** (`14,470,034 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).

### [2026-11-27] - Phiên 35: Khắc Phục Toàn Diện Cho Xiaomi MiTV4-ANSM0 (Android 6.0.1 API 23) — TLS 1.2 + Modern Root CAs, Glide Qua OkHttp, Sửa Overlap Lỗi Player & Tối Ưu RAM/1080p
- **Vấn đề / Yêu cầu:**
  1. Trên **Xiaomi MiTV4-ANSM0** (Android 6.0.1 • API 23 • bản vá bảo mật `01/04/2017`, `Amlogic 4×Cortex-A53`, `armeabi-v7a`, `Mali-450 MP`, `1.6 GB RAM`, `5.0 GB` bộ nhớ trong, `1920×1080 @ 60 Hz`), app rơi vào dữ liệu fallback, **không hiện toàn bộ ảnh poster/hero** trên trang chủ, **không kết nối được nguồn / không phát được video** (`Không có luồng phát trực tiếp`).
  2. Trên màn hình lỗi Player, cụm nút điều khiển trung tâm (`<< 10`, `||`, `10 >>`) nằm đè lên thông báo lỗi & nút `Thử lại`/`Đóng`.
  3. Một số biểu tượng Unicode (`▶`, `ℹ`, `▾`, `▴`, `☀`, `🌙`, emoji bộ lọc) bị biến thành ô vuông `▯` trên font hệ thống Android 6.0.1 của MiTV4.
  4. **Hủy bỏ tạo file Release R8 APK (`assembleRelease` + `lintVitalRelease`)** sau mỗi phiên — chỉ chạy `build-apk.ps1` xuất `HoatHinh3D-Universal-debug.apk`.
- **Nguyên nhân cốt lõi (Root Cause):**
  - Toàn bộ domain nguồn & CDN (`yanhh3d.now`, `yanhh3d.ee`, `phimapi.com`, `phimimg.com`, `cloudflare-dns.com`, `dns.google`, `wsrv.nl`, `streamrpt1.site`) dùng chứng chỉ TLS 1.2/1.3 cấp bởi **ISRG Root X2**, **GlobalSign ECC Root CA - R4**, **SSL.com Root ECC** — các Root CA này **không tồn tại** trong trust store năm 2017 của Android 6.0.1 (và MiTV4 không có Google Play Services).
  - `Glide 4.16.0` mặc định dùng `HttpUrlConnection` (không qua `HttpClientProvider.okHttpClient`), nên toàn bộ ảnh bìa không hưởng TLS 1.2, DoH DNS, hay header `User-Agent`/`Referer`.
  - Trong `activity_player.xml`, `errorOverlay` đặt trước `hudContainer` nên bị `centerControls` vẽ đè lên khi lỗi.
- **Giải pháp đã triển khai:**
  1. **TLS 1.2 + Gói 15 Root CA Hiện Đại Cho Android 6.0.1 ([modern_roots.pem](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/raw/modern_roots.pem), [CompatTls.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/CompatTls.kt), [HttpClientProvider.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/HttpClientProvider.kt), [HoatHinhApp.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/HoatHinhApp.kt)):**
     - Đóng gói 15 Root CA công khai chuẩn (`ISRG Root X1/X2`, `GlobalSign ECC R4 / R2 / R3 / R6`, `GTS Root R1`, `SSL.com Root ECC/RSA`, `DigiCert Global Root CA / G2 / G3`, `USERTrust ECC/RSA`) vào `res/raw/modern_roots.pem` (22 KB).
     - Xây dựng `CompatTls` (`CompositeX509TrustManager` + `Tls12SocketFactory` ép bật TLSv1.2 & cipher ECDHE-ECDSA/RSA trên API <= 25 + `Protocol.HTTP_1_1` trên API <= 23 tránh kẹt ALPN HTTP/2), áp dụng cho **cả `dohClient`, `okHttpClient`, và `HlsCacheProvider.streamingOkHttpClient`**.
     - Nạp `HttpClientProvider.init(this)` ngay từ `HoatHinhApp.onCreate()` và ghi nhật ký lỗi mạng/TLS vào `AppDiagnostics` trong `HttpClientProvider` & `YanHh3dNowScraper`.
  2. **Kết Nối Glide Qua `OkHttpClient` & Tối Ưu RAM/Bộ Nhớ MiTV4 ([GlideOkHttpSupport.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/GlideOkHttpSupport.kt), [HoatHinhApp.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/HoatHinhApp.kt), [MovieCardAdapter.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/common/MovieCardAdapter.kt), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt), [HlsCacheProvider.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/HlsCacheProvider.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Đăng ký `GlideOkHttpUrlLoader.Factory(HttpClientProvider.okHttpClient)` trong `HoatHinhApp.onCreate()` để toàn bộ ảnh bìa Trang chủ / Hero / Chi tiết dùng chung TLS 1.2 + Root CA + DoH + `User-Agent`/`Referer`.
     - Đặt `Glide.setMemoryCategory(MemoryCategory.LOW)`, `DecodeFormat.PREFER_RGB_565` & `override(300, 420)` / `(960, 540)` trên thiết bị RAM `<= 2560 MB`; giảm `SimpleCache` xuống `96 MB` cho bộ nhớ trong 5 GB của MiTV4; tự động thử lại 1 lần sau `3.5s` nếu TV vừa khởi động chưa kịp nhận IP/Wi-Fi.
  3. **Sửa Overlap Lỗi Player & Giới Hạn 1080p/Fallback 720p Cho MiTV4 ([activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [DeviceProfileResolver.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/DeviceProfileResolver.kt)):**
     - Đưa `errorOverlay` và `autoNextOverlay` ra sau `hudContainer` (`elevation="14dp"`) + ẩn `hudContainer` khi báo lỗi để D-Pad focus chuẩn vào `Thử lại`/`Đóng`.
     - Giới hạn ABR tối đa `1080p` cho TV RAM `<= 2560 MB` màn hình `1080p` (`DeviceProfileResolver.recommendedMaxVideoHeight`), và tự hạ về `720p` (`1280x720`) thử lại 1 lần nếu bộ giải mã phần cứng gặp `ERROR_CODE_DECODER_*`.
  4. **Thay Thế Ký Tự Lỗi Ô Vuông `▯` Bằng VectorDrawables ([ic_play_arrow_sm.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/ic_play_arrow_sm.xml), [ic_info_outline.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/ic_info_outline.xml), [ic_chevron_down.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/ic_chevron_down.xml), [ic_theme_sun.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/ic_theme_sun.xml), [ic_theme_moon.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/ic_theme_moon.xml), [activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [layout_home_header.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/layout_home_header.xml), [activity_movie_detail.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_movie_detail.xml)):**
     - Thay `▶`, `ℹ`, `▾`, `▴`, `☀`, `🌙`, `🔥`, emoji bộ lọc bằng VectorDrawable & text chuẩn Roboto để hiển thị sắc nét trên Android 6.0.1.
- **Kiểm tra thực tế:**
  - Bổ sung 2 unit tests mới trong [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt) (`compatTls_parsesModernRootsPemAndBuildsCompositeTrustManager`) và [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt) (`deviceProfileResolver_capsMaxVideoHeightForMiTv4LowRam1080p`).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` (không chạy `assembleRelease` theo yêu cầu hủy tạo Release R8 APK):
    - `testDebugUnitTest`: **38/38 unit tests PASS** (`NguonCApiAndRepoTest` 12, `PlayerHelpersTest` 6, `PrefsStoreTest` 6, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **13.80 MB** (`14,469,959 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).

### [2026-11-27] - Phiên 34: Đối Chiếu Kiến Trúc HH3D & Nâng Cấp Player Telemetry, MovieDetail Glass, Tìm Kiếm Đa Nguồn, Xóa Lịch Sử Lẻ, Đổi Giao Diện Nhanh & Xác Nhận Thoát App
- **Vấn đề / Yêu cầu:** Tham khảo và đánh giá chi tiết cấu trúc/UI của ứng dụng `HH3D.apk` (Crystal Glassmorphism / Cinema Dark), so sánh và chắt lọc những tính năng hay, tiện ích thực dụng chưa có hoặc có thể tối ưu hơn để hoàn thiện **HoatHinh3D**.
- **Giải pháp đã triển khai:**
  1. **Trình phát Media3 ExoPlayer — Buffer Telemetry, Nút Tập Trước & Tốc Độ `0.25x` ([activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [PlayerHelpers.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerHelpers.kt), [PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):**
     - Sửa lỗi nút **`btnPrevEp` (Tập trước)** bị ẩn cố định (`visibility="gone"`): tự động hiện khi phim có `> 1` tập và sắp xếp lại `topTitleBlock` (`toEndOf`/`toStartOf`) để tiêu đề không bao giờ đè lên cụm nút góc phải.
     - Bổ sung 2 nút nhanh **`btnQuickQuality`** (`Auto`/`1080p`) và **`btnQuickSpeed`** (`1x`..`0.25x`) trên thanh trên cùng mở thẳng ngăn Chọn Chất Lượng / Tốc Độ; bổ sung mốc tốc độ **`0.25x`** vào `PlayerUtils.SPEED_OPTIONS` và đồng bộ qua `PrefsStore` & `SettingsFragment`.
     - Hiển thị phần đệm trước trên `seekBar.secondaryProgress` từ `player.bufferedPosition` kèm nhãn **`txtBufferedAhead` (`+XXs đệm`)** khi đệm trước `>= 2s` (`PlayerUtils.computeBufferedAheadSec`).
  2. **Chi tiết phim Liquid Glass & Thu gọn/Xem thêm mô tả ([activity_movie_detail.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_movie_detail.xml), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt)):**
     - Bọc cụm thông tin metadata/thể loại/mô tả (`detailInfoGlassPanel`) và cụm máy chủ/lưới tập (`detailEpisodesGlassPanel`) trong thẻ kính mờ `ThemeManager.glassPanelDrawable`.
     - Mặc định thu gọn mô tả tối đa 3 dòng (`maxLines = 3`) kèm nút **`btnDetailReadMore` (`Xem thêm ▾` / `Thu gọn ▴`)** hỗ trợ D-Pad focus và tự ẩn nếu nội dung ngắn, giúp lưới chọn tập hiển thị ngay không phải cuộn dài.
  3. **Tìm kiếm Đa Nguồn có Lọc & Đếm kết quả theo từng nguồn ([CustomSourceParser.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/CustomSourceParser.kt), [MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt), [layout_home_header.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/layout_home_header.xml), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Mở rộng `MovieRepository.searchDonghua` quét song song cả các nguồn tùy chỉnh đang bật (`CustomSourceParser.searchCustomSource`).
     - Bổ sung `CatalogQueryEngine.countSearchHitsBySource` và lọc theo nguồn (`searchSourceFilter`) trong `MainState`: trên Tablet & Android TV hiển thị bố cục **2 cột (`multiSearchLeftPanel` chọn nguồn kèm số kết quả bên trái + tóm tắt bên phải)**, trên Mobile hiển thị dải chip kính ngang cuộn mượt (`multiSearchMobileScroll`).
  4. **Xóa từng phim Lịch sử, Đổi Sáng/Tối 1 chạm & Xác nhận Thoát App ([item_history_movie.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/item_history_movie.xml), [HistoryFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/history/HistoryFragment.kt), [FavoritesFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/favorites/FavoritesFragment.kt), [activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Thêm `PrefsStore.removeHistoryItem()` và nút **`btnRemoveHistoryItem` (`✕`)** trên từng thẻ lịch sử xem; tối ưu `HistoryFragment` & `FavoritesFragment` tránh gọi thừa `notifyDataSetChanged()` khi signature giao diện không đổi.
     - Thêm nút **`btnQuickTheme` (`☀` / `🌙`)** trên Header và **`tvRailTheme`** trên Left Rail TV: bấm 1 chạm đổi nhanh Sáng ↔ Tối, nhấn giữ mở bảng chọn nhanh 4 chủ đề Liquid Glass.
     - Bổ sung hộp thoại kính mờ **`showExitConfirmDialog()`** khi bấm Back ở trang chủ (focus mặc định vào *"Ở lại"* tránh thoát nhầm trên Android TV Remote).
- **Kiểm tra thực tế:**
  - Bổ sung 3 unit tests mới trong [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt) (`bufferedAheadAndSpeedFormatting_workAccurately`), [PrefsStoreTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt) (`removeHistoryItem_deletesSingleEntryOnly_and_quarterSpeedPersists`) và [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt) (`catalogQueryEngine_countsAndFiltersMultiSourceSearch`).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` + `.\gradlew.bat assembleRelease`:
    - `testDebugUnitTest`: **36/36 unit tests PASS** (`NguonCApiAndRepoTest` 11, `PlayerHelpersTest` 5, `PrefsStoreTest` 6, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **13.80 MB** (`14,468,299 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).
    - `assembleRelease` (`lintVitalRelease` sạch + R8 + `shrinkResources`): **4.66 MB** (`4,881,764 bytes`) → `android/app/build/outputs/apk/release/app-release-unsigned.apk`.

### [2026-11-26] - Phiên 33: Cửa Sổ Chọn Nguồn Liquid Glass Trong Suốt & Gỡ Nút "Đồng Bộ Cloud" Trên Tiêu Đề Cài Đặt
- **Vấn đề / Yêu cầu:**
  1. Thay đổi giao diện cửa sổ lựa chọn Nguồn phim khi được kích hoạt (từ pill chọn nguồn trên Header, nút Chọn nguồn thu gọn góc trên-phải và Left Rail trên TV) để bung ra khung lựa chọn dạng kính trong suốt (`Liquid Glass`).
  2. Gỡ nút **`☁ Đồng bộ Cloud`** ở góc trên-phải banner *"Trung Tâm Cài Đặt Hệ Thống"*.
- **Giải pháp đã triển khai:**
  1. **Khung chọn nguồn Liquid Glass trong suốt ([activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Thay `Spinner` dropdown đục trên `headerSourcePill` bằng nhãn `txtHeaderSourceName` (`... ▾`) và trỏ đồng bộ cả `headerSourcePill`, `btnCollapsedSource` lẫn `tvRailSource` về `openSourcePickerOverlay()`.
     - Bổ sung `floatingSourcePickerOverlay` (`sourcePickerScrim` nền mờ nhẹ để giữ thấy nội dung phía sau + `floatingSourcePickerContainer` dùng `ThemeManager.liquidGlassSheetDrawable(this, p, 22f)` theo đúng mức `glassOpacity` 10–90% và theme sáng/tối), hiệu ứng bung từ góc trên-phải trên Mobile/Tablet (hoặc giữa màn hình trên TV, `0ms` khi bật `reducedMotion`).
     - Mỗi nguồn hiển thị dạng hàng kính trong suốt (`ThemeManager.navTabDrawable`), kèm nhãn chất lượng, ping, vai trò (`Nguồn chính` / `Dự phòng` / `Tùy chỉnh`), badge `✓ Đang dùng`, nút `Mở thanh điều hướng` (khi chrome đang thu gọn) và phím tắt `⚙ Quản lý nguồn Web`; hỗ trợ D-Pad focus & nút Back (`closeSourcePickerOverlay`).
  2. **Gỡ bỏ nút `☁ Đồng bộ Cloud` ở tiêu đề Cài đặt ([fragment_settings.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/fragment_settings.xml), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):**
     - Xóa button `btnSyncCloudTop` (`☁ Đồng bộ Cloud`) khỏi banner *"Trung Tâm Cài Đặt Hệ Thống"* và gỡ code bind/style `btnSyncCloudTop` trong `SettingsFragment`.
- **Kiểm tra thực tế:**
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` + `.\gradlew.bat assembleRelease`:
    - `testDebugUnitTest`: **33/33 unit tests PASS** (`NguonCApiAndRepoTest` 10, `PlayerHelpersTest` 4, `PrefsStoreTest` 5, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **14.03 MB** (`14,714,107 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).
    - `assembleRelease` (`lintVitalRelease` sạch + R8 + `shrinkResources`): **4.65 MB** (`4,873,588 bytes`).

### [2026-11-26] - Phiên 32: Tích Hợp 5 Nâng Cấp "0-KB Bloat" Từ Phân Tích zFim (DoH DNS, TV Box Ultra-Lite, LAN UDP Discovery, Sync Phụ Đề ±0.5s, Night Mode DRC & Nhật Ký Chẩn Đoán)
- **Vấn đề / Yêu cầu:** Sau khi bóc tách và so sánh trực tiếp `zitfim.apk` (38.54 MB) với HoatHinh3D, triển khai 5 ưu điểm thực dụng nhất với chi phí 0 KB thư viện ngoài, giữ vững tiêu chí **Nhanh - Gọn - Nhẹ**, tốc độ khởi phát video và thích ứng Mobile / Tablet / Android TV.
- **Giải pháp đã triển khai:**
  1. **DNS-over-HTTPS (DoH) dự phòng & Nhật ký chẩn đoán (`AppDiagnostics`) ([HttpClientProvider.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/HttpClientProvider.kt)):**
     - Giữ TLS chuẩn (tuyệt đối không dùng `trustAllCerts` mất an toàn); nâng cấp `cachedDns` tự động truy vấn DoH HTTPS JSON (`cloudflare-dns.com` / `dns.google` qua IP bootstrap cứng `1.1.1.1` / `8.8.8.8` để tránh đệ quy DNS) khi DNS nhà mạng chặn hoặc trả về IP loopback/sinkhole (`0.0.0.0` / `127.0.0.1`).
     - Bổ sung bộ đệm vòng 50 dòng `AppDiagnostics` ghi lại lỗi DoH, phát hiện chặn mạng và lỗi `ExoPlayer` (`onPlayerError`).
  2. **Chế độ Siêu tối ưu TV Box RAM thấp (`Reduced Motion` / Ultra-Lite) ([Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt), [DeviceProfileResolver.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/DeviceProfileResolver.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [MovieCardAdapter.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/common/MovieCardAdapter.kt)):**
     - Thêm `AppSettings.reducedMotion` và `DeviceProfileResolver.isLowRamDevice()` / `shouldUseReducedMotion()` (tự động kích hoạt khi thiết bị có RAM `<= 2560 MB` như TV Box giá rẻ hoặc khi người dùng bật tay trong Cài đặt → Giao diện).
     - Khi bật: ẩn `orbsContainer`, bỏ `RenderEffect` blur, tắt hiệu ứng scale thẻ trên TV và chuyển thời lượng chuyển động thanh tìm kiếm / thu gọn chrome về `0ms`.
  3. **Tự động tìm thiết bị đồng bộ LAN qua UDP Broadcast ([LanSyncManager.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/LanSyncManager.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):**
     - Khi bấm **"📡 Phát đồng bộ LAN"**, phát gói beacon UDP nhỏ (`DISCOVERY_UDP_PORT = 8776`) mỗi `1.2s`; khi bấm **"📲 Nhận đồng bộ LAN"** trên TV/máy thứ 2, tự quét `discoverLanPeers(2200L)` để hiển thị nút chạm 1 lần điền sẵn `IP:Cổng` (vẫn giữ nhập tay dự phòng + yêu cầu mã PIN 4 số).
  4. **Chỉnh độ trễ phụ đề rời (`±0.5s`) & Chế độ Ban Đêm (Night Mode DRC) ([PlayerHelpers.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerHelpers.kt), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):**
     - Bổ sung `SubtitleOffsetHelper.shiftSrtOrVtt()` hỗ trợ dịch mốc thời gian phụ đề `.srt` / `.vtt` theo bước `-0.5s`, `0s`, `+0.5s` trực tiếp trong ngăn Cài đặt phát (khi đã nạp phụ đề rời).
     - Bổ sung `AudioDrcController` sử dụng API âm thanh có sẵn của Android SDK (`DynamicsProcessing` trên API 28+ với limiter nhẹ `-6 dB`, dự phòng `LoudnessEnhancer` trên API 23–27), điều khiển ngay trong Player và trong *Cài đặt → Trình phát tích hợp*.
  5. **Trình xem & sao chép Nhật ký chẩn đoán trong Cài đặt ([SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):**
     - Thêm nút **`📋 Xem Nhật ký chẩn đoán`** tại mục *Cài đặt → Cập nhật & Sao lưu* cho phép xem log `AppDiagnostics`, sao chép nhanh vào Clipboard hoặc xóa log.
- **Kiểm tra thực tế:**
  - Bổ sung 3 unit tests mới trong [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt) (`dohParser_and_appDiagnostics_handleValidAndMalformedInputs`), [PrefsStoreTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt) (`reducedMotionAndNightDrc_persistAndReset_and_lanDiscoveryPacketParses`), và [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt) (`subtitleOffsetHelper_shiftsSrtAndVttAndClampsAtZero`).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` + `.\gradlew.bat assembleRelease`:
    - `testDebugUnitTest`: **33/33 unit tests PASS** (`NguonCApiAndRepoTest` 10, `PlayerHelpersTest` 4, `PrefsStoreTest` 5, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **12.70 MB** (`13,314,240 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).
    - `assembleRelease` (`lintVitalRelease` sạch + R8 + `shrinkResources`): **4.65 MB** (`4,872,224 bytes`).

### [2026-11-26] - Phiên 31: Tối Ưu Toàn Diện 3 Giai Đoạn (P0 Tốc Độ/R8/Tái Chế View, P1 TV Rail/Tải Nền/Đồng Bộ LAN, P2 Phụ Đề Rời & HUD Tốc Độ Mạng)
- **Vấn đề / Yêu cầu:** Thực hiện hóa toàn bộ 3 giai đoạn tối ưu (P0, P1, P2) để biến app thành trình xem phim siêu gọn nhẹ, tốc độ mở/chuyển tập tức thì và tối ưu đồng thời cho cả Điện thoại Android lẫn Android TV.
- **Giải pháp đã triển khai:**
  1. **Giai đoạn P0 — Tốc độ, Tái chế ViewHolder, Giảm I/O & Thu gọn APK:**
     - **Fast-Path nguồn phát ([MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt)):** Bổ sung `fetchMovieDetailFast(onFastReady)` cho phép `PlayerActivity` phát ngay khi nguồn chính trả luồng HLS trực tiếp (~300–800ms) mà không phải đợi nguồn dự phòng chậm; `MovieDetailActivity` tự pre-warm sẵn luồng tập mục tiêu khi mở chi tiết.
     - **Nạp trước phân đoạn tập kế ([HlsCacheProvider.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/HlsCacheProvider.kt), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt)):** Bổ sung `prefetchHlsStart()` đọc sẵn playlist + 2 phân đoạn đầu tiên của tập kế tiếp vào `SimpleCache` qua `SanitizingTsDataSource` khi tập hiện tại đạt `STATE_READY`.
     - **Tái chế ViewHolder thực sự trên Trang chủ ([fragment_home.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/fragment_home.xml), [layout_home_header.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/layout_home_header.xml), [layout_home_footer.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/layout_home_footer.xml), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt)):** Thay `NestedScrollView` bằng `RecyclerView` hợp nhất dùng `ConcatAdapter` (`HomeHeaderAdapter` + `MovieCardAdapter` + `HomeFooterAdapter`) và `GridLayoutManager.SpanSizeLookup` để tái chế toàn bộ thẻ phim khi nạp nhiều trang.
     - **Giảm I/O, Drawable & R8 Shrinking ([PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [MovieCardAdapter.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/common/MovieCardAdapter.kt), [build.gradle](file:///E:/Project/3_HoatHinh3D/android/app/build.gradle), [proguard-rules.pro](file:///E:/Project/3_HoatHinh3D/android/app/proguard-rules.pro)):** Throttle `PrefsStore.saveProgress()` từ `500ms` lên `8,000ms` (kèm flush ngay ở `onPause`/`onDestroy`/đổi tập); bỏ hop `wsrv.nl` thừa cho CDN nhanh (`phimimg.com`, `yanhh3d.*`, `.webp`), cache drawable badge và ép Glide `override(380, 540)`; gỡ thư viện thừa `androidx.webkit` và bật `minifyEnabled true` + `shrinkResources true` cho `release` (APK release còn **4.64 MB**).
  2. **Giai đoạn P1 — Android TV Left Rail, Đánh dấu tập đã xem, Tải Offline nền & Đồng bộ LAN:**
     - **Android TV Left Navigation Rail ([activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):** Khi ở chế độ `TV`, ẩn thanh tab đáy kiểu Mobile và hiển thị `tvNavRail` bên trái để D-Pad Trái luôn nhảy tức thì vào menu/Tìm kiếm/Đổi nguồn ở mọi vị trí cuộn.
     - **Tải Offline nền bền bỉ & Hàng loạt ([OfflineDownloadService.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/offline/OfflineDownloadService.kt), [AndroidManifest.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/AndroidManifest.xml), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt)):** Tách tải HLS sang `OfflineDownloadService` (chạy nền độc lập với vòng đời UI, hỗ trợ xếp hàng tải 1 tập hoặc 3/5 tập tiếp theo).
     - **Đánh dấu tập đã xem & Skip Intro riêng từng bộ ([PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt)):** Tự đánh dấu tập đã xem (`✓`) trên lưới tập và chỉnh riêng mốc Skip Intro (`60s` / `85s` / `105s` / `120s`) cho từng phim.
     - **Lọc kết hợp Trạng thái + Âm thanh + Sắp xếp & Đồng bộ LAN/Mã đồng bộ ([LanSyncManager.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/LanSyncManager.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):** Bổ sung `CatalogStatusFilter` (`Tất cả trạng thái`, `Đang chiếu`, `Hoàn thành`) vào thanh lọc; thêm máy chủ HTTP nội bộ Wi-Fi LAN (`ServerSocket` + PIN 4 số) và Mã đồng bộ nén GZIP (`HH3D1:...`) chuyển dữ liệu giữa Điện thoại và Tivi trong < 1s.
  3. **Giai đoạn P2 — Phụ đề rời thực sự & HUD Độ phân giải/Tốc độ mạng:**
     - **Phụ đề rời `.srt` / `.vtt` / `.ass` ([PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt)):** Nạp trực tiếp file phụ đề từ thiết bị vào `MediaItem.SubtitleConfiguration` kèm chọn cỡ chữ (`Nhỏ 4.2%`, `Vừa 5.5%`, `Lớn 7.2%`) và bỏ banner tĩnh che màn hình.
     - **HUD độ phân giải & tốc độ tải thực tế ([activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt)):** Hiển thị trực tiếp độ phân giải video đang phát (`1080p` / `2160p 4K`) + tốc độ mạng (`TrafficStats.getUidRxBytes` → `KB/s` / `MB/s`) trên `txtPlayerBadge`.
- **Kiểm tra thực tế:**
  - Mở rộng unit tests trong [PrefsStoreTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt) (`watchedEpisodes`, `skipIntroSec`, `exportCompactSyncCode`/`importCompactSyncCode`, `LanSyncManager.normalizeLanSyncUrl`) và [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt) (`CatalogStatusFilter` & `getOptimizedPosterUrl`).
  - Clean build `.\gradlew.bat clean testDebugUnitTest assembleDebug assembleRelease` + `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **30/30 unit tests PASS** (`NguonCApiAndRepoTest` 9, `PlayerHelpersTest` 3, `PrefsStoreTest` 4, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: **12.57 MB** (`13,176,677 bytes`) → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk).
    - `assembleRelease` (R8 + `shrinkResources`): **4.64 MB** (`4,861,316 bytes`) (`app-release-unsigned.apk`, `lintVitalRelease` pass).

### [2026-11-26] - Phiên 30: Phân Trang "Tải Thêm Phim", Bộ Lọc Âm Thanh & Sắp Xếp Nâng Cao, Tối Ưu Hiệu Năng `HomeFragment`
- **Vấn đề / Yêu cầu:** Thực hiện hóa các nhiệm vụ được gợi ý ở Phiên 29 một cách khoa học và tối ưu nhất: phân trang **"Tải thêm phim" (Load More)** trên `HomeFragment`, **bộ lọc âm thanh & sắp xếp nâng cao**, và tối ưu hiệu năng hiển thị danh sách.
- **Giải pháp đã triển khai:**
  1. **Phân trang "Tải thêm phim" đa nguồn & theo thể loại ([Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [YanHh3dScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dScraper.kt), [MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Bổ sung `CatalogPageResult(movies, page, totalPages)` (tính `hasMore`), `YanHh3dScraper.parseArchiveMaxPage()` / `fetchArchiveHtmlPage()`, `MovieRepository.fetchDonghuaPage()`, `MovieRepository.fetchGenrePage()`, và `MovieRepository.mergeCatalogPages()` khử trùng lặp theo `slug/id` mà vẫn giữ đúng thứ tự các trang trước.
     - Mở rộng `MainState` (`catalogPage`, `catalogTotalPages`, `genrePage`, `genreTotalPages`, `isLoadingMore`, `activeCatalogPage`, `activeCatalogTotalPages`, `hasMoreCatalogPages`) và `MainActivity.loadMoreCatalog()` (chống trùng lệnh khi đang nạp, đối chiếu snapshot `activeSourceId`/`selectedGenre` tránh ghi đè khi đổi nguồn/thể loại giữa chừng).
     - Trên [fragment_home.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/fragment_home.xml) và [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt): hiển thị tiến độ `Trang X/Y`, tự động nạp tiếp khi cuộn gần cuối trang (`remaining <= 320dp`) và bổ sung nút `btnLoadMoreCatalog` hỗ trợ D-Pad focus cho Android TV.
  2. **Bộ lọc âm thanh & sắp xếp nâng cao (`CatalogQueryEngine` trong [MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt), [Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [fragment_home.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/fragment_home.xml), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt)):**
     - Bổ sung `CatalogAudioFilter` (`Tất cả âm thanh`, `Thuyết Minh`, `Vietsub`) và `CatalogSortMode` (`Mới cập nhật`, `Nhiều tập`, `Đánh giá cao`, `Tên A → Z` theo chuẩn không dấu tiếng Việt).
     - Tách logic lọc & sắp xếp thuần túy sang `CatalogQueryEngine.filterAndSort()` và `matchesAudioFilter()` (nhận diện chính xác phim có ưu tiên Thuyết Minh/Lồng Tiếng thay vì gộp nhầm phim Vietsub mặc định).
     - Bổ sung thanh pill Liquid Glass ngang ngay dưới tiêu đề kho phim (`chipAudioAll`, `chipAudioDub`, `chipAudioSub`, `chipSortDefault`, `chipSortEpisodes`, `chipSortRating`, `chipSortTitleAz`) và nút `btnResetCatalogFilters` khi kết quả lọc trống.
  3. **Tối ưu hiệu năng `HomeFragment` ([HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt)):**
     - Loại bỏ lệnh `notifyDataSetChanged()` thừa ngay sau `submitList()` trên `trendingAdapter` và `catalogAdapter` (chỉ gọi lại khi chữ ký `metaRenderSig` gồm theme/chế độ TV/yêu thích/tiến độ xem thực sự thay đổi), giúp `DiffUtil` chỉ chèn tăng dần các thẻ mới khi bấm Tải thêm phim mà không nháy ảnh hay rebind lại toàn bộ danh sách.
     - Tránh khởi động lại `heroTimerJob` và không vẽ lại thanh chip thể loại/bộ lọc khi dữ liệu & trạng thái chọn không đổi.
- **Kiểm tra thực tế:**
  - Bổ sung 3 unit tests mới trong [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt) (`catalogQueryEngine_filtersAudioAndSortsAccurately`, `mergeCatalogPages_deduplicatesAndPreservesOrder`) và [YanHh3dScraperTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/YanHh3dScraperTest.kt) (`parseArchiveMaxPage_extractsMaxPageFromPathAndQueryLinks`).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`: **28/28 unit tests qua 100%** (`NguonCApiAndRepoTest` 8, `PlayerHelpersTest` 3, `PrefsStoreTest` 3, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped), `assembleDebug` thành công → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk) (`14,315,118 bytes`, 13.65 MB).

### [2026-11-26] - Phiên 29: Top 10 BXH Thịnh Hành Đồng Bộ Thời Gian Thực, Tinh Gọn Cài Đặt & Nhãn/Số Lượng Nguồn Phim Header
- **Vấn đề / Yêu cầu:**
  1. Sửa mục `Bảng Xếp Hạng Thịnh Hành` thành **Top 10 BXH Thịnh Hành** đồng bộ hóa theo thời gian thực từ **Nguồn chính** (`yanhh3d.now`).
  2. Bỏ nhãn phụ **`Top 8 Tu Tiên 3D`** ở bên phải tiêu đề BXH.
  3. Bỏ 3 mục: **Hẹn giờ tắt phim** (`Sleep Timer`), **Làm dịu viền màn hình** (`Cinema Vignette`) và **Tổng quan App** (`SettingsSubtab.OVERVIEW`).
  4. Bổ sung chữ **`Nguồn phim`** trước mục chọn Nguồn phim và hiển thị **số lượng nguồn phim** đang có trong mục Nguồn phim.
  5. Tổng kết tình trạng dự án vào Memory và cập nhật prompt chuyển giao cho AI Agent phiên sau.
- **Giải pháp đã triển khai:**
  1. **Top 10 BXH Thịnh Hành đồng bộ thời gian thực từ Nguồn chính ([YanHh3dNowScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dNowScraper.kt), [YanHh3dScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dScraper.kt), [MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [fragment_home.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/fragment_home.xml)):**
     - Viết `parseRealtimeRankingHtml()` & `fetchRealtimeTrendingTop10()` đọc trực tiếp Top `#1..#10` từ khối `.block_area-realtime #top-viewed-day li.item-top` trên `https://yanhh3d.now/` với header `Cache-Control: no-cache` (hỗ trợ cả `top-yanhh3d-xem-nhieu` khi chuyển `YanHH3D` với `bypassCache = true`).
     - Đồng bộ song song khi nạp feed chính, khi `onResume`, và tự động đồng bộ nền mỗi 60 giây trong vòng đời `Lifecycle.State.STARTED` (`startRealtimeTrendingSync()`).
     - Đổi tiêu đề thành **`🔥 Top 10 BXH Thịnh Hành`**, gỡ bỏ hoàn toàn `txtTrendingSub` (*Top 8 Tu Tiên 3D*).
  2. **Gỡ bỏ Hẹn giờ tắt phim, Làm dịu viền màn hình & Tổng quan App ([Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt), [fragment_settings.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/fragment_settings.xml), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt), [activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml), [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt), [PrefsStoreTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt)):**
     - Rút gọn Cài đặt còn 6 phân mục sạch (bỏ `2. Tổng quan App` & `renderOverviewTab`), gỡ bỏ hoàn toàn công tắc `Làm Dịu Viền Rạp Phim (Cinema Vignette)` / `ambientGlowView`, và gỡ bỏ `Sleep Timer` trên Cài đặt, `MainActivity`, `MovieDetailActivity`, `PlayerActivity`.
  3. **Bổ sung chữ `Nguồn phim` & số lượng nguồn phim ([activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Thêm nhãn `txtHeaderSourcePrefix` (`"Nguồn phim"`) trực tiếp trước `headerSourcePill` ở góc phải thanh tiêu đề chính, kèm badge `txtHeaderSourceCount` hiển thị số lượng nguồn phim (`"2 nguồn"`, hoặc `"X/Y nguồn"` khi tắt bớt nguồn) và tiêu đề hộp thoại chọn nhanh `"Nguồn phim (... nguồn)"`.
- **Kiểm tra thực tế:**
  - Bổ sung unit test `parseRealtimeRankingHtml_extractsTop10RealtimeFromPrimarySource` trong [YanHh3dNowScraperTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/YanHh3dNowScraperTest.kt).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`: **25/25 unit tests qua 100%** (`NguonCApiAndRepoTest` 6, `PlayerHelpersTest` 3, `PrefsStoreTest` 3, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 5, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped), `assembleDebug` thành công → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk) (`14,314,402 bytes`, 13.65 MB).

### [2026-11-26] - Phiên 28: Trái Tim Yêu Thích Trong Suốt/Đỏ, Bỏ Tìm Kiếm Đầu Trang, Đổi Vị Trí Nút Thu Gọn (Nguồn Trên-Phải / Tìm Kiếm Dưới-Phải) & Thoát Tìm Kiếm Khi Lướt
- **Vấn đề / Yêu cầu:**
  1. Mặc định biểu tượng **yêu thích hình trái tim** trên thẻ phim phải **trong suốt** và chuyển sang **màu đỏ** khi được chọn yêu thích.
  2. Khi thao tác **lướt chọn** trên giao diện chính, **thanh lựa chọn các mục bên dưới** thu gọn lại thành **1 biểu tượng Tìm kiếm ở góc dưới bên phải** màn hình.
  3. Khi đang ở **chế độ tìm kiếm phim** mà thao tác **lướt chọn** trên giao diện chính thì tính năng tìm kiếm **thoát và thu lại vào góc màn hình**.
  4. **Bỏ mục tìm kiếm** phía trên đầu giao diện.
  5. Khi thao tác **lướt chọn** trên giao diện chính, **thanh trên cùng màn hình** thu gọn lại thành **1 biểu tượng Chọn Nguồn phim ở góc trên bên phải** màn hình.
- **Giải pháp đã triển khai:**
  1. **Trái tim yêu thích trong suốt mặc định / đỏ rực khi đã chọn ([ic_fav_heart_outline.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/ic_fav_heart_outline.xml), [ic_fav_heart_filled.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/ic_fav_heart_filled.xml), [item_movie_card.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/item_movie_card.xml), [MovieCardAdapter.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/common/MovieCardAdapter.kt)):**
     - Thay `btnFavHeart` từ `TextView` có nền mờ sang `ImageView` nền `@android:color/transparent`: mặc định hiển thị vector trái tim rỗng viền trắng thanh (`ic_fav_heart_outline`), khi chọn yêu thích chuyển sang vector trái tim tô đỏ rực `#EF4444` (`ic_fav_heart_filled`).
  2. **Bỏ ô tìm kiếm khỏi Header & Đảo 2 nút thu gọn Trên-Phải (Nguồn) / Dưới-Phải (Tìm kiếm) ([activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Xóa `searchBarBox` khỏi `headerContainer` (thanh tiêu đề đầu trang chỉ còn logo `HoatHinh3D` và nút chọn nguồn `btnSourceBadge`).
     - Bổ sung nút kính lúp `btnBottomSearch` ở cạnh phải thanh điều hướng đáy `bottomNavContainer` để mở khung tìm kiếm nổi `floatingSearchContainer` ngay cả khi chưa cuộn trang.
     - Hoán đổi vị trí 2 nút nổi Liquid Glass khi thu gọn (`setChromeCollapsed`): `btnCollapsedSource` đặt ở **góc trên-phải (`top|end`)** và `btnCollapsedSearch` đặt ở **góc dưới-phải (`bottom|end`)**.
  3. **Thoát & thu gọn chế độ tìm kiếm vào góc màn hình khi lướt chọn ([MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Bổ sung `isSearchOverlayOpen`, `openSearchOverlay()`, `closeSearchOverlay()` (đóng bàn phím mềm, bỏ focus `editSearch`, ẩn `floatingSearchContainer`, hỗ trợ nút Back hệ thống).
     - Trong `onMainContentScroll(dy, scrollY)`, nếu đang mở chế độ tìm kiếm (`isSearchOverlayOpen || binding.editSearch.hasFocus()`) và người dùng thao tác lướt/cuộn (`abs(dy) > 4 || scrollY > collapseAt`), tự động gọi `closeSearchOverlay()` và `setChromeCollapsed(true)` để thu gọn ngay tìm kiếm về biểu tượng `btnCollapsedSearch` góc dưới-phải và thu gọn thanh trên về `btnCollapsedSource` góc trên-phải.
- **Kiểm tra thực tế:**
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`: **24/24 unit tests qua 100%** (`NguonCApiAndRepoTest` 6, `PlayerHelpersTest` 3, `PrefsStoreTest` 3, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped), `assembleDebug` thành công → [HoatHinh3D-Universal-debug.apk](file:///E:/Project/3_HoatHinh3D/HoatHinh3D-Universal-debug.apk) (`14,314,298 bytes`, 13.65 MB).

### [2026-11-26] - Phiên 27: Thu Gọn Thanh Trên/Dưới Khi Lướt Chọn Thành Icon Góc Phải, Bỏ Cài Đặt Header & Đổi "Tủ phim" → "Yêu thích"
- **Vấn đề / Yêu cầu:**
  1. Khi thao tác lướt chọn trên giao diện chính, **thanh tiêu đề phía trên** thu gọn lại thành **1 biểu tượng kính lúp Tìm kiếm ở góc trên bên phải** màn hình.
  2. **Bỏ mục Cài đặt** phía trên thanh tiêu đề bên phải màn hình.
  3. **Bỏ hiển thị số lượng** trên Tủ phim và **đổi "Tủ phim" thành "Yêu thích"**.
  4. *(Mục 4 xóa nút Lưu Offline trong chi tiết phim đã được người dùng yêu cầu hủy — giữ nguyên `btnDetailOffline` trong `MovieDetailActivity`).*
  5. Khi thao tác lướt chọn trên giao diện chính, **thanh lựa chọn các mục bên dưới** thu gọn lại thành **1 biểu tượng Chọn Nguồn phim ở góc dưới bên phải** màn hình.
- **Giải pháp đã triển khai:**
  1. **Header gọn gàng & Thanh thu gọn thành 2 icon nổi Liquid Glass ([activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt), [ic_lg_sources.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/ic_lg_sources.xml)):**
     - Gỡ bỏ `btnHeaderSettings` / `customSourceDot` khỏi góc phải thanh tiêu đề (`Cài đặt` truy cập qua tab `Cài đặt` ở thanh điều hướng).
     - Bổ sung 2 nút kính mờ nổi góc phải (`btnCollapsedSearch` trên-phải `top|end` dùng `ic_lg_search` và `btnCollapsedSource` dưới-phải `bottom|end` dùng `ic_lg_sources`), mặc định ẩn khi ở đầu trang và thu gọn mượt (`setChromeCollapsed`) khi người dùng lướt xuống (`onMainContentScroll`) qua 5 tab ([HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [ScheduleFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/schedule/ScheduleFragment.kt), [HistoryFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/history/HistoryFragment.kt), [FavoritesFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/favorites/FavoritesFragment.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)); khóa ổn định `chromeTopPx`/`chromeBottomPx` khi thu gọn để không giật khung cuộn.
     - Bấm `btnCollapsedSearch` mở lại thanh tiêu đề + tự focus `editSearch` & bật bàn phím; bấm `btnCollapsedSource` mở hộp thoại `showQuickSourcePickerDialog()` chọn nhanh nguồn phim đang bật (kèm tùy chọn *"Mở thanh điều hướng"*); cuộn ngược lên / về đầu trang hoặc chuyển tab tự động mở rộng lại 2 thanh.
  2. **Đổi "Tủ phim" → "Yêu thích" & bỏ badge đếm số ([activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [fragment_favorites.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/fragment_favorites.xml), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt)):**
     - Gỡ bỏ `navFavBadge` khỏi tab đáy; đổi toàn bộ nhãn *"Tủ phim"* sang *"Yêu thích"* / *"Phim Yêu Thích"* trên thanh điều hướng đáy, màn hình Yêu thích, chi tiết phim (`♡ Yêu thích`), thông báo và Cài đặt. Giữ nguyên tính năng & nút `btnDetailOffline` (*Lưu Offline Tập N*) trong [activity_movie_detail.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_movie_detail.xml) và `MovieDetailActivity.kt`.
- **Kiểm tra thực tế:**
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **24/24 tests PASS** (`NguonCApiAndRepoTest` 6, `PlayerHelpersTest` 3, `PrefsStoreTest` 3, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,313,545 bytes`).

---

### [2026-11-26] - Phiên 26: Khắc Phục Triệt Để Sự Cố Nhảy Nguồn Liên Tục & Tối Ưu Tốc Độ / Độ Ổn Định Nguồn Phát
- **Vấn đề / Yêu cầu:** Người dùng phản ánh 2 nguồn phim liên tục báo lỗi *"Luồng hiện tại gặp sự cố — Tự động chuyển sang..."* và nhảy máy chủ liên tục (kèm ảnh minh họa `Tiên Nghịch Tập 21`); yêu cầu khắc phục triệt để và tối ưu nguồn phát hiệu quả nhất.
- **Nguyên nhân cốt lõi đã chẩn đoán trực tiếp trên luồng thực tế:**
  1. `SanitizingTsDataSource` chỉ kiểm tra đuôi `.png/.jpg/.jpeg/.webp/.gif` với buffer `2048` byte nên **bỏ sót toàn bộ phân đoạn PNG ngụy trang MPEG-TS không có đuôi ảnh** (như `tiktokcdn.com/obj/ad-site-i18n/...` offset `271` của `o1/sever2` và `streamrpt1 /embed/ storage=drive -> lh3.googleusercontent.com` offset `941`), khiến Media3 ExoPlayer báo lỗi ngay tại phân đoạn đầu tiên; đồng thời `SimpleCache` cũ (`media3_hls_cache`) giữ lại phân đoạn lỗi chưa cắt header.
  2. `YanHh3dNowScraper` chưa giải mã nút `1080+` (`/embed/` có `master.m3u8?storage=${storage}`), chưa đọc `data-stream-url`/`var cccc` trên một số tập `o1/sever2`, còn fallback `sU` (payload mã hóa `#ENC-AESGCM`), và đánh dấu nhầm `streamc.xyz` (Cloudflare Turnstile HTML) là server HLS trực tiếp.
  3. `MovieRepository.backfillM3u8` sao chép cùng một link `PhimAPI` sang 4–8 server `YanHH3D`, còn `selectPreferredServerIdx` xếp `epCount` trước cấp nguồn nên chọn `PhimAPI`/`YanHH3D` thay vì `YanHH3D Now` (tập gộp `21-25` ít item hơn), khiến khi có lỗi thì `tryAutoFailover()` nhảy vòng lặp qua 4–5 máy chủ trùng cùng một URL lỗi.
- **Giải pháp đã triển khai:**
  1. **[HlsCacheProvider.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/HlsCacheProvider.kt):**
     - Nâng cấp `SanitizingTsDataSource` kiểm tra magic byte (`PNG`, `GIF`, `JPEG`, `RIFF/WEBP`, `BMP`) cho mọi phân đoạn non-playlist (`position == 0`) bất kể URL có đuôi `.png` hay không, với peek buffer `8192` byte (`INITIAL_PEEK_BYTES = 512`).
     - Chuyển cache sang `media3_hls_cache_v2` và tự dọn cache `v1` cũ; tạo `streamingOkHttpClient` (`connectTimeout 12s`, `readTimeout 25s`, tự gắn `Referer`/`Origin` cho `streamrpt1` và `Accept: */*`) cùng `DefaultLoadErrorHandlingPolicy(5)` cho cả HLS và MP4.
  2. **[YanHh3dNowScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dNowScraper.kt):**
     - Bỏ `streamc.xyz` khỏi `isDirectNativeServer` và bỏ fallback `sU` khỏi `decodeStreamrptObf`.
     - Bổ sung `extractEmbedMasterM3u8` (xử lý `master.m3u8?storage=drive`, `data-stream-url`, `var cccc` HLS) và tự thử các nút native khác cùng tập trong `resolveStream` nếu nút đầu không trả `.m3u8`/`.mp4`.
  3. **[MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt), [PlayerHelpers.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerHelpers.kt) & [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt):**
     - Gỡ bỏ `backfillM3u8` (không nhân bản link PhimAPI cho 8 máy chủ `YanHH3D`); nâng ưu tiên nguồn trong `selectPreferredServerIdx` (`YanHH3D Now` > `Trình phát Tích hợp` > khác); hỗ trợ đối chiếu tập gộp `Tập 21-25` trong `PlayerUtils.findEpisodeIdxByNumber`.
     - Thêm retry 1 lần tại chỗ (`400ms`) khi gặp lỗi IO mạng tạm thời (`ERROR_CODE_IO_*`), theo dõi `failedStreamUrls` để `tryAutoFailover()` bỏ qua các máy chủ trùng URL đã hỏng.
- **Kiểm tra thực tế:**
  - Mở rộng unit tests trong [YanHh3dNowScraperTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/YanHh3dNowScraperTest.kt), [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt) và [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **24/24 tests PASS** (`NguonCApiAndRepoTest` 6, `PlayerHelpersTest` 3, `PrefsStoreTest` 3, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,313,269 bytes`).

---

### [2026-11-26] - Phiên 25: Bổ Sung Dark Mode + Thanh Độ Trong Suốt Liquid Glass & Sửa Lỗi Nền Ám Xanh Phát Sáng Trên Trình Phát
- **Vấn đề / Yêu cầu:**
  1. Bổ sung **chế độ Dark Mode** và **thanh tăng giảm độ trong suốt giao diện Liquid Glass** trong mục **5. Giao diện** của Cài đặt.
  2. Sửa lỗi trình phát video (`PlayerActivity`) bị **nền ám xanh phát sáng** bao quanh toàn bộ viền trên/dưới/hai bên màn hình phát (kèm ảnh minh họa).
- **Nguyên nhân cốt lõi & Giải pháp:**
  1. **Sửa triệt để lỗi nền ám xanh phát sáng trên Player ([bg_ambient_cinema_glow.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/bg_ambient_cinema_glow.xml), [Models.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/model/Models.kt), [PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt)):**
     - Nguyên nhân: `ambientGlowView` (phủ `match_parent` lên trên `playerView` trong `activity_player.xml`) dùng drawable `bg_ambient_cinema_glow.xml` chứa gradient tỏa màu xanh mint `#292CE59B` + đường viền `<stroke>` `2dp` màu `#332CE59B`, đồng thời `ExtensionSettings.ambientGlow` trước đó mặc định bật `true`.
     - Khắc phục: Loại bỏ hoàn toàn màu xanh `#2CE59B` và khung `<stroke>` khỏi `bg_ambient_cinema_glow.xml` (chuyển sang hiệu ứng tối dịu rìa trung tính `#1A000000` không ám màu) để kể cả máy nâng cấp từ bản cũ vẫn hết ám xanh ngay lập tức; đặt `ambientGlow = false` mặc định trong `ExtensionSettings` và `PrefsStore.getSettings()`; cập nhật tên mục trong Tiện ích thành *"Làm Dịu Viền Rạp Phim (Cinema Vignette)"*.
  2. **Chế độ Dark Mode (`☀ Sáng` / `🌙 Tối (Dark)` / `⚙ Hệ thống`) & Thanh độ trong suốt Liquid Glass (`10%–90%`) ([ThemeManager.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/ThemeManager.kt), [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt)):**
     - Thêm `DarkModePreference` (`light`, `dark`, `system`), `AppSettings.darkMode` (mặc định `"light"`) và `AppSettings.glassOpacity` (mặc định `35`, phạm vi `10..90%`), lưu/khôi phục đầy đủ qua `PrefsStore`.
     - Mở rộng `ThemeManager.palette(...)` hỗ trợ bảng màu **Dark Mode OLED/Aurora** cho cả 4 tông chủ đề (`Pearl Dark`, `Abyss Aqua Dark`, `Midnight Sakura Dark`, `Dark Aurora`) và tính trực tiếp alpha của `glassSurface` (`38..238`), `glassCard` (`62..244`) & `inputBg` theo phần trăm độ trong suốt `glassOpacity`.
     - Trong `SettingsFragment.renderAppearanceTab`: thêm 3 nút chuyển nhanh `☀ Sáng | 🌙 Tối (Dark) | ⚙ Hệ thống`, thanh kéo **Độ trong suốt Liquid Glass: XX%** (xem trước tức thì khi kéo, commit mượt ở `onStopTrackingTouch` & hỗ trợ D-Pad Trái/Phải bước 5%) kèm 3 nút chọn nhanh `Kính đục (20%)`, `Cân bằng (45%)`, `Trong suốt (75%)`; thêm `onConfigurationChanged` trên `MainActivity` để tự đổi sáng/tối khi chọn chế độ `⚙ Hệ thống`.
- **Kiểm tra thực tế:**
  - Cập nhật [PrefsStoreTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt) kiểm tra `darkMode`, `glassOpacity`, `ambientGlow = false` mặc định, sao lưu/khôi phục JSON và `resetSettingsToDefaults()`.
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **24/24 tests PASS** (`NguonCApiAndRepoTest` 6, `PlayerHelpersTest` 3, `PrefsStoreTest` 3, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,313,269 bytes`).

---

### [2026-11-26] - Phiên 24: Xóa Bỏ 100% Chế Độ Embed Khỏi Trình Phát (Phương Án 2 — Chỉ Phát HLS/MP4 Thuần Trên Media3 ExoPlayer)
- **Vấn đề / Yêu cầu:** Thực hiện Phương án 2: xóa bỏ 100% chế độ phát `Embed` khỏi trình phát (`EmbedFallbackView`, `useEmbedNow`, `preferEmbedMode`, `btnFallbackEmbed`, `btnEmbedReload`, `btnSwitchToHls` trong `PlayerActivity.kt` và `activity_player.xml`); chỉ hiển thị và phát các máy chủ `HLS/MP4` thuần trên Media3 ExoPlayer (nếu phim chỉ có `streamfree.vip` / Embed và không có `.m3u8`/`.mp4` trực tiếp sẽ báo lỗi không có luồng trực tiếp).
- **Giải pháp đã triển khai:**
  1. **[activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml):**
     - Gỡ bỏ hoàn toàn `<com.hoathinh3d.donghua.player.EmbedFallbackView android:id="@+id/embedFallbackView" .../>`, `btnFallbackEmbed`, `btnEmbedReload`, và `btnSwitchToHls`.
     - Bổ sung `txtErrorTitle` & `txtErrorSubtitle` trong `errorOverlay` với thông báo rõ ràng khi thiếu luồng trực tiếp HLS/MP4.
  2. **[PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt):**
     - Gỡ bỏ hoàn toàn `EmbedFallbackView` (kể cả sniffer & callback), `useEmbedNow`, `preferEmbedMode`, `currentActiveEmbed`, `embedProgressJob`, `customEmbedFullscreenView`, `customEmbedCallback`, `setupEmbedViewCallbacks()`, `switchFromEmbedToExo()`, `switchToEmbedFallback()`, cùng các nhánh `useEmbedNow` trong `setupControls()`, `setupGestures()`, `updatePlaybackProgressUi()`, `onKeyDown()`, `wakeHud()`, `onUserLeaveHint()` và `onDestroy()`.
     - Cập nhật `loadCurrentEpisodeStream()`, `preResolveNextEpisode()`, `tryAutoFailover()`, `toggleAudioQuick()`, `renderSourcesDrawer()` và `showNoDirectStreamError()`: chỉ phát qua Media3 ExoPlayer với URL `.m3u8`/`.mp4` hoặc file offline; khi chỉ có liên kết nhúng như `streamfree.vip` mà không có luồng trực tiếp ở bất kỳ máy chủ `isDirectStreamServer` nào, hiển thị ngay `errorOverlay` báo *"Không có luồng phát trực tiếp"* (không fallback sang WebView Embed hay URL mẫu).
  3. **[MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt) & [SettingsFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/settings/SettingsFragment.kt):**
     - Cập nhật `MovieRepository.visibleServerIndices` chỉ trả về danh sách máy chủ `isDirectStreamServer` (trả về danh sách rỗng nếu phim chỉ có Embed-only).
     - Chỉnh mô tả `AdBlock Shield Pro` trong `SettingsFragment.kt` bỏ tham chiếu `WebView dự phòng`.
- **Kiểm tra thực tế:**
  - Cập nhật unit test `visibleServerIndices_onlyReturnsDirectHlsServers` trong [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt) (giữ `EmbedFallbackView.kt` companion helpers cho `YanHh3dNowScraper.kt` và test parser).
  - Xác nhận bằng `Select-String`: 0 tham chiếu tới `EmbedFallbackView`, `useEmbedNow`, `preferEmbedMode`, `btnFallbackEmbed`, `btnEmbedReload`, `btnSwitchToHls` trong `PlayerActivity.kt` & `activity_player.xml`.
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **24/24 tests PASS** (`NguonCApiAndRepoTest` 6, `PlayerHelpersTest` 3, `PrefsStoreTest` 3, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,313,269 bytes`).

---

### [2026-11-26] - Phiên 23: Thực Thi Hóa Toàn Bộ 7 Mục Cài Đặt & Nối Trực Tiếp Vào Hệ Thống / Trình Phát
- **Vấn đề / Yêu cầu:** Thực thi hóa toàn bộ các tính năng phụ bên trong 7 mục Cài đặt (*Tài khoản, Tổng quan App, Trình phát tích hợp, Nguồn phim, Giao diện, Cập nhật & Sao lưu, Tiện ích mở rộng*) để hoạt động thực tế, tối ưu và tương thích đa thiết bị (Mobile / Tablet / Android TV).
- **Các cải tiến thực thi cụ thể trên từng mục:**
  1. **Tài khoản (`renderAccountTab` & `MainActivity.maybeAutoCloudSync`):**
     - `cloudAutoSync` nay tự động chụp snapshot mỗi khi thêm/xóa tủ phim, đổi nguồn, xóa lịch sử, cập nhật cài đặt hoặc xem xong trong trình phát; hiển thị số liệu snapshot thực (`PrefsStore.getCloudSnapshotStats()`), nút `✏ Sửa hồ sơ` và khôi phục đồng bộ toàn trạng thái qua `reloadAllPersistedState()`.
  2. **Tổng quan App (`renderOverviewTab`):**
     - Bổ sung thống kê bộ nhớ Offline HLS + bộ nhớ đệm phát `SimpleCache` (`HlsCacheProvider.getCacheSizeBytes`), nút `🔄 Làm mới kho phim đang chọn` (`reloadActiveSourceFeed()`) và phím tắt `openSamplePlayer()` tự ưu tiên mở tập đang xem dở gần nhất.
  3. **Trình phát tích hợp (`renderPlayerTab` & [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt)):**
     - Nối thật `settings.defaultQuality` (`4K 60FPS` -> 2160p, `2K HDR` -> 1440p, `1080p Pro` -> 1080p, `Auto` -> không giới hạn) vào `DefaultTrackSelector` qua `PlayerUtils.maxHeightForQualityPreset`; thêm tốc độ `0.5x`, nhãn tỷ lệ khung hình tiếng Việt dễ hiểu, lưu lại tỷ lệ khung hình khi đổi trong trình phát và thêm nút dọn `SimpleCache` (`HlsCacheProvider.clearCache`).
  4. **Nguồn phim +Web (`renderSourcesTab` & [CustomSourceParser.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/CustomSourceParser.kt)):**
     - Thay placeholder cứng bằng quét phim thật (`CustomSourceParser.fetchFromCustomSource` + `extractDirectStreamsFromWebUrl`) và `PrefsStore.addCustomMovies`; sửa `probeCustomSource` trả về trạng thái kết nối thực (`ok = true/false` + số luồng/mục phát hiện được); thêm bộ chọn Loại trình phân tích & Chất lượng gắn nhãn; tự chuyển sang nguồn hoạt động tiếp theo nếu tắt nguồn đang ưu tiên.
  5. **Giao diện (`renderAppearanceTab` & [HistoryFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/history/HistoryFragment.kt)):**
     - Thêm xử lý `KEYCODE_DPAD_LEFT/RIGHT` + debounce cho thanh trượt `Glass Blur` trên TV Box; đồng bộ `HistoryFragment` tự cập nhật số cột lưới (`1/2/3`) ngay khi đổi chế độ thiết bị.
  6. **Cập nhật & Sao lưu (`renderBackupTab` & [PrefsStore.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/PrefsStore.kt)):**
     - Đọc trực tiếp `BuildConfig.VERSION_NAME/VERSION_CODE/APPLICATION_ID`; nút `🔄 Kiểm tra hệ thống & nguồn` đo độ trễ thực tế tới các nguồn đang bật; bổ sung nút dọn Cache HLS và `↺ Mặc định` (`resetSettingsToDefaults()` kèm hộp thoại xác nhận, giữ nguyên Lịch sử & Tủ phim).
  7. **Tiện ích mở rộng & Sleep Timer (`renderExtensionsTab`, [activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml), `PlayerActivity.kt`):**
     - Tạo [bg_ambient_cinema_glow.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/drawable/bg_ambient_cinema_glow.xml) và đặt `ambientGlowView` lên viền khung phát (`playerView` không còn che mất); đồng bộ `bilingualSub` giữa Trình phát ↔ Cài đặt và cấu hình `playerView.subtitleView` (`CaptionStyleCompat`); làm rõ phạm vi `AI HDR Upscale` theo Android 12+ (`API 31+`) và cho Sleep Timer dừng cả `embedFallbackView` khi hết giờ.
- **Kiểm tra thực tế:**
  - Bổ sung unit tests trong [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt) (`maxHeightForQualityPreset`) và [PrefsStoreTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PrefsStoreTest.kt) (`cloudSnapshotStats_and_resetSettingsToDefaults_workCorrectly`).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **24/24 tests PASS** (`NguonCApiAndRepoTest` 6, `PlayerHelpersTest` 3, `PrefsStoreTest` 3, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,313,365 bytes`).

---

### [2026-11-26] - Phiên 22: Tinh Gọn UI Trình Phát Theo Phương Án 1 (Ẩn Server Embed-Only & Bỏ Nút Chuyển Embed Thủ Công)
- **Vấn đề / Yêu cầu:** Giải thích vai trò của nguồn & máy chủ phát `Embed` và tinh gọn trình phát theo Phương án 1 (ẩn giao diện/nút chuyển `Embed` thủ công khi phim đã có luồng trực tiếp `HLS/MP4`, chỉ giữ `EmbedFallbackView` làm dự phòng ngầm cuối cùng cho phim độc quyền `yanhh3d.ee` không có `.m3u8`).
- **Giải pháp đã triển khai:**
  1. **[MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt):**
     - Bổ sung `isDirectStreamServer(server)` và `visibleServerIndices(serverGroups, includeIdx)`: tự động lọc bỏ các máy chủ chỉ có `Embed` (như `DLM`, `HYD`, hoặc `YanHH3D` chưa backfill `.m3u8`) khi phim đã có ít nhất 1 máy chủ `HLS/MP4` trực tiếp; giữ nguyên danh sách nếu phim hoàn toàn chỉ có `Embed` để vẫn xem được qua dự phòng ngầm.
  2. **[MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt) & [PlayerActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt):**
     - Áp dụng `MovieRepository.visibleServerIndices` cho hàng chip máy chủ trong màn hình Chi tiết phim, `renderSourcesDrawer()` và `toggleAudioQuick()` trong trình phát.
     - Gỡ bỏ nút chuyển thủ công `"Đang dùng ExoPlayer (Đổi Embed)"` khỏi `renderSourcesDrawer()`, bỏ nhãn thừa `"• HLS/MP4" / "• Embed"` trên tên server và ẩn nút `"Phát qua Embed Web"` (`btnFallbackEmbed`) trên màn hình lỗi `errorOverlay` (cơ chế `tryAutoFailover` và `switchToEmbedFallback` vẫn chạy ngầm khi phim không có bất kỳ luồng `.m3u8`/`.mp4` nào).
- **Kiểm tra thực tế:**
  - Bổ sung test `visibleServerIndices_hidesEmbedOnlyWhenDirectHlsExists` trong [NguonCApiAndRepoTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **23/23 tests PASS** (`NguonCApiAndRepoTest` 6, `PlayerHelpersTest` 3, `PrefsStoreTest` 2, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,313,080 bytes`).

---

### [2026-11-26] - Phiên 21: Thiết Kế Lại Giao Diện Theo Ngôn Ngữ Apple iOS 27 Liquid Glass (Spatial Layering, Specular Rim & Concentric Geometry)
- **Vấn đề / Yêu cầu:** Tham khảo ngôn ngữ thiết kế **Liquid Glass** của Apple (iOS 26/27) và áp dụng tối ưu cho ứng dụng HoatHinh3D Native Android (giữ nguyên mượt mà trên TV Box 2GB, tối ưu Mobile edge-to-edge và giữ layout trình phát CloudStream v4.9.1).
- **Giải pháp thiết kế & kỹ thuật đã triển khai:**
  1. **Tầng Quang Học Liquid Glass & Hình Học Đồng Tâm ([ThemeManager.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/core/ThemeManager.kt)):**
     - Kết nối thanh trượt `glassBlur` (8–40px) trong Cài đặt vào `ThemeManager.palette(themeWire, glassBlurPx)` để điều chỉnh độ mờ/trong suốt của vật liệu kính theo thời gian thực (tương tự *Transparency Slider* trên iOS 27).
     - Bổ sung `liquidGlassSheetDrawable` (lớp phủ 2 tầng `LayerDrawable`: nền kính mờ + lớp phản quang chéo `specularSheenLayer` + viền sáng cạnh vát + viền focus TV `#06B6D4`) dành riêng cho **tầng điều khiển nổi** (Header, Tab Capsule, Toast, nút nổi).
     - Phân tách rõ **Controls vs. Content Layering**: `glassPanelDrawable(..., isCard = true)` dùng vật liệu mỏng nhẹ hơn cho thẻ nội dung; bổ sung `applyRoundedOutline` (`ViewOutlineProvider` + `clipToOutline = true`) thực thi bo góc đồng tâm (`card 20dp` / `padding 5dp` -> `poster 15dp`).
  2. **Kiến Trúc Không Gian Nổi & Thanh Tab Capsule iOS 27 ([activity_main.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_main.xml), [MainActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/MainActivity.kt)):**
     - Chuyển `contentContainer` sang tràn toàn màn hình bên dưới `headerContainer` nổi phía trên và `bottomNavContainer` dạng **Capsule nổi (`30dp`)** phía dưới; bổ sung `ChromeInsetsListener` & `getChromeInsets()` để cả 5 tab (`HomeFragment`, `ScheduleFragment`, `HistoryFragment`, `FavoritesFragment`, `SettingsFragment`) cuộn nội dung xuyên qua sau lớp kính mờ một cách tự nhiên.
     - Thay toàn bộ icon emoji ở Header và Bottom Tab bằng bộ **vector SF-inspired** sắc nét (`ic_nav_explore.xml`, `ic_nav_schedule.xml`, `ic_nav_history.xml`, `ic_nav_favorites.xml`, `ic_nav_settings.xml`, `ic_lg_search.xml`, `ic_lg_settings_gear.xml`), kết hợp **Liquid Lens (`navTabDrawable`)** cho tab đang chọn và hiệu ứng chuyển tab `fade_in`/`fade_out`.
  3. **Tinh Chỉnh Card, Hero, Chi Tiết Phim & Trình Phát ([MovieCardAdapter.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/common/MovieCardAdapter.kt), [HomeFragment.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/home/HomeFragment.kt), [MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt), [activity_player.xml](file:///E:/Project/3_HoatHinh3D/android/app/src/main/res/layout/activity_player.xml)):**
     - Bo tròn đồng tâm cho `heroCard` (`24dp`), `posterFrame` (`15dp`), `continuePosterFrame` (`12dp`), `historyPosterFrame` (`13dp`), `imgSchedPoster` (`12dp`), `imgOfflinePoster` (`12dp`), `imgDetailPoster` (`16dp`), `imgHeaderAvatar` (`12dp`) và các badge trên thẻ phim.
     - Áp dụng lớp phủ chuyển sắc mượt `bg_hero_scrim.xml` cho Hero Banner & Chi tiết phim; nút `Chi Tiết & Tập` và `btnCloseDetail` dùng kính mờ nổi.
     - Trong `PlayerActivity`: nâng cấp `topBar` & `bottomBar` sang dải chuyển sắc rạp phim mượt (`bg_player_top_scrim.xml`, `bg_player_bottom_scrim.xml`), `playerDrawer` dùng kính tối bo góc trái (`bg_player_drawer_glass.xml`) và `bg_player_overlay_card.xml` có viền specular.
- **Kiểm tra thực tế:**
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **22/22 tests PASS** (`NguonCApiAndRepoTest` 5, `PlayerHelpersTest` 3, `PrefsStoreTest` 2, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,313,080 bytes`).

---

### [2026-11-25] - Phiên 20: Kiểm Tra & Tối Ưu Toàn Diện UI / Điều Hướng D-Pad Trên Android TV Box & Mobile
- **Vấn đề / Yêu cầu:** Kiểm tra và tối ưu giao diện (UI) cũng như khả năng điều khiển D-Pad trên Android TV Box và cảm ứng/hiển thị trên điện thoại Android (bao gồm Android 15 edge-to-edge).
- **Các điểm yếu thực tế đã phát hiện & khắc phục triệt để:**
  1. **Trình phát `PlayerActivity` & `activity_player.xml`:**
     - **Sửa lỗi D-Pad `onKeyDown`:** Trước đây `wakeHud()` được gọi trước khi kiểm tra `!showHud`, khiến `!showHud` luôn `false`; đồng thời `DPAD_LEFT`/`DPAD_RIGHT` khi đang focus `btnPlayPause` lại gọi `seekByStep()` làm nút Tua lùi/Tua tới không thể focus được, và ở chế độ Embed (`useEmbedNow = true`) các phím D-Pad vẫn điều khiển ExoPlayer đã dừng. Đã tách `wasHudVisible`, mở khóa di chuyển trái/phải từ `btnPlayPause` sang `btnRewind`/`btnForward`, cho phép D-Pad điều hướng tự do trong chế độ Embed và cập nhật `updateHudFocusRouting` theo trạng thái HLS/Embed.
     - **Focus ring & Focus trapping cho toàn bộ Drawer & Overlay:** Bổ sung `bg_cs_pill_mint.xml`, `bg_cs_pill_dark.xml`, `bg_cs_pill_danger.xml`, `bg_player_overlay_card.xml` và `drawerItemBackground(...)` để mọi nút trong `playerDrawer` (Danh sách tập, Nguồn, Cài đặt phát, Chất lượng, Tốc độ), `btnUnlockScreen`, `btnSkipIntro`, `resumePromptPill`, `errorOverlay`, `autoNextOverlay` đều có viền focus xanh mint `#2CE59B`; chặn focus lọt ra sau `hudContainer` (`FOCUS_BLOCK_DESCENDANTS`) khi drawer mở và khôi phục lại đúng view trước đó khi đóng.
     - **Tối ưu Mobile Portrait & Cutout:** Tự động chuyển hàng 6 nút dưới (`bottomActionsRow`) sang bố cục dọc gọn (`48dp`, `9.5sp`) khi điện thoại khóa xoay dọc (`ORIENTATION_PORTRAIT` / `screenWidthDp < 520`) để không bị cắt chữ `"Chỉnh phụ đề"` / `"Thuyết minh"`, giữ nguyên hàng ngang CloudStream khi xoay ngang/TV; bổ sung padding an toàn vùng tai thỏ/camera (`applyPlayerCutoutPadding`) và đồng bộ cờ `bilingualSub`.
  2. **Màn hình chính, Header, Bottom Nav & 4 Tab còn lại (`MainActivity.kt`, `HomeFragment.kt`, `MovieCardAdapter.kt`, `ScheduleFragment.kt`, `HistoryFragment.kt`, `FavoritesFragment.kt`, `SettingsFragment.kt`):**
     - **Edge-to-Edge Android 15 (`targetSdk 35`) & TV Overscan:** Áp dụng `ThemeManager.applyEdgeToEdgePadding` trên `MainActivity` và `MovieDetailActivity` để Header/nút Đóng không chui dưới Status Bar/tai thỏ và thanh Bottom Nav/Scroll không bị che bởi thanh điều hướng cử chỉ, đồng thời tăng lề ngang an toàn cho TV (`22dp–28dp`).
     - **Loại bỏ giật lag & rớt focus do Sleep Timer 1s:** Tách `SleepTimerListener` để `sleepJob` chỉ cập nhật trực tiếp TextView đếm ngược trong `SettingsFragment` thay vì gọi `notifyActiveFragment()` mỗi giây; trong `HomeFragment` tái sử dụng `tvCarouselsContainer` và `imgHeroBg` khi dữ liệu không đổi.
     - **Điều hướng TV D-Pad & Thẻ phim (`ThemeManager.kt`, `MovieCardAdapter.kt`):** Thêm `navTabDrawable`, `tintedFocusDrawable`, `attachTvCardFocusEffect` (phóng nhẹ `1.035x` + nâng bóng khi focus trên TV); tắt `focusable` trên nút tim nhỏ `btnFavHeart` khi ở chế độ TV (thay bằng nhấn giữ `onLongClick` trên thẻ phim) để D-Pad lướt mượt giữa các thẻ; thêm highlight cho 5 tab Bottom Nav và sửa màu chữ `Spinner` nguồn phim theo Dark/Light theme.
     - **Cài đặt, Lịch chiếu, Lịch sử & Chi tiết phim (`SettingsFragment.kt`, `ScheduleFragment.kt`, `HistoryFragment.kt`, `MovieDetailActivity.kt`):** Làm toàn bộ `switchRow` focusable/clickable (tắt focus lồng trên `Switch`), đưa `Switch` nguồn phim lên góc phải tiêu đề thẻ để không chật chội trên Mobile 360dp, giữ nguyên D-Pad focus sau khi đổi tab ngày/server/cụm tập/cài đặt (`pendingFocusTag`), và thêm `resolveMovieForProgress` giúp bấm vào Lịch sử / Tiếp tục xem luôn phát được ngay cả khi đã đổi nguồn phim.
- **Kiểm tra thực tế:**
  - Bổ sung test `aspectMode_cyclesAndParsesSafely` trong [PlayerHelpersTest.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/test/java/com/hoathinh3d/donghua/PlayerHelpersTest.kt).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **22/22 tests PASS**, 0 failures, 0 errors, 0 skipped (`NguonCApiAndRepoTest` 5, `PlayerHelpersTest` 3, `PrefsStoreTest` 2, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,310,026 bytes`).

---

### [2026-11-25] - Phiên 19: Nghiên Cứu Sâu & Nâng Cấp Toàn Diện Nguồn `https://yanhh3d.ee/` (Phân Loại, Tập Phim, 8 Server & Tối Ưu `streamfree.vip`)
- **Vấn đề / Yêu cầu:** Nghiên cứu kỹ nguồn `https://yanhh3d.ee/` để lấy toàn bộ phân loại phim, chi tiết phim chuẩn xác, đủ 8 server, đầy đủ danh sách tập và tối ưu tốc độ load & phát video.
- **Kết quả nghiên cứu trực tiếp trên `https://yanhh3d.ee/` & `streamfree.vip`:**
  1. Phát hiện thêm taxonomy WP-JSON `post_options=11` (Đề Cử Hot - 126 phim) và 2 trang xếp hạng/cập nhật HTML `.flw-item` chính thức (`/yanhh3d-moi-cap-nhat` và `/top-yanhh3d-xem-nhieu` 25 trang) ngoài 9 categories & 2 status (`ongoing`, `completed`).
  2. Trong `parseDetailHtmlOnly` Strategy 2, nút `Xem phim` (`btn-play` trỏ `/tap-N.html`) đứng trước danh sách `.ep-item[data-ep]` khiến `seenEp.add(...)` giữ nhầm nút `btn-play` bị thiếu `data-post-id` / `data-sv`, làm lỗi gọi AJAX trên một số trang chi tiết.
  3. Reverse-engineer `streamfree.vip/public/static/app.15de130c.js` (iframe mà cả 8 server `tiktik`, `pro`, `vip4k`, `vip4k_v2`, `tiktm`, `pro_tm`, `vip4ktm`, `vip4ktm_v2` của `yanhh3d.ee` trả về): `streamfree.vip` gọi `/hls/<uuid>.m3u8?vid=...` nhưng phản hồi của nó là **payload mã hóa `#ENC-AESGCM;iv=...`** (giải mã trong JS bằng WebCrypto + ServiceWorker `/sw.js`), KHÔNG phải `#EXTM3U` thuần! Trước đây `EmbedFallbackView` bắt nhầm URL này ném vào ExoPlayer khiến ExoPlayer lỗi `ParserException`, nhảy failover liên tục 10–20s và thậm chí ngắt nhầm WebView đang phát.
- **Giải pháp đã triển khai:**
  1. **[YanHh3dScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dScraper.kt) & [MovieRepository.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt):**
     - Bổ sung vào `fetchTaxonomies` các phân loại còn thiếu: `Mới Cập Nhật` (`yanhh3d-moi-cap-nhat`), `Top Xem Nhiều` (`top-yanhh3d-xem-nhieu`), `Đề Cử Hot` (`post_options=11`), nối với `fetchArchiveHtmlMovies` (`parseArchiveHtmlCards`) và `fetchMovies(..., postOptionsId = 11)`.
     - Sửa `parseTickRateBadge` hỗ trợ badge `FULL HD [4K]` (`latestEp=1, totalEp=1`), mở rộng `fetchHomeEpisodeMeta` quét song song `/` + `/yanhh3d-moi-cap-nhat`.
     - Sửa `parseDetailHtmlOnly` ưu tiên `.ep-item` / `.ssl-item` (`data-ep`) trước `btn-play`, trích xuất `poster` từ `og:image` / `.film-poster-img`, và tăng ngưỡng hợp nhất server tập đầu/tập cuối lên `< 8` để lấy đủ 8 server.
  2. **[EmbedFallbackView.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/player/EmbedFallbackView.kt):**
     - Thêm `isEncryptedEmbedHost(url)` nhận diện `streamfree.vip` / `streamfreevip.vip` / `abysscdn.com`; cập nhật `isM3u8StreamUrl` & `extractM3u8FromHtml` bỏ qua hoàn toàn URL `/hls/*.m3u8` mã hóa `#ENC-AESGCM` của các host này để không bao giờ gây crash ExoPlayer hay ngắt nhầm WebView đang phát.
     - Cập nhật `sniffM3u8Url` trả về `null` ngay lập tức (0ms) với `isEncryptedEmbedHost`, bỏ qua thời gian chờ timeout 4.5s khi mở/chuyển tập `yanhh3d.ee`; bật `settings.databaseEnabled = true` hỗ trợ WebView ServiceWorker ổn định.
  3. **[MovieDetailActivity.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/ui/detail/MovieDetailActivity.kt):**
     - Dọn cảnh báo `Condition is always 'true'` tại dòng 175 (smart-cast `continue`).
- **Kiểm tra thực tế:**
  - Bổ sung tests trong `YanHh3dScraperTest.kt` (FULL HD badge, `parseArchiveHtmlCards`, ưu tiên `.ep-item` giữ `wpPostId`/`wpServerSv`) và `NguonCApiAndRepoTest.kt` (`isEncryptedEmbedHost` & bỏ qua `streamfree.vip/hls/` trong `isM3u8StreamUrl`).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **21/21 tests PASS**, 0 cảnh báo Kotlin (`NguonCApiAndRepoTest` 5, `PlayerHelpersTest` 2, `PrefsStoreTest` 2, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 5).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,308,849 bytes`).

---

### [2026-11-25] - Phiên 18: Gỡ Bỏ NguonC, Tích Hợp Toàn Diện `https://yanhh3d.now/`, Giải Mã StreamRPT1 + PNG-TS & Redesign Trình Phát Phong Cách CloudStream APK v4.9.1
- **Vấn đề / Yêu cầu:**
  1. Bỏ nguồn `NguonC` khỏi mục nguồn phim.
  2. Nghiên cứu sâu `https://yanhh3d.now/`, tích hợp thành nguồn chính lấy toàn bộ phân loại phim, chi tiết phim, các server phát, đầy đủ tập phim và tối ưu luồng phát.
  3. Thiết kế lại trình phát của app theo giao diện **CloudStream APK v4.9.1** (theo ảnh minh họa đính kèm).
- **Kết quả nghiên cứu `https://yanhh3d.now/` & Giải pháp kỹ thuật:**
  1. **Kiến trúc `yanhh3d.now` & Scraper mới ([YanHh3dNowScraper.kt](file:///E:/Project/3_HoatHinh3D/android/app/src/main/java/com/hoathinh3d/donghua/data/source/YanHh3dNowScraper.kt)):**
     - `yanhh3d.now` chạy Laravel (không dùng `/wp-json/`), phân loại qua HTML (`/moi-cap-nhat`, `/dang-chieu`, `/hoan-thanh`, `/hoat-hinh-4k`, `/hoat-hinh-3d`, `/hoat-hinh-2d`, `/hoat-hinh-ai`, `/phim-le`, `/the-loai/*` — tổng 18+ danh mục), tìm kiếm `/search?keysearch=...` và lịch chiếu AJAX CSRF `POST /ajax/sever/load-lich-chieu`.
     - Trang xem phim chứa đủ 2 tab audio (`#top-comment` = `Thuyết Minh`, `#new-comment` = `Vietsub` dùng `/sever2/...`), toàn bộ danh sách tập `.ssl-item.ep-item` và danh sách máy chủ `#list_sv .btn3dsv` (`4K`, `4K-`, `1080`, `1080-`, `HD`, `NC`, `DLM`, `HYD`).
     - Các server `4K / 4K- / 1080 / 1080-` trỏ đến `streamrpt1.site/*.m3u8` (trang player có `#player[data-obf]` Base64 JSON chứa `pU = .../stream-plain?t=...` trả về `#EXTM3U` thuần), còn server `HD` (`play-fb-v8/play/...`) chứa trực tiếp `var cccc = "https://...fbcdn.net/...mp4"`.
     - **Khử PNG ngụy trang MPEG-TS (`HlsCacheProvider.kt`, `HlsOfflineManager.kt`):** các phân đoạn `.png` trong `stream-plain` thực chất là MPEG-TS (`0x47` lặp mỗi 188 byte) có gắn thêm `271` byte PNG + `0xFF` giả phía trước. Đã bổ sung `SanitizingTsDataSource` (qua `findPngCamouflageTsOffset`) vào upstream `CacheDataSource` và `HlsOfflineManager` để cắt bỏ tự động header ảnh giả, giúp ExoPlayer + `SimpleCache` phát trực tiếp mượt với `0ms` seek mà không cần WebView Embed.
  2. **Gỡ `NguonC` & Đặt `YanHH3DNow` mặc định (`InitialData.kt`, `Models.kt`, `PrefsStore.kt`, `MovieRepository.kt`, `CustomSourceParser.kt`, `SettingsFragment.kt`, `ScheduleFragment.kt`, `MainActivity.kt`, `MovieDetailActivity.kt`):**
     - Thay `NguonC` bằng `YanHH3DNow` (`https://yanhh3d.now`) làm nguồn mặc định đầu tiên bên cạnh `YanHH3D` (`https://yanhh3d.ee`), kèm key migration `hh3d_sources_v5_yan_now_no_nguonc` trong `PrefsStore` giữ nguyên các nguồn web custom người dùng đã thêm.
     - Cập nhật `ScheduleFragment` lấy song song lịch chiếu từ `YanHh3dNowScraper.fetchScheduleByDay` + `YanHh3dScraper`; cập nhật `MovieDetailActivity` hỗ trợ tải offline cho cả luồng `.m3u8` động của `yanhh3d.now`.
     - Cập nhật `selectPreferredServerIdx` & `tryAutoFailover` nhận diện `wpServerSv == "hls"` cho các server `now:` để luôn ưu tiên `Thuyết Minh` phát native ExoPlayer trước.
  3. **Giao diện Trình Phát CloudStream APK v4.9.1 (`activity_player.xml`, `PlayerActivity.kt`, `PlayerHelpers.kt`, 17 drawables `ic_cs_*` / `bg_cs_*` / `thumb_cs_seekbar.xml`):**
     - Top bar tối giản cân giữa tên phim (`#000000`), nút back trái sắc nét và nút `Danh sách tập` / `Tập sau` gọn ở góc phải.
     - Cụm điều khiển trung tâm chuẩn CloudStream vector trắng trong suốt: `↺10` (cập nhật động theo `seekStep`), nút `▌▌ / ▶` lớn sắc nét ở giữa, `10↻` bên phải.
     - Thanh SeekBar chuẩn CloudStream với núm tròn xanh ngọc mint **`#2CE59B`** trên nền rãnh trắng mảnh (`bg_cs_seekbar.xml` + `thumb_cs_seekbar.xml`) giữa timestamp trái/phải.
     - Thanh dưới gồm đúng 6 nút ngang `[icon] Label` cạnh nhau chuẩn hình mẫu: `Khóa`, `Thu phóng`, `Phụ đề`, `Thuyết minh`, `Chỉnh phụ đề` (mở drawer chỉnh Phụ đề Song ngữ, Chất lượng video, Tốc độ phát, Khóa xoay), và `Nguồn` (chọn máy chủ + chuyển nhanh sang Danh sách tập), hỗ trợ đầy đủ cả chạm lẫn TV D-Pad (`focusable` + viền mint `#2CE59B`).
- **Kiểm tra thực tế:**
  - Cập nhật `PrefsStoreTest.kt` và thêm mới `YanHh3dNowScraperTest.kt` (4 test cases: danh sách `.flw-item`, audio tabs + `btn3dsv`, `decodeStreamrptObf`/`extractFbCcccMp4`, `findPngCamouflageTsOffset` offset 271).
  - `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1`:
    - `testDebugUnitTest`: **20/20 tests PASS** (`NguonCApiAndRepoTest` 5, `PlayerHelpersTest` 2, `PrefsStoreTest` 2, `VietnameseSearchTest` 3, `YanHh3dNowScraperTest` 4, `YanHh3dScraperTest` 4 — 0 failures, 0 errors, 0 skipped).
    - `assembleDebug`: `BUILD SUCCESSFUL`.
    - File APK tạo ra: `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.65 MB**, `14,308,849 bytes`).

---

### [2026-11-25] - Phiên 17: Khai Thác Toàn Bộ Phân Loại & 8 Server YanHH3D / NguonC-PhimAPI, Gỡ Bỏ Embed Online Ở Chi Tiết Phim, Mặc Định Thuyết Minh
- **Vấn đề / Yêu cầu:**
  1. Nghiên cứu API thực tế của `YanHH3D` (`https://yanhh3d.ee`) và `NguonC` (`https://phim.nguonc.com`) / `PhimAPI` (`https://phimapi.com`) để lấy **toàn bộ phân loại phim**, chi tiết phim chuẩn xác, đủ server phát và đủ tập phim, tối ưu phát video.
  2. **Xoá bỏ** tính năng/nút `"🌐 Embed Online"` trong màn hình chi tiết phim (`MovieDetailActivity`).
  3. Thiết lập **nguồn phát Thuyết Minh mặc định** khi bấm phát phim (`selectPreferredServerIdx`).
- **Kết quả nghiên cứu API & Giải pháp đã triển khai:**
  1. **YanHH3D (`YanHh3dScraper.kt`):**
     - Sửa `fetchTaxonomies` tính `totalMovies` chỉ từ `ongoing` + `completed` (loại bỏ trailer).
     - Bổ sung `findByExactSlug(baseUrl, sourceName, slug)` gọi `/wp-json/wp/v2/posts?slug=...&_embed=1` để lấy chính xác toàn bộ danh sách `data-ep` (ví dụ *Thế Giới Hoàn Mỹ* đủ 288 tập) ngay cả khi tên phim lệch nhẹ khi search.
     - Mở rộng `parseDetailHtmlOnly` từ 4 lên đủ **8 server** (`tiktik`, `pro`, `vip4k`, `vip4k_v2`, `tiktm`, `pro_tm`, `vip4ktm`, `vip4ktm_v2`) khi AJAX `dox_ajax_player` trả rỗng.
     - Trong `fetchMovieDetail`: nếu tập mới nhất trả ít hơn 6 nút server `btn3dsv`, gọi thêm `parsedEps.first()` để hợp nhất toàn bộ server hiện có của bộ phim.
  2. **NguonC / PhimAPI (`InitialData.kt`, `NguonCApiClient.kt`):**
     - Bổ sung `fetchAllAnimationCategories()` lấy động toàn bộ 25 thể loại thuần Hoạt Hình từ `https://phimapi.com/the-loai` (trừ `phim-18`), kết hợp 6 bộ lọc ngôn ngữ/quốc gia (`Thuyết Minh`, `Lồng Tiếng`, `Vietsub`, `Donghua Trung Quốc`, `Anime Nhật Bản`), đồng thời mở rộng `NGUONC_GENRES` lên 18 mục fallback.
     - Cập nhật `fetchByGenreSpec` hỗ trợ query `lang=`, `country=` và `cat=` trên endpoint `/v1/api/danh-sach/hoat-hinh`.
     - Bổ sung fallback tìm kiếm `/v1/api/tim-kiem?keyword=...` (`type == "hoathinh"`, ưu tiên không dính `ova`/`movie`) trong `fetchPhimApiDetail(slug, fallbackTitle)` khi `phimapi.com/phim/$slug` lệch slug.
  3. **Mặc định Thuyết Minh & Đồng bộ Repository (`MovieRepository.kt`):**
     - Cập nhật `selectPreferredServerIdx`: khi `preferredAudio` là `"Thuyết Minh"`, luôn ưu tiên chọn nhóm server `Thuyết Minh / Lồng Tiếng` trước (`isDubbed`), bên trong nhóm đó ưu tiên `.m3u8` trực tiếp > nhiều tập nhất > khớp `"thuyết"`; chỉ fallback sang Vietsub khi phim không có server Thuyết Minh/Lồng Tiếng.
     - Truyền `findByExactSlug` và `movie?.title` vào `fetchMovieDetail` để đồng bộ đủ server/tập và backfill `.m3u8` cho server Thuyết Minh.
  4. **Xoá Embed Online ở Chi Tiết Phim (`MovieDetailActivity.kt`):**
     - Xoá hoàn toàn text/action `"🌐 Embed Online"` trên `btnDetailOffline`; khi chưa có luồng `.m3u8` để tải offline thì ẩn hẳn nút (`View.GONE`).
- **Kiểm tra thực tế:**
  - Cập nhật unit test trong `YanHh3dScraperTest.kt` (8 server fallback) và `NguonCApiAndRepoTest.kt` (ưu tiên `Thuyết Minh` mặc định).
  - `.\gradlew.bat testDebugUnitTest`: **16/16 unit tests PASS** (`NguonCApiAndRepoTest` 5, `YanHh3dScraperTest` 4, `VietnameseSearchTest` 3, `PlayerHelpersTest` 2, `PrefsStoreTest` 2 — 0 failures, 0 skipped).
  - `.\gradlew.bat assembleDebug`: `BUILD SUCCESSFUL`, tạo `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.74 MB**, `14,409,897 bytes`).

---

### [2026-11-25] - Phiên 16: Sửa Lỗi Không Play Được Phim (Iframe `-2`, Touch Overlay Che Nút Play) & Tối Ưu Server Load Film
- **Vấn đề / Yêu cầu (theo 3 ảnh chụp thực tế):**
  1. Phim *Linh Cảnh Hành Giả - Tập 2* báo lỗi `"Oh, video này chỉ có thể phát trong iframe. (-2)"` và hiện nút `Dùng Trình phát Tích hợp` trên Top Bar dù phim đã có `.m3u8` ở `phimapi.com`.
  2. Phim *Vô Thượng Thần Đế - Tập 1* mở Embed nhưng màn hình đen, nút Play đỏ giữa màn hình không bấm được và các nút điều khiển `↺10 / ⏸ / 10↻` đè lên giữa WebView Embed.
  3. Tối ưu tốc độ load server phim và chuyển tập.
- **Nguyên nhân cốt lõi (Root Cause):**
  1. `EmbedFallbackView.loadEmbed` và `sniffM3u8Url` gọi `wv.loadUrl(embedUrl)` trực tiếp ở cấp top-level (`window.self === window.top`), khiến `streamfree.vip` / `streamc.xyz` chặn bằng lỗi `(-2)`.
  2. Trong `PlayerActivity.kt` / `activity_player.xml`, khi `useEmbedNow = true`, `gestureStage` (`clickable=true`) vẫn hiện phủ lên trên `EmbedFallbackView` nên nuốt toàn bộ thao tác chạm vào nút Play trong WebView, đồng thời `centerControls` (`↺10 / ⏸ / 10↻`) không được ẩn.
  3. `MovieRepository.selectPreferredServerIdx` lọc `isDubbed` trước khi lọc `hasDirectHls`, nên nếu server `Thuyết Minh` chỉ có Embed trong khi server `Vietsub` có `.m3u8` trực tiếp thì chọn nhầm server Embed.
- **Giải pháp & Các file đã sửa:**
  1. `android/app/src/main/java/com/hoathinh3d/donghua/player/EmbedFallbackView.kt`:
     - Bổ sung `buildIframeWrapperHtml` + `resolveBaseReferer` và nạp qua `loadDataWithBaseURL(baseReferer, html, "text/html", "UTF-8", null)` cho cả `loadEmbed` lẫn `sniffViaHiddenIframeWebView`, giúp `window.self !== window.top` và `document.referrer` hợp lệ tuyệt đối.
     - Thêm fast-path `extractM3u8FromHtml` (~150–300ms qua OkHttp trước khi mở WebView sniffer) và chặn tải ảnh/font thừa trong WebView sniffer để tăng tốc bắt `.m3u8`.
  2. `android/app/src/main/java/com/hoathinh3d/donghua/player/PlayerActivity.kt`:
     - Khi `useEmbedNow = true`: ẩn `gestureStage` & `centerControls` (`View.GONE`), giữ `hudContainer` hiện và neo `embedFallbackView` gọn giữa `topBar` và `bottomBar` để người dùng bấm trực tiếp mọi nút trong WebView iframe; tự nâng cấp sang ExoPlayer ngay nếu sniffer bắt được `.m3u8` sau khi bấm Play.
     - Thêm `preResolveNextEpisode()` tự giải mã trước `.m3u8` / link tập kế tiếp ngay khi tập hiện tại đạt `STATE_READY`, và ưu tiên server `.m3u8` trước khi failover sang Embed trong `tryAutoFailover()`.
  3. `android/app/src/main/java/com/hoathinh3d/donghua/data/source/MovieRepository.kt`:
     - Cập nhật `selectPreferredServerIdx` luôn ưu tiên server có `.m3u8` trực tiếp (`Trình phát Tích hợp`) trước server chỉ có Embed; bọc `withTimeoutOrNull` cho từng nguồn trong `fetchMovieDetail` để tránh 1 nguồn lag kéo chậm các nguồn còn lại.
  4. `android/app/src/test/java/com/hoathinh3d/donghua/NguonCApiAndRepoTest.kt`:
     - Bổ sung test kiểm chứng `selectPreferredServerIdx` (HLS-over-Embed) và `EmbedFallbackView.buildIframeWrapperHtml` + `extractM3u8FromHtml`.
- **Kiểm tra thực tế:**
  - `.\gradlew.bat testDebugUnitTest`: **16/16 unit tests PASS** (0 failures, 0 skipped).
  - `.\gradlew.bat assembleDebug`: `BUILD SUCCESSFUL`, 0 lỗi, 0 cảnh báo Kotlin.
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.74 MB**, `14,409,897 bytes`).

---

### [2026-11-25] - Phiên 15: Chuyển Đổi Toàn Bộ Kiến Trúc Sang Android Thuần (Kotlin + XML Views + Media3 ExoPlayer)
- **Vấn đề / Yêu cầu:** Chuyển đổi toàn bộ ứng dụng từ Capacitor WebView + React/Vite + `hls.js` sang **Android Native thuần** (`Kotlin` + `XML Views` + `ViewBinding` + `AndroidX Media3 ExoPlayer`) theo đặc tả `SPEC_HOATHINH3D_NATIVE_KOTLIN_MIGRATION.md`, đạt 1:1 Feature & UI/UX Parity, hỗ trợ Mobile / Tablet / Android TV (`LEANBACK_LAUNCHER`), giữ WebView chỉ cho Sniffer bắt `.m3u8` và `EmbedFallbackView` dự phòng.
- **Giải pháp & Các module Kotlin mới trong `android/app/src/main/java/com/hoathinh3d/donghua/`:**
  1. **Giai đoạn 1 — Khung Gradle Native & Resources (`android/build.gradle`, `android/settings.gradle`, `android/app/build.gradle`, `AndroidManifest.xml`, `build-apk.ps1`):**
     - Gỡ bỏ `capacitor-android` / `capacitor-cordova-android-plugins`, cấu hình `org.jetbrains.kotlin.android` (Kotlin `2.0.21`), `ViewBinding`, `AndroidX Media3 1.5.1` (`media3-exoplayer`, `media3-exoplayer-hls`, `media3-datasource-okhttp`, `media3-ui`), `OkHttp 4.12.0`, `Jsoup 1.18.3`, `Glide 4.16.0`, `Material 1.12.0`.
     - Giữ nguyên `applicationId "com.hoathinh3d.donghua"`, `versionCode 35`, `versionName "3.5.0-universal"`, `minSdk 23`, `targetSdk 35`, `LEANBACK_LAUNCHER`, `supportsPictureInPicture="true"`, `tv_banner.png`, launcher icons và `app_avatar.jpg`.
     - Cập nhật `build-apk.ps1` chạy trực tiếp `.\gradlew.bat testDebugUnitTest` + `.\gradlew.bat assembleDebug` và copy ra `HoatHinh3D-Universal-debug.apk`.
  2. **Giai đoạn 2 — Tầng Core, Scrapers, Storage & Offline (`core/`, `data/`):**
     - `core/HttpClientProvider.kt`: OkHttp singleton (`ConnectionPool(12, 5m)`, DNS cache 5 phút, timeout `8s/10s`).
     - `core/DeviceProfileResolver.kt`: Nhận diện `auto | mobile | tablet | tv` qua `UiModeManager`, `FEATURE_LEANBACK`, `smallestScreenWidthDp` và tổng RAM.
     - `core/ThemeManager.kt`: Đầy đủ 4 theme (`Pearl`, `Aqua`, `Sakura`, `Dark`), `ambientOrbs`, `glassBlur` (`RenderEffect` API 31+ / translucent border API 23–30) và viền focus Cyan `#06B6D4` cho D-Pad TV.
     - `core/PrefsStore.kt`: Tương thích 100% các key lưu trữ & JSON Backup Export/Import (`version: "3.5.0-universal"`).
     - `data/source/VietnameseSearch.kt`, `YanHh3dScraper.kt`, `NguonCApiClient.kt`, `CustomSourceParser.kt`, `MovieRepository.kt`: Port đầy đủ bộ lọc Hoạt Hình thuần, `.tick-rate`, `.ep-range`, `.halim-server`, `dox_ajax_player`, backfill `.m3u8`, tìm kiếm không dấu & 15 từ viết tắt (`dptk`, `tghm`, `pntt`, ...), kiểm tra tập mới tủ phim.
     - `data/offline/HlsOfflineManager.kt`: Tải playlist master/variant + tối đa 240 phân đoạn theo batch 4, viết lại `index.m3u8` cục bộ vào `filesDir/offline_hls/` để ExoPlayer phát 100% Offline không cần mạng.
  3. **Giai đoạn 3 — Media3 ExoPlayer “Nhấn Phát Tua Ngay” (`player/`):**
     - `player/InstantLoadControl.kt`: `minBufferMs = 10_000`, `maxBufferMs = 32_000/48_000`, `bufferForPlaybackMs = 500`, `bufferForPlaybackAfterRebufferMs = 500`, `setBackBuffer(25_000, true)`, `targetBufferBytes` thích ứng RAM (`34MB` cho TV Box 2GB / `72MB` cho máy >= 3GB).
     - `player/HlsCacheProvider.kt`: `SimpleCache` (256 MB LRU) + `CacheDataSource` + `OkHttpDataSource` + `HlsMediaSource.Factory.setAllowChunklessPreparation(true)`.
     - `player/PlayerHelpers.kt` (`ZeroDelaySeekCoalescer`): Lần chạm/nhấn D-Pad đầu tiên gọi `exoPlayer.seekTo()` ngay ở `0ms`, chỉ gom lệnh `100ms` khi bấm dồn liên tiếp `<= 150ms`; dùng `SeekParameters.CLOSEST_SYNC` và `SurfaceView` chuẩn (không ép tunneling).
     - `player/EmbedFallbackView.kt`: WebView sniffer ẩn (`shouldInterceptRequest`) bắt `.m3u8` đưa về ExoPlayer, chỉ hiện `EmbedFallbackView` (kèm AdBlock host filter, third-party cookies, fullscreen custom view) khi server không có `.m3u8`.
     - `player/PlayerActivity.kt`: Full gestures (sáng trái / âm lượng phải / tua ngang / chạm đúp), PiP API 26+, khóa xoay, khóa màn hình, 4 Drawers (Tập 50/trang, Nguồn HLS↔Embed, Chất lượng `TrackSelector`, Tốc độ `0.5x–2.0x`), Skip Intro +85s, Auto-Next 5s, Auto-failover, AI HDR Upscale (`RenderEffect`), Bilingual Sub banner, Sleep Timer.
  4. **Giai đoạn 4 — Giao Diện 5 Tab & Chi Tiết Phim (`ui/`):**
     - `MainActivity.kt`, `HomeFragment.kt`, `ScheduleFragment.kt`, `HistoryFragment.kt`, `FavoritesFragment.kt`, `MovieDetailActivity.kt`, `SettingsFragment.kt` (đủ 7 subtabs), `MovieCardAdapter.kt`.
- **Kiểm tra thực tế:**
  - `.\gradlew.bat testDebugUnitTest`: **15/15 unit tests PASS** (`NguonCApiAndRepoTest`: 4, `PlayerHelpersTest`: 2, `PrefsStoreTest`: 2, `VietnameseSearchTest`: 3, `YanHh3dScraperTest`: 4), 0 failures, 0 skipped.
  - `.\gradlew.bat assembleDebug`: `BUILD SUCCESSFUL`, **0 lỗi biên dịch, 0 cảnh báo Kotlin**.
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**13.74 MB**, `14,409,897 bytes`).

---

### [2026-11-25] - Phiên 14: Thiết Kế & Tối Ưu Trình Phát Chuẩn HH3D Thích Ứng 4 Thiết Bị (Android Phone, Smart TV, Tablet, PC / Giả Lập)
- **Vấn đề / Yêu cầu:** Thiết kế trình phát Play chuẩn theo giao diện & trải nghiệm mẫu của app HH3D, đồng thời tối ưu riêng cho từng loại màn hình: **Điện thoại Android, Android TV, Máy tính bảng, và PC / Giả lập PC**.
- **Giải pháp & Các file đã sửa / tạo mới:**
  1. **`src/components/player/playerUtils.ts` (mới):**
     - Bổ sung `resolvePlayerDeviceProfile` tự nhận diện 4 profile thực tế (`mobile`, `tablet`, `tv`, `pc`) dựa trên `resolvedDevice`, `deviceMode`, `pointer: coarse` và User-Agent.
     - Bổ sung `getDeviceHlsConfig` tối ưu bộ đệm `Hls.js` theo phần cứng từng thiết bị: `mobile`/`tv` dùng bộ đệm tiết kiệm RAM (`16s–32s`, `28MB`, bật `capLevelToPlayerSize` khi `Auto`), còn `tablet`/`pc` dùng bộ đệm lớn mượt mà (`24s–48s`, `50MB`).
  2. **`src/components/player/PlayerDrawer.tsx` (mới):**
     - Tách bảng trượt bên phải hỗ trợ 4 tab (`episodes`, `sources`, `quality`, `speed`) với kích thước chữ/lưới thích ứng (`grid-cols-4` trên mobile, `grid-cols-5` & `max-w-md` trên Tablet/TV/PC), hỗ trợ auto-focus cho D-Pad TV và hiển thị badge tập đã lưu offline.
  3. **`src/components/player/VideoPlayer.tsx` & `src/App.tsx`:**
     - **Giao diện chuẩn HH3D:** Top Bar (`<-`, `Tên phim - Tập`, badge `Trình phát Tích hợp • chất lượng`, nút `Tập trước`, `Khóa màn hình`, `Tốc độ/PiP`, `Toàn màn hình`), 3 nút tròn điều khiển ở tâm (`Tua lùi`, `Play/Pause`, `Tua tới`), thanh tiến độ vàng cam (`amber-400`) + 6 nút chức năng đáy.
     - **Tối ưu Điện thoại & Tablet:** Hỗ trợ `env(safe-area-inset-top/bottom)`, cử chỉ vuốt dọc trái/phải chỉnh **Độ sáng / Âm lượng**, **vuốt ngang trên sân khấu tua nhanh/lùi kèm preview thời gian đích**, chạm đúp trái/phải tua `±seekStep`, chạm đúp giữa màn hình bật/tắt Fullscreen, nút **Khóa đứng / Xoay ngang** ở vị trí #2 thanh đáy.
     - **Tối ưu Android TV:** Phóng to nút điều khiển (`w-24 h-24` ở tâm, `w-11 h-11` trên Top Bar), cho phép focus D-Pad trực tiếp vào thanh Timeline (`tv-focus-target`), auto-focus nút Play/Drawer, thay nút Khóa đứng bằng nút chọn **Chất lượng thực (`Auto` / `1080p` / `720p`...)** trên thanh đáy.
     - **Tối ưu PC & Giả lập PC:** Thêm bộ phím tắt đầy đủ (`Space`/`K`, `J`/`L`, `M`, `S` Skip Intro, `N`/`P` Next/Prev tập, `0–9` nhảy % video, `F` Fullscreen), cuộn chuột chỉnh âm lượng, tooltip xem trước thời gian khi rê chuột trên thanh tua và tự ẩn con trỏ (`cursor-none`) khi phát.
     - **Tính năng mẫu HH3D:** Nút nổi **Bỏ qua Mở đầu (+85s)** trong 3 phút đầu tập và lớp phủ **Tự động chuyển tập đếm ngược 5 giây** (`Hủy tự chuyển` / `Phát ngay`).
- **Kiểm tra thực tế:**
  - `npm.cmd run build`: `56 modules transformed`, 0 lỗi (`4.09s` / `4.10s`).
  - Kiểm thử DOM trên Vite Dev + Headless Edge CDP qua 4 chế độ (`pc` 1366x768, `tablet` 820x1180, `mobile` 390x844, `tv` 1920x1080): xác nhận `data-player-profile` chuyển đúng 4 chế độ, slot #2 thanh đáy đổi linh hoạt giữa `Khóa đứng` (Mobile/Tablet) và `Tự động` chất lượng (PC/TV), `PlayerDrawer` mở đúng (`Danh sách tập (288 tập)`), `0` lỗi console/runtime.
- **Trạng thái Build:** `tsc -b && vite build` OK (`4.10s`), `cap sync android` OK (`0.076s`), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 14s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**6.95 MB**).

---

### [2026-11-25] - Phiên 13: Sửa Bộ Lọc Phân Loại (Thuyết Minh / Lồng Tiếng / Cổ Trang), Đổi Tiêu Đề Kho Nguồn & Ưu Tiên Trình Phát Tích Hợp HLS
- **Vấn đề / Yêu cầu (theo ảnh chụp màn hình người dùng):**
  1. Các chip phân loại trên giao diện chính (`Thuyết Minh`, `Lồng Tiếng`, `Cổ Trang`, `Hành Động`, `Phiêu Lưu`, `Giả Tưởng`, `Viễn Tưởng`...) khi bấm vào bị lọc sai (0 kết quả hoặc lẫn phim bộ người đóng Hàn/Trung như *Tình Yêu Ngụy Trang*) và làm bẩn danh sách chính.
  2. Đổi tiêu đề **"Kho Phim Hoạt Hình 3D"** thành **"Kho Nguồn Phim Đang Chọn"** (hiển thị đúng nguồn và bộ lọc đang chọn).
  3. Thay đổi trình phát web (`<iframe>` Embed) sang **Trình phát tích hợp** (`<video>` + `Hls.js`) tối ưu và hiệu quả nhất cho thiết bị.
- **Nguyên nhân cốt lõi (Root Cause):**
  1. `ApiService.mapNguonCItems` gán cứng `genres: ['Tu Tiên', 'Huyền Huyễn']` cho mọi phim NguonC, đồng thời endpoint NguonC `/api/films/ngon-ngu/thuyet-minh` và `/api/films/the-loai/co-trang` trả cả phim bộ người đóng thay vì chỉ Hoạt Hình; trong khi `App.tsx` lại nhét kết quả fetch vào `allMovies` rồi lọc bằng `movie.genres.includes(...)` khiến thẻ mới bị ẩn và phim người đóng sót lại trong `allMovies`.
  2. `ApiService.fetchMovieDetail` trước đó chỉ lấy link `Embed` từ `YanHH3D` và `NguonC` nên `VideoPlayer` luôn bị ép vào chế độ trình phát web `<iframe>`.
- **Giải pháp & Các file đã sửa:**
  1. **`src/services/api.service.ts`:**
     - Cập nhật `NGUONC_GENRES` (`Thuyết Minh`, `Lồng Tiếng`, `Cổ Trang`, `Hành Động`, `Phiêu Lưu`, `Giả Tưởng`, `Viễn Tưởng`, `Hài Hước`, `Bí Ẩn`) và `fetchBySourceAndGenre` truy vấn chuẩn bộ lọc Hoạt Hình thuần túy (`phimapi.com/v1/api/danh-sach/hoat-hinh` theo `sort_lang` / `category`, gắn đúng `sources: ['NguonC']` và tag thể loại/âm thanh), còn `Tất cả NguonC` lấy thuần từ `/api/films/the-loai/hoat-hinh`.
     - Cập nhật `fetchDonghuaList` nạp đúng danh sách theo nguồn đang chọn (`YanHH3D` hoặc `NguonC`) thay vì trộn lẫn cố định.
     - Trong `fetchMovieDetail`: truy vấn song song bộ giải mã HLS trực tiếp (`phimapi.com/phim/{slug}`) để tạo máy chủ `Trình phát Tích hợp • ...` (`.m3u8`) và backfill `linkM3u8` sang các tập khớp của `YanHH3D` / `NguonC`; nâng cấp `selectPreferredServerIdx` luôn ưu tiên máy chủ có `linkM3u8` trực tiếp trước khi xét đến Embed.
  2. **`src/App.tsx` & `src/components/home/HomeView.tsx`:**
     - Tách riêng `activeGenreMovies` và `isLoadingGenre` trong `App.tsx` khi chọn chip phân loại (không ghi đè làm bẩn `allMovies`), đồng thời khi đổi nguồn trên Header sẽ hiển thị đúng kho phim của nguồn đó.
     - Đổi tiêu đề danh mục chính trong `HomeView.tsx` từ `Kho Phim Hoạt Hình 3D` thành **`Kho Nguồn Phim Đang Chọn:`** kèm badge tên nguồn (`YanHH3D VIP` / `Phim NguonC`) và bộ lọc hiện tại.
  3. **`src/components/player/VideoPlayer.tsx`:**
     - Tinh chỉnh `Hls.js` tối ưu cho thiết bị (`lowLatencyMode: false` cho VOD mượt mà, `capLevelToPlayerSize: settings.defaultQuality === 'Auto'`, `abrEwmaDefaultEstimate: 2500000`, `maxBufferLength: 18`, `maxMaxBufferLength: 36`, `maxBufferSize: 35MB`).
     - Hiển thị badge `Trình phát Tích hợp • ...` trên Top Bar, chỉ lùi về `Embed dự phòng` khi nguồn phim hoàn toàn chưa có `.m3u8`, và bổ sung nút 1 chạm **Dùng Trình phát Tích hợp** nếu đang ở Embed mà có server `.m3u8`.
- **Kiểm tra thực tế:**
  - `npm.cmd run build` (`tsc -b && vite build`): 0 lỗi (`3.90s` / `3.89s`).
  - Kiểm thử DOM trên Vite Dev + Headless Edge CDP: chọn `Phim NguonC` hiện tiêu đề `Kho Nguồn Phim Đang Chọn: Phim NguonC`; bấm `Thuyết Minh` trả 48 phim hoạt hình thuyết minh (*Tiên Nghịch*, *Mục Thần Ký*, *Phàm Nhân Tu Tiên*, *Đấu Phá Thương Khung Phần 5*); bấm `Lồng Tiếng` trả 48 phim hoạt hình lồng tiếng; bấm `Cổ Trang` trả 23 phim hoạt hình cổ trang (*Già Thiên*, *Nghịch Thiên Chí Tôn*...); mở *Già Thiên* phát bằng **Trình phát Tích hợp** (`<video>` active, `hasIframe: false`).
- **Trạng thái Build:** `tsc -b && vite build` OK (`3.89s`), `cap sync android` OK (`0.071s`), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 14s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**6.94 MB**).

---

### [2026-11-25] - Phiên 12: Tối Ưu WebView Embed, Giao Diện Android TV, Cảnh Báo Tập Mới, Lưu Offline HLS & Dọn Proxy Thừa
- **Vấn đề / Yêu cầu:** Hoàn thiện toàn bộ 5 đầu việc chuyển giao từ Phiên 11:
  1. Tối ưu WebView Android cho Embed (`streamfree.vip` / `streamc.xyz`) và sửa lỗi đồng bộ tập/tiến độ trong `VideoPlayer`.
  2. Nâng cấp giao diện Android TV dạng hàng ngang (Horizontal Row Carousel) và khoanh vùng điều hướng D-Pad bên trong Modal/Drawer.
  3. Bổ sung kiểm tra tập mới cho Tủ phim yêu thích và tính năng lưu tập phim HLS xem offline (`CacheStorage`).
  4. Dọn dẹp proxy thừa (`/hh3d-proxy`, `/hhkf-proxy`) và tối ưu danh sách tập mới thực tế trên `YanHH3D`.
- **Giải pháp & Các file đã sửa:**
  1. **WebView Android & Embed (`android/app/src/main/java/com/hoathinh3d/donghua/MainActivity.java`, `src/components/player/VideoPlayer.tsx`):**
     - Bật `CookieManager.setAcceptCookie(true)` và `setAcceptThirdPartyCookies(webView, true)` giúp `streamc.xyz` / `streamfree.vip` vượt qua kiểm tra cookie trong iframe bên thứ 3; chặn popup cửa sổ mới (`setJavaScriptCanOpenWindowsAutomatically(false)`, `setSupportMultipleWindows(false)`).
     - Ghi đè `onShowCustomView` & `onHideCustomView` trên `BridgeWebChromeClient` để video/iframe bật fullscreen thực sự trên Android & Android TV Box.
     - Trong `VideoPlayer.tsx`: khởi tạo `currentEpIdx` bằng `findEpisodeIdxByNumber` ngay khi mở player với `movie.serverGroups` có sẵn, sửa `StorageService.saveProgress` lưu đúng `currentEpisodeNum` thay vì `currentEpIdx + 1`, thêm spinner `isEmbedLoading` khi khởi tạo `<iframe>`, và cập nhật tiến độ xem định kỳ mỗi 15 giây cho chế độ Embed.
  2. **Android TV Horizontal Row Carousel & Scoped D-Pad (`src/utils/tvSpatialNav.ts`, `src/hooks/useTVRemote.ts`, `src/components/home/HomeView.tsx`, `src/components/catalog/MovieDetailModal.tsx`, `src/components/player/VideoPlayer.tsx`):**
     - Nâng cấp `tvSpatialNav.ts` (`getActiveTvScope`, `focusFirstTvTarget`): tự động khoanh vùng D-Pad trong `[data-tv-drawer="true"]` hoặc `[data-tv-modal="true"]` đang mở, tránh nhảy focus ra nền sau.
     - Hỗ trợ phím `ChannelUp` / `ChannelDown` / `MediaTrackNext` / `MediaTrackPrevious` trong `useTVRemote.ts`.
     - Trong `HomeView.tsx`: khi `resolvedDevice === 'tv'`, hiển thị thêm các hàng ngang Leanback (`Thuyết Minh & Lồng Tiếng Hot`, `Tu Tiên • Tiên Hiệp Đỉnh Cao`, `Huyền Huyễn • Nghịch Thiên`).
     - Trong `MovieDetailModal.tsx`: tự động focus nút Xem Ngay (`data-tv-autofocus="true"`), hỗ trợ D-Pad 4 hướng bên trong modal và ánh xạ đúng số tập thực tế (`parseEpisodeNum`).
  3. **Thông báo tập mới Tủ phim & Lưu Offline HLS (`src/services/offline.service.ts`, `src/services/storage.service.ts`, `src/types/index.ts`, `src/components/catalog/MovieCard.tsx`, `src/components/favorites/FavoritesView.tsx`, `src/App.tsx`):**
     - Tạo mới `src/services/offline.service.ts`: `OfflineCacheService` tải playlist `.m3u8` và phân đoạn video vào `CacheStorage` (`hh3d-offline-hls-v1`), kết hợp `createCacheAwareHlsLoader` để `Hls.js` tự động phát từ cache offline trước khi gọi mạng; `FavoritesMonitorService` đối chiếu tập mới cho Tủ phim yêu thích.
     - Thêm nút **Kiểm tra tập mới**, huy hiệu `+N tập mới` / `N offline` trên `MovieCard`, khu vực **Tập Phim Đã Lưu Offline** trong `FavoritesView`, và nút **Lưu Offline** trong `MovieDetailModal`.
  4. **Dọn proxy thừa & Tối ưu tập mới YanHH3D (`vite.config.ts`, `src/utils/httpClient.ts`, `src/services/yanhh3d.service.ts`):**
     - Xóa `/hh3d-proxy` (`hoathinh3d.ee`) và `/hhkf-proxy` (`hhkungfu.ee`) khỏi `vite.config.ts` và `DEV_PROXY_MAP`.
     - Bổ sung `WpDonghuaService.fetchHomeEpisodeMeta` bóc tách badge `.tick-rate` (`Tập X/Y [4K]`) từ trang chủ `yanhh3d.ee` song song với `wp/v2/posts` để hiển thị đúng số tập mới nhất thực tế thay vì `1/180`.
- **Kiểm tra thực tế:**
  - Chạy `npm.cmd run build` (`tsc -b && vite build`): 0 lỗi, build trong `4.11s`.
  - Chạy kiểm thử DOM trực tiếp trên Vite Dev + Headless Edge CDP: xác nhận `data-resolved-device="tv"`, nạp 101 thẻ phim với số tập thực tế (`Tập 288/300`, `Tập 135/156`, `Tập 5/16`, `Tập 14/16`...), render đủ các hàng ngang TV Carousel, mở `MovieDetailModal` auto-focus nút CTA & hiện nút `Lưu Offline` / `Embed Online`, chọn tập mở `iframe` `streamfree.vip/...?autoplay=1` (`referrerpolicy="unsafe-url"`), tab Yêu thích hiển thị nút `Kiểm tra tập mới`, `0` lỗi console/runtime.
- **Trạng thái Build:** `tsc -b && vite build` OK (`3.98s`), `cap sync android` OK (`0.077s`), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 18s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (**6.94 MB**).

---

### [2026-11-25] - Phiên 11: Thu Gọn Còn 2 Nguồn (YanHH3D & NguonC), Sửa Lỗi Auto-Play Chọn Tập & Tối Ưu Trình Phát
- **Vấn đề / Yêu cầu:**
  1. Chỉ giữ lại 2 nguồn phim chính thức trên app: **YanHH3D** (`https://yanhh3d.ee`) và **NguonC** (`https://phim.nguonc.com`).
  2. Sửa lỗi chọn tập phim không tự động phát (auto-play) và tối ưu trình phát hoàn thiện hơn.
- **Giải pháp & Các file đã sửa:**
  1. **Chỉ giữ lại 2 nguồn YanHH3D & NguonC (`src/constants/initialData.ts`, `src/services/storage.service.ts`, `src/services/yanhh3d.service.ts`, `src/services/api.service.ts`, `src/components/settings/SettingsView.tsx`, `src/App.tsx`):**
     - Rút gọn `DEFAULT_BUILT_IN_SOURCES` và `WEB_SOURCE_PRESETS` chỉ còn **YanHH3D** và **NguonC**; thêm khóa migration `hh3d_sources_v4_yanhh3d_nguonc_only` trong `StorageService.getSources` và đặt `preferredSource` mặc định sang `'YanHH3D'`.
     - Tinh gọn `ApiService` (`fetchCategoriesForSource`, `fetchBySourceAndGenre`, `fetchDonghuaList`, `fetchMovieDetail`, `searchDonghua`) chỉ truy vấn và hợp nhất dữ liệu từ `YanHH3D` và `NguonC`.
  2. **Sửa lỗi chọn tập không tự phát & tối ưu trình phát (`src/components/player/VideoPlayer.tsx`):**
     - Không nạp lại và ghi đè `serverGroups` khi `movie.serverGroups` đã được truyền từ `MovieDetailModal`, loại bỏ lỗi ngắt/khởi động lại luồng phát ngay khi vừa mở tập.
     - Thêm `extractEpisodeNumber` & `findEpisodeIdxByNumber` để ánh xạ đúng số tập khi mở trình phát hoặc chuyển máy chủ (`switchToServer`).
     - Bổ sung `attemptAutoplayVideo` với cơ chế fallback `muted` + nút nổi **Nhấn để bật âm thanh** nếu trình duyệt/WebView chặn autoplay có tiếng, đồng thời tự phục hồi cả `MEDIA_ERROR` lẫn `NETWORK_ERROR` trên `Hls.js` và đồng bộ `playbackRate` / `volume`.
     - Với chế độ Embed (`YanHH3D` / `NguonC`): tự gắn `autoplay=1` (`appendAutoplayParam`), dùng `referrerPolicy="unsafe-url"` chuẩn YanHH3D, gắn `key` theo tập/máy chủ/`embedReloadTick` để mount mới `<iframe>` mỗi khi đổi tập, ưu tiên failover sang máy chủ cùng chế độ âm thanh (Thuyết Minh trước khi chuyển Vietsub), và bổ sung nút **Tập trước** + **Tải lại luồng phát** trên thanh điều khiển.
- **Kiểm tra thực tế:**
  - Chạy headless Edge (`vite` dev): Header hiển thị đúng 2 nguồn (`YanHH3D VIP`, `Phim NguonC`), nạp 65 thẻ phim, mở *Đấu Phá Thương Khung* chọn Tập 2 mở trình phát `NguonC • Lồng Tiếng #1` với `embed11.streamc.xyz/...&autoplay=1`, mở *Một Mình Tu Tiên Giữa Tận Thế* chọn Tập 3 mở trình phát `YanHH3D • Thuyết minh 1080 V1` với `streamfree.vip/...?autoplay=1` (`referrerpolicy="unsafe-url"`).
- **Trạng thái Build:** `tsc -b && vite build` OK (3.49s), `cap sync android` OK (0.061s), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 11s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (6.93 MB).

---

### [2026-11-25] - Phiên 10: Nâng Cấp Toàn Bộ 9 Nguồn Phim Thực Tế & Tối Ưu Tìm Kiếm Tiếng Việt
- **Vấn đề / Yêu cầu:**
  1. Kiểm tra, nghiên cứu 9 nguồn phim trong app và đảm bảo lấy được đầy đủ phân loại phim của từng nguồn, chi tiết phim, các server phát, toàn bộ danh sách tập và phát được video.
  2. Sửa lỗi ô tìm kiếm trên giao diện chính không hoạt động và tối ưu tìm kiếm tiếng Việt chính xác trên tất cả các nguồn.
- **Kết quả khảo sát thực tế 9 nguồn:**
  - Các domain cũ `hhtq.vip`, `hh3dhay.com` (`127.0.0.1`/`::1` sinkholed) và `hoathinhkungfu.tv`, `xiaofilm.tv`, `phimchill.tv` (`ENOTFOUND`) không truy cập được; trong khi `phimapi.com`, `yanhh3d.ee`, `hoathinh3d.ee`, `hhkungfu.ee`, `phim.nguonc.com` và `vsmov.com` đều phản hồi HTTP 200 với đầy đủ danh mục, chi tiết, máy chủ, tập và luồng phát hợp lệ (`m3u8` / `streamfree.vip` / `streamvsmov.com` / `player.phimapi.com` / `streamc.xyz`).
- **Giải pháp & Các file đã sửa:**
  1. `src/constants/initialData.ts` & `src/services/storage.service.ts`:
     - Thay thế các nguồn chết bằng 9 nguồn hoạt động thực tế (`KKPhim`, `YanHH3D`, `HH3D`, `HHKungfu`, `NguonC`, `VSMov`, `NguonCTM`, `KKPhimAnime`, `KKPhimCoTrang`), kèm migration `hh3d_sources_v3_live_migrated` giữ nguyên nguồn tùy biến người dùng đã thêm.
     - Cập nhật `WEB_SOURCE_PRESETS` trong `src/components/settings/SettingsView.tsx`.
  2. `src/services/yanhh3d.service.ts`, `src/utils/httpClient.ts`, `vite.config.ts`, `src/types/index.ts` & `src/components/player/VideoPlayer.tsx`:
     - Tổng quát hóa `WpDonghuaService` hỗ trợ cả 3 nguồn WordPress (`yanhh3d.ee`, `hoathinh3d.ee`, `hhkungfu.ee`), bóc tách cả cấu trúc `.ep-range` (YanHH3D) và `.halim-server` (HH3D/HHKungfu: `Việt Sub`, `Thuyết Minh`, `1080P V1/V2`), giải mã link phát qua `player/player.php?action=dox_ajax_player` với `CapacitorHttp` trên Android và proxy dev (`/yan-proxy`, `/hh3d-proxy`, `/hhkf-proxy`).
  3. `src/services/api.service.ts`:
     - Bổ sung `fetchCategoriesForSource` và `fetchBySourceAndGenre` nạp toàn bộ phân loại chính thức của từng nguồn (26 thể loại KKPhim, 45 thể loại VSMov, 10 nhóm NguonC + ngôn ngữ Thuyết Minh/Lồng Tiếng, và toàn bộ category/status WP Donghua).
     - Tích hợp đầy đủ `VSMov`, `HH3D`, `HHKungfu`, `YanHH3D`, `KKPhim`, `NguonC` vào `fetchDonghuaList`, `fetchMovieDetail` và `searchDonghua`.
  4. `src/utils/vietnamese.ts`, `src/components/navigation/Header.tsx`, `src/components/home/HomeView.tsx` & `src/App.tsx`:
     - Nâng cấp bộ tìm kiếm tiếng Việt (`expandVietnameseSearchQueries`, `getTitleAcronym`, `scoreMovieMatch`): hỗ trợ tiếng Việt có dấu, không dấu, tìm theo cụm từ rời rạc và viết tắt (`dptk`, `tghm`, `pntt`, `tptk`, ...), tự chuyển về `home` khi gõ tìm kiếm từ tab khác, bỏ qua lọc chip thể loại khi đang tìm kiếm, hiển thị banner kết quả tìm kiếm ngay đầu trang và xếp hạng kết quả theo độ khớp.
- **Kiểm tra thực tế:**
  - Chạy `tsc -b && vite build` (3.87s) và kiểm tra DOM trên headless Edge: Header hiển thị đủ 9 nguồn hoạt động, tìm kiếm `"tiên nghịch"`, `"dau pha"`, `"dptk"` trả kết quả khớp ngay ở vị trí đầu tiên, chuyển sang nguồn `HH3D` nạp đúng các chip phân loại thực (`Huyền Huyễn (351)`, `Tiên Hiệp (284)`, `Kiếm Hiệp (270)`, `Tu Tiên (242)`...).
- **Trạng thái Build:** `tsc -b && vite build` OK (3.87s), `cap sync android` OK (0.068s), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 14s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (6.94 MB).

---

### [2026-11-25] - Phiên 9: Gỡ Bỏ Các Nút Nhanh (Tải APK, Tự Động, Đăng Nhập) Trên Header
- **Vấn đề / Yêu cầu:** Người dùng yêu cầu bỏ 3 mục **Tải APK**, **Tự động** và **Đăng nhập** trên thanh tiêu đề (Header) của giao diện trang chủ theo ảnh minh họa.
- **Giải pháp & Các file đã sửa:**
  1. `src/components/navigation/Header.tsx`: Gỡ bỏ nút **Tải APK**, nút chuyển chế độ hiển thị (**Tự động**) và nút **Đăng nhập** (Google), giữ lại logo, thanh tìm kiếm, bộ chọn nguồn phát và nút Cài đặt; lược bỏ các props không còn dùng (`deviceMode`, `onCycleDeviceMode`, `user`, `onOpenAccount`, `onOpenApkStudio`).
  2. `src/App.tsx`: Cập nhật truyền props vào `<Header />` và dọn `handleCycleDeviceMode`, `openSettingsAtSubtab` (các tính năng Tài khoản, Giao diện, Sao lưu/APK vẫn truy cập đầy đủ trong tab Cài đặt).
- **Trạng thái Build:** `tsc -b && vite build` OK (3.98s), `cap sync android` OK (0.074s), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 14s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (6.93 MB).

---

### [2026-11-25] - Phiên 8: Sửa Lỗi Hiển Thị Số Tập Chi Tiết Phim & Đặt Máy Chủ Mặc Định Thuyết Minh
- **Vấn đề / Yêu cầu:**
  1. Không hiện số tập khi chọn vào chi tiết phim (ví dụ mở *Đấu Phá Thương Khung* chỉ hiển thị `Tập 1/1` và 1 nút `Tập Full`).
  2. Thiết lập máy chủ mặc định là nguồn phim **Thuyết Minh** khi chọn xem phim.
- **Nguyên nhân cốt lõi (Root Cause):**
  1. Trong `ApiService.fetchMovieDetail`, bước tìm kiếm chéo sang YanHH3D dùng điều kiện `m.slug.startsWith(slug)`, khiến `dau-pha-thuong-khung` (45 tập trên KKPhim/NguonC) bị ghép nhầm sang bản OVA 1 tập `dau-pha-thuong-khung-ova-song-de-chi-chien` của YanHH3D, đồng thời `latestEp` / `totalEp` bị ghi đè thành `1/1` và server mặc định của modal là server đầu tiên (`YanHH3D • VIETSUB 1080 V1 (1)`).
  2. `DEFAULT_SETTINGS.defaultAudio` trước đó là `'Vietsub'`, các hàm kiểm tra máy chủ chỉ tìm chữ `'thuyết'` (bỏ sót các máy chủ `Lồng Tiếng` của KKPhim/NguonC), và `MovieDetailModal` luôn khởi tạo `activeServerIdx = 0` nên truyền `initialServerIdx = 0` sang `VideoPlayer`.
- **Giải pháp & Các file đã sửa:**
  1. `src/services/api.service.ts`:
     - Siết chặt khớp phim YanHH3D trong `fetchMovieDetail` (chỉ nhận khi trùng chính xác `slug` hoặc tiêu đề chuẩn hóa, chạy song song với KKPhim/NguonC), tính `latestEp` / `totalEp` theo giá trị lớn nhất trên tất cả máy chủ (`detail_v3_` cache), và sửa `parseFirstInt` tránh ghép số dạng `"12/12"` thành `1212`.
     - Bổ sung `isDubbedServerName` và `selectPreferredServerIdx` nhận diện đồng bộ cả **Thuyết Minh** và **Lồng Tiếng**, ưu tiên máy chủ lồng tiếng/thuyết minh có nhiều tập nhất.
  2. `src/services/storage.service.ts` & `src/components/settings/SettingsView.tsx`:
     - Đổi `DEFAULT_SETTINGS.defaultAudio` sang `'Thuyết Minh'`, thêm migration một lần `hh3d_audio_default_tm_v1` cho thiết bị đã lưu cấu hình cũ.
  3. `src/components/catalog/MovieDetailModal.tsx`, `src/components/player/VideoPlayer.tsx` & `src/App.tsx`:
     - Truyền `defaultAudio` vào `MovieDetailModal`, tự động chọn máy chủ Thuyết Minh/Lồng Tiếng bằng `selectPreferredServerIdx` ngay khi nạp chi tiết và trong `VideoPlayer`, hiển thị rõ tổng số tập ở tiêu đề danh sách tập và chuẩn hóa nhãn `Tập X` cho các server chỉ trả về số.
- **Kiểm tra thực tế:** Kiểm tra DOM trên headless Edge (`vite` dev): mở chi tiết *Đấu Phá Thương Khung* hiển thị đúng `Tập 135/156`, `Chọn Máy Chủ & Danh Sách Tập (45 tập)`, tự động chọn `KKPhim • Lồng Tiếng (45)` và render đủ 45 nút tập (`Tập 01`..`Tập 45`).
- **Trạng thái Build:** `tsc -b && vite build` OK (3.97s), `cap sync android` OK (0.073s), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 14s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (6.93 MB).

---

### [2026-11-25] - Phiên 7: Tích Hợp Nguồn YanHH3D Thực Tế (Scraper, Native HTTP, Phân Loại, Lịch Chiếu & Embed Player)
- **Vấn đề / Yêu cầu:** Tiếp tục và hoàn tất tích hợp nguồn `https://yanhh3d.ee` (YanHH3D) làm nguồn dữ liệu & phát phim thực tế ngang hàng với KKPhim/NguonC trên cả Web Dev và Android Native.
- **Giải pháp & Các file đã sửa:**
  1. `src/utils/httpClient.ts` & `vite.config.ts`:
     - Hỗ trợ gọi HTTP đa nền tảng qua `CapacitorHttp` trên Android Native (bỏ qua CORS, gửi User-Agent + Referer chuẩn) và Vite Dev Proxy (`/yan-proxy`) khi chạy trên trình duyệt dev.
  2. `src/services/yanhh3d.service.ts` & `src/types/index.ts`:
     - Triển khai `YanHH3DService` nạp danh mục thể loại/trạng thái (`wp/v2/categories`, `wp/v2/status`), danh sách phim (`wp/v2/posts` theo trang, thể loại, trạng thái, lịch chiếu `showtimes`, tìm kiếm), bóc tách danh sách tập + danh sách máy chủ (`tiktik`, `pro`, `vip4k`, `tiktm`) và giải mã link phát trực tiếp/embed qua `player.php?action=dox_ajax_player` (chuẩn hóa link `//` và `/`).
  3. `src/services/api.service.ts` & `src/services/storage.service.ts`:
     - Sửa lỗi TypeScript TS2339 khi hợp nhất `YanHH3DService.fetchYanMovieDetail` trong `Promise.allSettled`, khớp slug chặt chẽ hơn, giữ thông tin tập cao nhất khi gộp đa nguồn, và tự động migrate domain cũ `yanhh3d.tv` sang `yanhh3d.ee`.
  4. `src/App.tsx`, `src/components/schedule/ScheduleView.tsx` & `src/components/catalog/MovieDetailModal.tsx`:
     - Nối danh mục thể loại/trạng thái động từ YanHH3D vào bộ lọc `HomeView` (hỗ trợ lọc cả theo `statusLabel` *Đang chiếu / Hoàn thành*), nạp lịch chiếu theo từng thứ trong tuần (`showtimeId`) tại `ScheduleView`, và cập nhật hiển thị `latestEp / totalEp` thực tế sau khi nạp chi tiết phim.
  5. `src/components/player/VideoPlayer.tsx`:
     - Bổ sung trạng thái `isLoadingServers` để chờ danh sách máy chủ thực tế trước khi fallback, hỗ trợ giải mã stream YanHH3D theo yêu cầu (`needsYanResolve`) và bố cục `flex-col` riêng cho chế độ Embed (`useEmbedNow`) để thanh điều khiển ứng dụng không che khuất nút điều khiển bên trong iframe.
- **Trạng thái Build:** `tsc -b && vite build` OK (4.52s), `cap sync android` OK (0.108s), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 14s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (6.93 MB).

---

### [2026-11-25] - Phiên 6: Thay Đổi Toàn Bộ Avatar & Icon Ứng Dụng (Android, TV Banner, PWA, Header)
- **Vấn đề / Yêu cầu:** Người dùng yêu cầu thay đổi avatar/biểu tượng của ứng dụng bằng hình ảnh nhân vật 3D đính kèm.
- **Giải pháp & Các file đã sửa:**
  1. **Android Launcher Icons & TV Banner (`android/app/src/main/res/`):**
     - Tạo mới toàn bộ `ic_launcher.png`, `ic_launcher_round.png` (bo tròn mượt) và `ic_launcher_foreground.png` (Adaptive Icon tỷ lệ vùng an toàn 72%) cho đủ 5 mật độ màn hình: `mipmap-mdpi`, `mipmap-hdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, `mipmap-xxxhdpi`.
     - Cập nhật màu nền Adaptive Icon tại `android/app/src/main/res/values/ic_launcher_background.xml` sang `#0B1528` đồng bộ với ảnh avatar.
     - Tạo mới `android/app/src/main/res/drawable/tv_banner.png` (320×180) và gắn `android:banner="@drawable/tv_banner"` trong `android/app/src/main/AndroidManifest.xml` để hiển thị đẹp trên Android TV Leanback Launcher.
  2. **Web / PWA / Header Logo:**
     - Lưu `src/assets/app-avatar.jpg`, `public/icon-192x192.png`, `public/icon-512x512.png`, bổ sung `src/vite-env.d.ts`.
     - Cập nhật `<link rel="icon">` và `<link rel="apple-touch-icon">` trong `index.html`.
     - Cập nhật `src/components/navigation/Header.tsx` hiển thị avatar tròn mới thay cho icon cũ, và cập nhật `src/components/settings/SettingsView.tsx` vẽ avatar mới vào canvas PWA Studio.
- **Trạng thái Build:** `tsc -b && vite build` OK (4.49s), `cap sync android` OK (0.079s), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 20s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (6.59 MB).

---

### [2026-11-25] - Phiên 5: Giao Diện Trình Phát Toàn Màn Hình & Cử Chỉ Vuốt Độ Sáng / Âm Lượng
- **Vấn đề / Yêu cầu:** Thiết kế lại giao diện trình phát video toàn màn hình khớp ảnh minh họa và bổ sung tính năng vuốt chạm cảm ứng tăng/giảm Độ sáng (bên trái) và Âm thanh (bên phải).
- **Giải pháp & Các file đã sửa:**
  1. `src/components/player/VideoPlayer.tsx`:
     - Chuyển sang bố cục **Full-screen Cinema Overlay** (`fixed inset-0 bg-black`), tự động ẩn HUD sau 3.5 giây.
     - **Thanh Top Bar:** Nút quay lại, tên phim + tập, badge `Phân loại: ...` viền vàng cam bên trái; nút Khóa cảm ứng (`fa-lock`), PiP / Tốc độ phát và Toàn màn hình / TV ở góc phải.
     - **Nút điều khiển giữa màn hình:** Nút tròn mờ tua lùi `seekStep` giây (mặc định 10s), nút Play/Pause lớn ở tâm và nút tua tới `seekStep` giây.
     - **Thanh Bottom Bar:** Thanh tiến độ màu vàng cam (`amber-400`) kèm hiển thị thời gian `H:MM:SS` / `MM:SS` và 6 nút chức năng: *Tỷ lệ (Vừa/Lấp đầy/Giãn/4:3)*, *Khóa đứng*, *Nguồn*, *Thuyết minh / Vietsub*, *Tập sau*, *Danh sách tập* (mở Drawer trượt phải với phân nhóm 50 tập/trang không che khuất video).
     - **Vuốt cảm ứng 2 vùng (Touch & Pointer Gesture):**
       - Nửa **trái** màn hình: Vuốt lên/xuống điều chỉnh **Độ sáng** (`15%–100%`) kết hợp lớp dimmer + filter độ sáng kèm cột hiển thị `%` trực quan.
       - Nửa **phải** màn hình: Vuốt lên/xuống điều chỉnh **Âm lượng** (`0%–100%`) trực tiếp trên thẻ `<video>` kèm cột hiển thị `%` trực quan.
       - Hỗ trợ khóa cảm ứng (`isLocked`) chống chạm nhầm khi đang xem.
- **Trạng thái Build:** `tsc -b && vite build` OK (4.75s), `cap sync android` OK (0.089s), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 15s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (4.24 MB).

---

### [2026-11-25] - Phiên 4: Tích Hợp & Tối Ưu Hóa Kiến Trúc HH3D TV / Failover / Detail / Player Tuning
- **Vấn đề / Yêu cầu:** Tích hợp tối ưu các kỹ thuật từ đặc tả `hh3d.apk` vào app HoatHinh3D Universal (tổng hợp đa nguồn failover, bộ đệm nhanh cho TV Box, tự động chuyển máy chủ khi lỗi, màn hình chi tiết phim & phân nhóm tập, điều hướng D-Pad không gian, tối ưu ảnh & WebView native).
- **Giải pháp & Các file đã sửa:**
  1. `src/services/api.service.ts`:
     - Nâng cấp thành **Multi-Source Aggregator & Failover Engine** kết hợp đồng thời `phimapi.com` (KKPhim HLS) + `phim.nguonc.com` (NguonC dự phòng), kèm **TTL Cache 2 giờ** (memory + `sessionStorage`) giúp chuyển tab và mở chi tiết phim tức thì.
     - Thêm `getOptimizedPosterUrl` (nén WebP qua `wsrv.nl` w=400 tiết kiệm RAM cho TV Box 1-2GB, tự fallback URL gốc trong `MovieCard` nếu lỗi).
  2. `src/components/catalog/MovieDetailModal.tsx` & `src/components/catalog/MovieCard.tsx`:
     - Tạo mới màn hình chi tiết phim (`Hh3dDetailContainer` + nền mờ `Hh3dPortalBackdropView`), nạp mô tả/máy chủ thực tế, nút *Xem Ngay / Tiếp Tục Tập X*, *Thêm tủ phim*, chuyển đổi máy chủ và **phân nhóm tập 50 tập/trang** (`1 - 50`, `51 - 100`, ...).
  3. `src/components/home/HomeView.tsx`:
     - Bổ sung **Hero Spotlight Banner** tự xoay 5 phim Niên Phiên nổi bật và **Bảng Xếp Hạng Thịnh Hành** dạng hàng ngang có huy hiệu `#1..#8`.
  4. `src/components/player/VideoPlayer.tsx`:
     - Tinh chỉnh cấu hình `Hls.js` phát nhanh ít xoay vòng cho TV Box (`maxBufferLength: 15`, `maxMaxBufferLength: 30`, `lowLatencyMode: true`, `startFragPrefetch: true`, timeout 8–10s).
     - **Auto Server Failover:** Khi luồng HLS lỗi nghiêm trọng, tự động thử chuyển sang máy chủ kế tiếp trong `serverGroups` hoặc sang chế độ **Iframe Embed** dự phòng (`linkEmbed`).
     - **Player HUD tự ẩn sau 3.2 giây** hoạt động chuẩn trên cả cảm ứng Mobile và Remote TV, bổ sung nút Tập trước / Tập sau & phân nhóm tập 50 tập/trang.
  5. `src/utils/tvSpatialNav.ts`, `src/hooks/useTVRemote.ts`, `src/App.tsx`:
     - Triển khai thuật toán **Spatial D-Pad Navigation 2D** di chuyển focus chính xác giữa các `.tv-focus-target` bằng phím mũi tên Remote TV Box.
  6. `android/app/src/main/java/com/hoathinh3d/donghua/MainActivity.java` & `android/app/build.gradle`:
     - Bật `FLAG_KEEP_SCREEN_ON`, `setMediaPlaybackRequiresUserGesture(false)`, `MIXED_CONTENT_ALWAYS_ALLOW`, `setDomStorageEnabled(true)` ở tầng Native WebView; đồng bộ `versionCode 35`, `versionName "3.5.0-universal"`.
- **Trạng thái Build:** `tsc -b && vite build` OK (3.70s), `cap sync android` OK (0.074s), Gradle `assembleDebug` OK (`BUILD SUCCESSFUL in 19s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (4.21 MB).

---

### [2026-11-25] - Phiên 3: Nâng Cấp Toàn Diện Trung Tâm Cài Đặt (7 Phân Mục) & Hệ Thống Liquid Glass
- **Vấn đề / Yêu cầu:** Người dùng yêu cầu đổi lại giao diện Cài đặt của app kèm các tính năng, phân mục phù hợp theo 6 ảnh minh họa (Liquid Glass UI + 7 subtabs chuyên sâu).
- **Giải pháp & Các file đã sửa:**
  1. `src/types/index.ts` & `src/services/storage.service.ts`:
     - Bổ sung `ThemePreset` (`pearl`, `aqua`, `sakura`, `dark`), `SettingsSubtab`, `ExtensionSettings` (`adblock`, `aiUpscale`, `bilingualSub`, `ambientGlow`).
     - Mở rộng `AppSettings`: `glassBlur`, `ambientOrbs`, `preferredSource`, `cloudAutoSync`, `autoSkipIntro`, `seekStep`, `extensions` (tương thích ngược và tự chuẩn hóa từ bản cũ).
     - Bổ sung `toggleSourceEnabled`, `addCustomMovie`, `saveCloudSnapshot`, `restoreCloudSnapshot`, `getCloudSnapshotMeta`.
  2. `index.html` & `src/index.css`:
     - Triển khai hệ thống biến CSS Liquid Glass hỗ trợ đủ 4 theme (**Light Pearl** mặc định, **Crystal Aqua**, **Sakura Glass**, **Dark Aurora**), điều chỉnh độ mờ thấu kính `--glass-blur` (8px–40px), 3 hiệu ứng Giọt sáng nền (`liquid-orb`), và bố cục theo `data-resolved-device` (`mobile`, `tablet`, `tv`) giữ nguyên D-Pad focus ring (`tv-focus-target`).
  3. `src/components/settings/SettingsView.tsx` & `src/components/common/GoogleLoginModal.tsx`:
     - Thiết kế lại hoàn toàn thành **Trung Tâm Cài Đặt Hệ Thống** với 7 phân mục đầy đủ chức năng:
       - **1. Tài khoản:** Đăng nhập Google Cloud Modal, badge Khách / SVIP Cloud, bật/tắt tự động sao lưu Cloud, đồng bộ nguồn web & khôi phục snapshot đám mây.
       - **2. Tổng quan App:** 4 thẻ thống kê live (Kho phim & tập, Nguồn hoạt động, Lịch sử, Tủ phim) + Sơ đồ 6 phân khu điều hướng nhanh.
       - **3. Trình phát tích hợp:** Chất lượng mặc định, Âm thanh/Phụ đề ưu tiên (Vietsub / Thuyết Minh), Bước nhảy tua nhanh (5/10/15/30s), Tốc độ phát mặc định, Auto Skip Intro (+85s), Tự động chuyển tập.
       - **4. Nguồn phim (+Web):** Thêm nguồn từ URL web bất kỳ với 5 preset 1-chạm (`kkphim`, `xiaofilm.tv`, `phim.nguonc.com`, `phimchill.tv`, `vsmov.com`), nút `Quét & Kiểm tra Web`, `Đo Ping Tất Cả`, đặt nguồn ưu tiên, bật/tắt & xóa nguồn web ngoài.
       - **5. Giao diện:** Chọn thiết bị (`Tự động`, `Điện thoại`, `Máy tính bảng`, `Tivi Smart TV` kèm banner hỗ trợ Remote), 4 chủ đề Liquid Glass (`Light Pearl`, `Crystal Aqua`, `Sakura Glass`, `Dark Aurora`), thanh trượt Backdrop Blur & bật/tắt Giọt Sáng Nền.
       - **6. Cập nhật & Sao lưu:** Thông tin gói Universal Android API 23→35, xem/tải `AndroidManifest.xml`, Xuất/Nhập JSON, tạo & tải Icon PWA Canvas 192/512 PNG, `manifest.json` & `sw.js`.
       - **7. Tiện ích mở rộng:** AdBlock Shield Pro, AI HDR Upscale, Phụ đề Song Ngữ Việt-Trung, Hào Quang Rạp Phim (Ambient Glow) và Hẹn Giờ Tắt Phim Khi Ngủ (Sleep Timer đếm ngược thực).
  4. `src/components/navigation/Header.tsx`, `BottomLiquidNav.tsx`, `Toast.tsx`, `HomeView.tsx`, `MovieCard.tsx`, `ScheduleView.tsx`, `HistoryView.tsx`, `FavoritesView.tsx`:
     - Đồng bộ toàn bộ giao diện theo Liquid Glass 4 theme; thêm nút nhanh `Tải APK`, chuyển chế độ thiết bị, nguồn ưu tiên, tài khoản Google trên Header và Floating Dock đáy có badge số lượng Tủ phim.
  5. `src/components/player/VideoPlayer.tsx` & `src/App.tsx`:
     - Nối trực tiếp toàn bộ cấu hình Trình phát (`seekStep`, `playbackSpeed`, `autoSkipIntro`, `autoNext`, `defaultAudio`), Tiện ích mở rộng (`aiUpscale`, `ambientGlow`, `bilingualSub`, `adblock`) và bộ đếm Sleep Timer vào trình phát video thực tế.
- **Trạng thái Build:** `tsc -b && vite build` OK (4.77s / 3.37s), Capacitor sync OK (0.134s), Gradle APK build OK (`BUILD SUCCESSFUL in 13s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (4.47 MB).

---

### [2026-11-24] - Phiên 2: Khắc phục Lỗi Kết nối Nguồn Phim & Tích hợp Kho Nguồn HH3D Thực Tế
- **Vấn đề / Yêu cầu:** Người dùng phản ánh *"Không thể kết nối với nguồn phim. hãy tạo kho nguồn tương tự hh3d"*.
- **Nguyên nhân cốt lõi (Root Cause):** Phiên bản trước sử dụng video mẫu MP4 giả lập của Google và chưa có logic nạp danh sách tập thực tế từ các API Donghua (phimapi, nguonc).
- **Giải pháp & Các file đã sửa:**
  1. `src/types/index.ts`: Bổ sung kiểu `EpisodeItem`, `ServerGroup`, `slug`, `serverGroups` cho `DonghuaMovie`.
  2. `src/services/api.service.ts`: Xây dựng service kết nối trực tiếp kho API HH3D (phimapi.com):
     - `fetchDonghuaList`: Tự động nạp 24-30 phim Donghua 3D mới nhất kèm poster chất lượng cao từ `phimimg.com`.
     - `fetchMovieDetail`: Bóc tách đầy đủ danh sách máy chủ (`Vietsub`, `Thuyết Minh`) và toàn bộ link stream `.m3u8` thực tế (e.g. Tiên Nghịch 168 tập, Đấu Phá 135 tập, Thế Giới Hoàn Mỹ 288 tập...).
     - `searchDonghua`: Tìm kiếm phim trực tiếp theo từ khóa.
  3. `src/constants/initialData.ts`: Cập nhật toàn bộ catalog cơ sở với slug thật, poster CDN `phimimg.com` và link M3U8 ban đầu.
  4. `src/components/player/VideoPlayer.tsx`: Nạp danh sách tập động theo từng máy chủ, hỗ trợ Hls.js stream đa luồng, xử lý lỗi tự động đề xuất chuyển máy chủ.
  5. `src/App.tsx`: Tự động đồng bộ kho phim khi mở app và khi đổi nguồn phát trên Header.
- **Trạng thái Build:** Vite build OK, Capacitor sync OK, Gradle APK build OK (`BUILD SUCCESSFUL in 14s`).
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk` (4.49 MB).

---

### [2026-11-24] - Phiên 1: Khởi tạo Kiến trúc Universal & Triển khai 5 Giai đoạn
- **Vấn đề / Yêu cầu:** Nghiên cứu file HTML 536KB `HoatHinh3D-Universal-Android-Bundle.html` bằng phương pháp Craft Anything và chuyển đổi thành app hoàn chỉnh hỗ trợ Android TV, Phone, Tablet.
- **Thực hiện:**
  1. Đã bóc tách 14 module chức năng gốc.
  2. Xây dựng dự án Vite + React 19 + TypeScript + Tailwind CSS.
  3. Xây dựng hoàn chỉnh 5 view chính: Home, Schedule, History, Favorites, Settings.
  4. Hoàn thiện `VideoPlayer` hỗ trợ HLS, đa server, chọn tập, Vietsub/Thuyết minh, tua nhanh, resume progress.
  5. Cài đặt OpenJDK 21 thay cho JBR 25 để tương thích Gradle 8.11.1 (sửa lỗi `major version 69`).
  6. Viết script tự động `build-apk.ps1` và tích hợp lệnh `npm run build:apk`.
- **Trạng thái Build:** Web build thành công (3.22s), Capacitor sync hoàn tất, Gradle APK build thành công.
- **File APK tạo ra:** `E:\Project\3_HoatHinh3D\HoatHinh3D-Universal-debug.apk`

---

## 6. TỔNG KẾT BÀN GIAO & TRẠNG THÁI HIỆN TẠI (HANDOFF STATUS)

- **Ngày chốt phiên:** `2026-11-28` (sau Phiên 58).
- **Phiên bản hiện tại:** `versionCode 58` / `versionName "3.6.8-v58"` (Header giao diện chính hiển thị `Hoạt Hình` + `3D`, `app_name` là `HH3D v3.6.8-v58`, chứng chỉ ký chung `SHA-256: e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`).
- **Kiến trúc hiện tại:** **Android Native thuần (Kotlin + XML Views + ViewBinding + AndroidX Media3 ExoPlayer 1.5.1)** trong `android/` (bản React/Vite cũ trong `src/` được giữ nguyên làm tham chiếu).
- **Quy tắc Build & Xuất bản OTA (Phiên 41+ / 53+):** Không tạo Release R8 APK; mọi máy build (Công ty & Nhà) phải có sẵn bộ keystore đồng bộ tại `%USERPROFILE%\.hoathinh3d\` khớp `android/signing-sha256.txt`. Cuối phiên tăng `versionCode`/`versionName`, chạy `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` + `.\gradlew.bat lintDebug`, commit + push lên GitHub, rồi chạy `powershell -ExecutionPolicy Bypass -File .\publish-ota.ps1 -SkipBuild -Changelog "..."` để xuất bản `HoatHinh3d.apk` lên GitHub Release `v<versionCode>` và cập nhật `version.json` trên `main`.
- **Trạng thái Code & Test:** `compileDebugKotlin` sạch, `lintDebug` đạt 0 errors, `testDebugUnitTest` qua **56/56 unit tests** (`NguonCApiAndRepoTest` 16, `PlayerHelpersTest` 12, `PrefsStoreTest` 9, `VietnameseSearchTest` 3, `VsmovSourceProviderTest` 4, `YanHh3dNowScraperTest` 6, `YanHh3dScraperTest` 6 — 0 failures, 0 errors, 0 skipped).
- **Trạng thái OTA, Tương Thích Android 6.0.1 / Xiaomi MiTV4, Player & Cài Đặt:**
  - **Tìm kiếm thông minh, Pull-to-refresh, Khóa màn hình tự ẩn 3s & Chia sẻ nhanh (Phiên 49):**
    - `PrefsStore` lưu lịch sử tìm kiếm (tối đa 10 mục, chống trùng, xóa lẻ/xóa hết) kèm `searchSuggestionsPanel` Liquid Glass (Từ khóa Hot Thịnh Hành + Lịch sử gần đây) trong `MainActivity`.
    - `SwipeRefreshLayout` bọc `rvHomeFeed` (`HomeFragment`) và `rvSchedule` (`ScheduleFragment`), đổi màu spinner theo theme, tự tắt trên Android TV để giữ nguyên D-Pad.
    - `PlayerActivity` nâng cấp `btnUnlockScreen` tại `top|start` dùng VectorDrawable `ic_cs_lock` (không emoji) tự ẩn sau 3s, chạm màn hình hiện lại; thêm `PlayerUtils.formatShareText` + nút Chia sẻ nhanh (`btnQuickShare`, `btnDetailShare`).
  - **Sửa trùng lặp phim & mất poster, tối ưu đa thiết bị & nguồn phát (Phiên 48):**
    - Không còn trộn `InitialData.INITIAL_DONGHUA_CATALOG` vào feed sống (`loadLiveDonghuaFeed`), thay sạch URL `phimimg.com` chết (404) bằng link `yanhh3d.now` xác thực + `bg_poster_placeholder.xml`, hợp nhất khử trùng lặp (`mergeMoviePair`, `extractPosterUrl`) theo slug/tiêu đề & khử trùng `ServerGroup`; đồng bộ lề ngang (`14/18/22dp`), kích thước card/hero/chi tiết phim cho Mobile / PC-Tablet / TV và sắp xếp tập mới nhất trước trong Drawer của `PlayerActivity`.
  - **Menu Cài đặt phân nhóm 2 tầng trên Điện thoại (Phiên 47):**
    - `SettingsFragment` + `MainActivity` (`MainState.isSettingsDetailOpen`, `closeSettingsDetail`) hiển thị riêng trên `MOBILE` menu phân nhóm **TRẢI NGHIỆM & PHÁT** (*Trình phát tích hợp*, *Giao diện*, *Tiện ích mở rộng*) và **HỆ THỐNG & DỮ LIỆU** (*Nguồn phim (+Web)*, *Cập nhật và Sao lưu*) trong thẻ kính bo góc `18dp` kèm ô biểu tượng `36dp`, vạch ngăn inset, mũi tên phải `›` và nút Back quay lại đúng luồng; vẫn giữ thanh 5 pill ngang cho Tablet/PC và TV.
  - **Đồng bộ `Lịch sử` giống `Yêu thích`, Thanh điều hướng nổi bật & `Hoàng Hôn Đêm Sâu` (Phiên 46):**
    - `HistoryFragment` + `fragment_history.xml` dùng chung lưới poster `MovieCardAdapter` (`3`/`4`/`5` cột), chạm phát tiếp ngay tại đúng phút/giây, nhấn giữ mở menu thao tác/xóa; thanh điều hướng kính đáy & TV Rail bọc `RippleDrawable`, `navFeatureIconDrawable` nổi bật từng biểu tượng + hiệu ứng nhấn đàn hồi (`attachLiquidNavTapEffect`); chủ đề `DARK` chuyển sang **Hoàng Hôn Đêm Sâu** (`#040507` / vàng hoàng hôn dịu `#E2AE58`).
  - **Chi tiết phim mới nhất trước, Thể loại phim thời gian thực, 3 cột Điện thoại & Tinh gọn Giao diện (Phiên 45):**
    - Chi tiết phim (`MovieDetailActivity`) hiển thị số tập mới nhất trước (`Tập N -> Tập 1`) theo trang 50 tập giảm dần.
    - Bấm `Thể loại phim ▾` trên Trang chủ mở bảng **Liquid Glass Thể Loại Phim** (`floatingGenrePickerOverlay`) kèm đồng bộ thời gian thực các chủ đề (*Phim Hot, Phim Chiếu Rạp, Phim Lẻ, Phim Bộ, Phim Hoạt Hình, Phim Hành Động, Tình Cảm* + toàn bộ thể loại Web).
    - `DeviceProfileResolver.gridSpanCount`: `MOBILE = 3`, `TABLET = 4`, `TV = 5`; `MovieCardAdapter` co giãn tỷ lệ card 3 cột cho điện thoại; `ScheduleFragment` & `rvOfflineEpisodes` hiển thị 2 cột trên Tablet/PC/TV; `PlayerActivity` cân chỉnh nút Play/Tua & thanh công cụ đáy theo thiết bị.
    - Tinh gọn `3. Giao diện`: bỏ `Xanh Băng` (chỉ giữ 3 tông màu dịu nhẹ `Ngọc Trai Dịu Nhẹ`, `Anh Đào Phấn Nhạt`, `Đêm Cực Quang Dịu`), bỏ `Hệ thống`, bỏ Độ trong suốt (mặc định `52%`), Độ mờ (mặc định `32%`), Giọt sáng nền (mặc định Bật) và Tùy biến bố cục Trang chủ (`APPEARANCE_DEFAULTS_V45`).
  - **Sửa lỗi văng app sau 1s trên Xiaomi MiTV4-ANSM0 & tối ưu GPU/RAM (Phiên 44):**
    - Bật `coreLibraryDesugaringEnabled true` (`com.android.tools:desugar_jdk_libs:2.1.4`) để rewrite toàn bộ API Java 8+ trong `Jsoup 1.18.3` (`java.util.stream`, `Optional`, `Collectors`), Media3 Guava, AndroidX và Kotlin sang `j$.util.*` (`9,180` lệnh gọi đã desugar theo `dexdump`), triệt tiêu `NoClassDefFoundError` khi bóc tách HTML ở Android 6.0.1 (API 23).
    - Nạp thêm Root CA `Sectigo E46` & `R46` vào `modern_roots.pem` (tổng **17 Root CA**), đặt `windowBackground` `@color/pearl_bg_primary`, thêm `liteRender` bỏ `specularSheenLayer` & `clipToOutline` trên máy `isUltraLowRamDevice`, giới hạn pool/cache `HomeFragment` (`1` carousel `5` item + pool `6`), mở rộng `catch (Throwable)` (giữ nguyên `CancellationException`) và lùi auto-update sang `4.5s`.
  - **Tinh gọn UI, đổi tên gói APK & tối ưu hiệu năng toàn app (Phiên 43):**
    - Đổi tên gói xuất ra thành **`HoatHinh3d.apk`** (`build-apk.ps1`, `publish-ota.ps1`, Release asset); ẩn `Nhật ký lỗi` & nút/URL `URL cập nhật` trong `4. Cập nhật & Sao lưu` (giữ 3 nút `Kiểm tra hệ thống`, `Dọn Cache`, `Kiểm tra cập nhật`).
    - Trang chủ & BXH dùng `PAYLOAD_META` + cache drawable huy hiệu để không nháy ảnh Glide khi đổi Yêu thích/Tiến độ; `MainActivity.onDestroy()` hủy toàn bộ Job/Handler và xóa cache ở luồng nền; `PlayerActivity` tạm dừng `progressTicker` theo `onPause()`/`onResume()`, giữ trần `1080p` khi chọn ABR trên máy RAM thấp và ghi nhận đã xem ở `STATE_ENDED`.
  - **Cập nhật OTA tự động qua GitHub (Phiên 41–42):**
    - `AppUpdateChecker.DEFAULT_MANIFEST_URL` trỏ mặc định tới `https://raw.githubusercontent.com/long-1285/Hoathinh3D/main/version.json` (tự fallback sang `GITHUB_RELEASES_LATEST_URL` kèm `withCacheBuster`), kiểm tra `SHA-256` + magic ZIP `'P''K'`, tải với timeout `60s`/`5m`, lưu vào `externalCacheDir/updates` trên API < 24 (`MiTV4-ANSM0`) và tự tiếp tục cài đặt sau khi cấp quyền.
  - **Tương thích Android 6.0.1 / Xiaomi MiTV4-ANSM0 nền tảng (Phiên 35 & 40):**
    - Sửa sạch `NewApi` API 24/26 (`decodeBase64Compat` thuần Kotlin, bỏ `Map.putIfAbsent`, đổi nút có `android:drawableStart` vector sang `LinearLayout` + `ImageView(app:srcCompat)`, `SwitchCompat`, `setCompatVectorFromResourcesEnabled(true)`).
    - Ngưỡng **Ultra-Low RAM (`<= 1800 MB`)** cho MiTV4 `1.6 GB` / `armeabi-v7a` / ổ `5.0 GB`: OkHttp `12` requests (`4/host`), `InstantLoadControl` `20 MB`, `SimpleCache` `64 MB`, tắt prefetch segment nền, `DefaultRenderersFactory.setEnableDecoderFallback(true)`, `RGB_565` + `override()` + `dontAnimate()`, `<uses-feature android:name="android.hardware.wifi" android:required="false" />`.
  - **Đồng bộ Thể loại Thời Gian Thực & Tinh Gọn Cài Đặt Trình Phát (Phiên 39):**
    - Bỏ nút `Dọn bộ nhớ đệm phát HLS` khỏi `1. Trình phát tích hợp`; đổi nhãn đầu thanh thể loại Trang chủ sang **`Thể loại phim`** và đồng bộ thời gian thực danh mục cho `YanHH3DNow`, `YanHH3D` & mọi nguồn `+Web`.
  - **Nâng cấp Phiên 37–38:** Bỏ banner `"Đang kết nối..."` & toast lúc mở app; SHA-256 Auto Update; `SettingsFragment` 5 tab (`1. Trình phát tích hợp` → `5. Tiện ích mở rộng`) 2 cột trên TV/Tablet + bật/ẩn Banner Hero / Tiếp tục xem / Top 10 BXH; `StoryboardParser` (`seekThumbCard`); Đồng bộ Email + LAN Sync UDP `8776` + `HH3D1:...`; Auto Clear Cache khi thoát App.
  - **Mạng TLS 1.2 + Root CA Hiện Đại & Player CloudStream (Phiên 33–36):** `CompatTls` (17 Root CA + TLS 1.2 + HTTP/1.1 API 23), `GlideOkHttpUrlLoader`, Header bỏ chữ `Nguồn phim`, chọn nguồn kính trong suốt, **Tiếp tục xem / Lịch sử / Tiếp tục Tập X** phát đúng phút/giây, **Bỏ qua Intro (`+85s`)** tự ẩn sau 5s, phụ đề rời `±0.5s`, Night Mode DRC.
- **File APK mới nhất:** `HoatHinh3d.apk` (**11.7 MB**, `12,267,375 bytes`, `APK SHA-256: ecabc261849594f72694db74f130ed67ecdcea704e5877d6e75a8fecf66a937e`, `Cert SHA-256: e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`), build bằng `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` và xuất bản OTA lên GitHub Release `v58` + `version.json` (Phiên 58 chưa chạy đo trực tiếp trên phần cứng thật).
- **Điểm cần lưu ý / Kiểm thử thiết bị thật ở phiên sau:**
  1. Nếu PC Công ty chưa chép thư mục `%USERPROFILE%\.hoathinh3d\` (`hoathinh3d-shared.jks` + `keystore.properties`), chép sang và chạy `.\setup-signing.ps1 -Verify` để khớp chữ ký chung.
  2. Cài đặt **`HoatHinh3d.apk` (`v3.6.8-v58`)** lên điện thoại & **Xiaomi MiTV4-ANSM0** (Android 6.0.1 API 23) để kiểm tra thực tế trình phát đã bỏ nút Chia sẻ, tự động chọn **`Auto • Thuyết Minh`** (theo mạng thực tế), và màn Chi tiết phim ẩn hàng chip máy chủ con trong khung đỏ.

---

## 7. PROMPT CHUYỂN GIAO CHO AI AGENT PHIÊN SAU

```markdown
Hãy tiếp tục phát triển dự án HoatHinh3D Universal (Android Native Kotlin + XML Views + Media3 ExoPlayer).

Trước khi sửa code, hãy đọc kỹ toàn bộ `PROJECT_MEMORY.md` và `GEMINI.md` tại thư mục gốc dự án để nắm kiến trúc Native, quy tắc môi trường build APK (bắt buộc qua `build-apk.ps1`, **KHÔNG tạo Release R8 APK**, bắt buộc ký bằng Shared Keystore tại `%USERPROFILE%\.hoathinh3d\` khớp `android/signing-sha256.txt`), lịch sử thay đổi tới Phiên 58, và **so sánh đối chiếu mã nguồn trên máy với Git (`https://github.com/long-1285/Hoathinh3D.git`)** — nếu trên Git mới hơn thì kéo/clone phiên bản mới nhất về trước khi thực hiện debug.

Tóm tắt hiện trạng sau Phiên 58:
- Phiên bản hiện tại: `versionCode 58`, `versionName "3.6.8-v58"` (Header giao diện chính hiển thị `Hoạt Hình` + badge `3D`, `app_name` là `HH3D v3.6.8-v58`). Mã nguồn Android Native nằm tại `android/` (Kotlin + XML Views + ViewBinding + Media3 ExoPlayer 1.5.1 + OkHttp + Jsoup + Glide, `com.hoathinh3d.donghua`, `minSdk 23`, `targetSdk 35`, hỗ trợ Android TV `LEANBACK_LAUNCHER`).
- APK mới nhất tại `HoatHinh3d.apk` (**11.7 MB**, `12,267,375 bytes`, `APK SHA-256: ecabc261849594f72694db74f130ed67ecdcea704e5877d6e75a8fecf66a937e`, `Cert SHA-256: e2acc2dd967ee6c277ffd9fdaca3c0e1fddad22c1e40cd8bbe70a5fa23ef63de`), đã qua **56/56 unit tests** (`testDebugUnitTest`), `lintDebug` (0 errors), đã xuất bản OTA lên GitHub Release `v58` + `version.json`.
- **Phiên 58 (Gỡ Chia Sẻ Trình Phát, Mặc Định `Auto • Thuyết Minh` & Ẩn Thanh Chọn Máy Chủ Con Chi Tiết Phim):** Xóa `btnQuickShare` & `shareCurrentMovieOrEpisode()` khỏi `activity_player.xml` / `PlayerActivity.kt`; thêm `Keys.QUALITY_AUTO_TM_DEFAULT_V58` đặt mặc định `defaultQuality = "Auto"` & `defaultAudio = "Thuyết Minh"` (nhãn Cài đặt `"Auto • Thuyết minh (Theo mạng thực tế)"`, badge Player hiển thị `Auto` lúc mở); ẩn `serverChipsScroll` trong `activity_movie_detail.xml` / `MovieDetailActivity.kt`, giữ 2 tab nguồn `Thuyết Minh` / `Vietsub` và tự động chọn máy chủ tối ưu + failover trong trình phát.
- **Phiên 57 (Kiến Trúc Đa Nguồn `MovieSourceProvider`, Nguồn Mới `VSMov`, `HeadlessWebViewSniffer` & Header Động ExoPlayer):** Tạo interface chuẩn hóa đa nguồn `MovieSourceProvider.kt`, triển khai `VsmovSourceProvider.kt` cho `https://vsmov.com/` (JSON API ưu tiên + Jsoup HTML fallback, CookieJar + Retry trên `Dispatchers.IO`, tìm kiếm, lọc theo thể loại/quốc gia/năm/xem nhiều, chi tiết đa server & bóc tách `signedMasterUrl`/sub/thumb từ `streamvsmov.com`), tách `HeadlessWebViewSniffer.kt`, thêm `ExoPlayerStreamHelper` (`DefaultHttpDataSource.Factory` với custom HTTP headers) + `HlsCacheProvider.registerStreamHeaders()`, và tích hợp `VSMov` vào `InitialData`/`PrefsStore` (`SOURCES_V57_WITH_VSMOV`)/`MovieRepository`/`MovieDetailActivity`/`PlayerActivity`.
- **Phiên 56 (Nút `Làm mới` TV & `Ngẫu nhiên`, Nhắc Tập Mới Yêu Thích Trên Home, Lịch Sử Kèm Ngày & Nhảy Tập Chưa Xem / Đánh Dấu Đã Xem Hết):** Bổ sung `btnRefreshTvFeed` (hiện trên TV) và `btnHomeRandom` (`PlayerUtils.pickRandomMovie`); thêm dải `favUpdatesPanel` nhắc phim Yêu thích có `newEpCount > 0` ngay Trang chủ; gắn nhãn ngày xem (`Hôm nay`/`Hôm qua`/`dd/MM`, `java.util.Calendar` an toàn API 23) trong `HistoryFragment`; thêm `btnDetailNextUnwatched` (`⏭ Tập X (Chưa xem)`) và `btnToggleMarkAllWatched` (`✓ Đã xem hết` / `↺ Bỏ đánh dấu`) trong `MovieDetailActivity`.
- **Phiên 55 (Header `Hoạt Hình 3D`, Chất Lượng Mặc Định `Auto` ABR, Sửa Mục Yêu Thích Đủ 10/10, Gom Chọn Thiết Bị Vào Menu `Auto`, Hẹn Giờ Tắt & AudioFocus):** Header chính chỉ hiện `Hoạt Hình` + `3D`; `defaultQuality = "Auto"` với `AdaptiveTrackSelection.Factory(4s/10s)`; lưu snapshot `favoriteMovies` trong `PrefsStore` để `FavoritesFragment` luôn hiển thị đủ 100% phim đã chọn + menu nhấn giữ; gom 4 nút thiết bị thành `spinnerRow` `Auto` duy nhất; bật AudioFocus + `setHandleAudioBecomingNoisy(true)` + Sleep Timer trong `PlayerActivity`.
- **Phiên 52 (Nén WebP & Giảm APK còn 11.63 MB, Voice Search, Lọc Ad HLS, Dò Decoder TV, Chỉnh Phụ Đề Trực Tiếp & Tên App Kèm Phiên Bản):** Nén `app_avatar` (`2000×2000` `713 KB` → `192×192` WebP `15 KB`), `tv_banner`/`splash`/`mipmap` sang `.webp` + `resourceConfigurations ["vi","en"]`; tự gắn `HoatHinh3D v${versionName}` vào `app_name`/`txtHeaderBrand`; thêm `btnVoiceSearch` (`RecognizerIntent`); thêm `HlsPlaylistAdFilter` trong `SanitizingTsDataSource`; thêm `DeviceProfileResolver.detectVideoDecoderCapability()`; thêm chỉnh vị trí từ đáy (`%`), màu chữ & nền phụ đề trực tiếp trong Drawer của `PlayerActivity`.
- **Phiên 51 (Ẩn Hero Banner TV, Thu gọn 2 chế độ Giao diện & Tối ưu tổng thể MiTV4):** Ẩn `heroCard` ("Niên phiên nổi bật") và hủy Glide `imgHeroBg` trên `ResolvedDeviceProfile.TV`; thu gọn `ThemePreset` / `ThemeManager` / `PrefsStore` / `SettingsFragment` / `MainActivity` còn đúng **2 chế độ** (`PEARL` — *1. Sáng dịu Ngọc Trai* và `DARK` — *2. Hoàng Hôn Đêm*); truyền `ThemePalette.liteRender` cho toàn bộ helper drawable, bỏ `applyRoundedOutline` và tab fade animation khi `shouldUseReducedMotion`; thêm cache `homeHtmlCache` (`15s`) dùng chung ở `YanHh3dNowScraper`, throttle `onResume` (`45s`) trong `MainActivity`, tối ưu `HomeFragment` (`PAYLOAD_META` + diff) và `ScheduleFragment`, bổ sung `nextFocus*` trong `activity_main.xml`.
- **Phiên 50 (Tắt 100% giao diện động ở chế độ TV cho MiTV4):** `DeviceProfileResolver.shouldUseReducedMotion` luôn bật trên `TV` và `UltraLowRam`; bỏ `RippleDrawable`, `specularSheenLayer`, touch scale animation (`ThemeManager`, `MainActivity`); tắt `ambientOrbs`, `startHeroAutoRotate()`, `applyRoundedOutline(heroCard)` và `RecyclerView.itemAnimator` trên toàn bộ tab; tắt animation `btnSkipIntro` ở `PlayerActivity`.
- **Phiên 49 (Tìm kiếm thông minh, Pull-to-refresh, Khóa màn hình tự ẩn & Chia sẻ nhanh):** Bổ sung lịch sử tìm kiếm 10 từ khóa (`PrefsStore`) + bảng gợi ý Từ khóa Hot (`HOT_KEYWORDS`) & Lịch sử Liquid Glass trong `MainActivity`; tích hợp `SwipeRefreshLayout` cho `HomeFragment` & `ScheduleFragment` (tự tắt trên TV); nâng cấp `btnUnlockScreen` tại `top|start` dùng VectorDrawable tự ẩn 3s khi khóa màn hình trong `PlayerActivity`; thêm `PlayerUtils.formatShareText` + nút Chia sẻ (`btnQuickShare`, `btnDetailShare`).
- **Phiên 48 (Khắc phục trùng phim/mất poster & tối ưu toàn app):** Bỏ trộn `INITIAL_DONGHUA_CATALOG` vào feed sống (`loadLiveDonghuaFeed`), thay toàn bộ link `phimimg.com` 404 bằng poster xác thực + vector `bg_poster_placeholder.xml`, hợp nhất khử trùng slug/tiêu đề & ưu tiên poster sống trong `MovieRepository`, khử trùng `ServerGroup`, đồng bộ `hPad` (`14/18/22dp`), kích thước card/hero/detail cho Mobile/PC/TV và xếp tập mới nhất trước trong Drawer của `PlayerActivity`.
- **Phiên 47 (Menu Cài Đặt Mobile Phân Nhóm):** Trên **Điện thoại (`MOBILE`)**, tab Cài đặt hiển thị giao diện danh sách phân nhóm 2 thẻ kính (`TRẢI NGHIỆM & PHÁT` + `HỆ THỐNG & DỮ LIỆU`) kèm ô icon bo góc `36dp`, vạch chia inset, mũi tên phải `›` và điều hướng 2 tầng (`isSettingsDetailOpen`, nút Back phần cứng & thanh `← <Tiêu đề>`); **Tablet/PC** và **TV** giữ nguyên thanh 5 pill ngang.
- **Phiên 46 (Đồng nhất Lịch sử, Thanh điều hướng & Hoàng Hôn Đêm Sâu):** Lưới `Lịch sử` dùng chung `MovieCardAdapter` giống `Yêu thích` (chạm phát tiếp ngay, nhấn giữ mở menu); thanh điều hướng kính đáy & TV Rail bọc `RippleDrawable`, `navFeatureIconDrawable` làm nổi bật 5 icon tính năng + `attachLiquidNavTapEffect`; chủ đề `DARK` thành **Hoàng Hôn Đêm Sâu** (`#040507`, vàng hoàng hôn dịu `#E2AE58`).
- **Phiên 45 (UI/UX, Thể loại & Tập mới nhất trước):** Chi tiết phim hiển thị tập mới nhất trước; bấm `Thể loại phim ▾` mở bảng Liquid Glass đồng bộ thời gian thực các chủ đề *Phim Hot, Phim Chiếu Rạp, Phim Lẻ, Phim Bộ, Phim Hoạt Hình, Phim Hành Động, Tình Cảm* + toàn bộ thể loại Web; Mobile 3 cột / Tablet-PC 4 cột / TV 5 cột; bỏ `Xanh Băng` (`AQUA`) giữ 3 tông màu (`PEARL`, `SAKURA`, `DARK`), bỏ `Hệ thống`, mặc định `52%` trong suốt / `32%` mờ / Hiệu ứng nền Bật.
- **Sửa văng app Xiaomi MiTV4-ANSM0 & OTA GitHub (Phiên 41–44):** Bật `coreLibraryDesugaringEnabled true` (`desugar_jdk_libs:2.1.4`), 17 Root CA (`Sectigo E46/R46`), `liteRender` Mali-450, xuất bản gói `HoatHinh3d.apk`, `AppUpdateChecker` trỏ `version.json` trên `main`.
- **Nền tảng Android 6.0.1 / MiTV4-ANSM0 & Player CloudStream (Phiên 31–40):** Sạch `NewApi`, `Ultra-Low RAM (<= 1800 MB)`, `CompatTls`, `GlideOkHttpUrlLoader`, Đồng bộ Email + LAN Sync UDP `8776`, Auto Clear Cache khi thoát, **Tiếp tục xem / Tập X** đúng phút/giây, **Bỏ qua Intro (`+85s`)** tự ẩn sau 5s, Storyboard Preview `seekThumbCard`, trình phát thuần HLS/MP4 khử PNG-TS, phụ đề rời `±0.5s`, Night Mode DRC.

Nhiệm vụ tiếp theo cho bạn:
[Điền yêu cầu cụ thể của phiên mới tại đây...]

Lưu ý bắt buộc khi kết thúc: tăng `versionCode`/`versionName` trong `android/app/build.gradle`, chạy `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` (KHÔNG chạy `assembleRelease`) + `.\gradlew.bat lintDebug`, cập nhật `PROJECT_MEMORY.md`, commit + push lên `https://github.com/long-1285/Hoathinh3D.git`, rồi chạy `powershell -ExecutionPolicy Bypass -File .\publish-ota.ps1 -SkipBuild -Changelog "..."` và đẩy `version.json` để luồng OTA nhận ngay bản mới nhất trên Git (tuyệt đối không lưu token lên Git hay mã nguồn).
```
