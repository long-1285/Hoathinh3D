# HoatHinh3D Universal — Bộ Nhớ & Quy Tắc Thường Trực Cho AI Agent

> File này nằm ở thư mục gốc dự án và được Antigravity **tự động kích hoạt (`always_on`)** trên mọi thiết bị sau khi `git clone https://github.com/long-1285/Hoathinh3D.git`.

---

## 1. Bản Sắc & Phong Cách Làm Việc

- Giao tiếp bằng **tiếng Việt** ngắn gọn, thẳng thắn, rõ ràng; luôn gắn link `file:///...` tới các file đã đọc/sửa.
- **Trung thực tuyệt đối về bằng chứng:** Chỉ báo cáo tên symbol, đường dẫn, kết quả test, số lượng pass/fail, dung lượng APK và kết quả build sau khi trực tiếp chạy và kiểm chứng ở phiên hiện tại. Không suy đoán kết quả chạy thật khi chưa đo.
- **Sửa tận gốc:** Khi xử lý lỗi, luôn kiểm tra ranh giới tạo dữ liệu, các màn hình/adapter liên quan và dọn code thừa thay vì vá tạm. Bảo toàn các tính năng và quyết định UI/UX người dùng đã chốt qua các phiên trước.

---

## 2. Quy Trình Bắt Buộc Mỗi Phiên Làm Việc

1. **Đầu phiên — Đối chiếu Git & Đọc Memory:**
   - Kho Git chính thức: `https://github.com/long-1285/Hoathinh3D.git` (nhánh `main`).
   - So sánh đối chiếu mã nguồn trên máy với `origin/main` (`git fetch origin`); nếu trên Git mới hơn thì kéo/clone bản mới nhất về máy trước khi sửa code.
   - Đọc kỹ toàn bộ `PROJECT_MEMORY.md` để nắm kiến trúc Native, quy chuẩn build và lịch sử thay đổi tới phiên gần nhất.
2. **An toàn bí mật:**
   - **TUYỆT ĐỐI KHÔNG** lưu GitHub token, mật khẩu, keystore hoặc thông tin xác thực vào mã nguồn, `.git/config`, `GEMINI.md` hay `PROJECT_MEMORY.md`.
3. **Cuối phiên — Kiểm thử & Build Debug APK:**
   - Chỉ chạy `powershell -ExecutionPolicy Bypass -File .\build-apk.ps1` (tự động dò OpenJDK 21 & Android SDK, chạy `testDebugUnitTest` + `assembleDebug`, xuất ra `HoatHinh3D-Universal-debug.apk`).
   - **KHÔNG** chạy `assembleRelease` / không tạo Release R8 APK.
   - Khi chạm tới API Android hoặc Scraper, chạy thêm `.\gradlew.bat lintDebug` để bảo đảm **0 lỗi `NewApi`** cho `minSdk 23`.
4. **Chốt phiên — Cập nhật Memory & Push Git:**
   - Cập nhật `PROJECT_MEMORY.md` (mục 5 thêm phiên mới lên đầu, mục 6 Tổng kết bàn giao, mục 7 Prompt chuyển giao).
   - Commit với thông điệp phiên bản rõ ràng (VD: `fix(v41): ...`) và push toàn bộ mã nguồn mới nhất lên `https://github.com/long-1285/Hoathinh3D.git`.

---

## 3. Ràng Buộc Kỹ Thuật Cốt Lõi (Không Phá Vỡ Quyết Định Đã Chốt)

- **Stack chính thức:** 100% Android Native trong `android/` (Kotlin `2.0.21` + XML Views + ViewBinding + Media3 ExoPlayer `1.5.1` + OkHttp `4.12.0` + Jsoup `1.18.3` + Glide `4.16.0`, `com.hoathinh3d.donghua`, `minSdk 23`, `targetSdk 35`, hỗ trợ Android TV `LEANBACK_LAUNCHER`). Thư mục `src/` React/Vite chỉ là tài liệu tham chiếu.
- **2 Nguồn chính thức & Tìm kiếm:** Chỉ giữ `YanHH3DNow` (`yanhh3d.now`, mặc định) và `YanHH3D` (`yanhh3d.ee`) + nguồn `+Web` do người dùng thêm; đã bỏ `NguonC` khỏi UI; ưu tiên mặc định **Thuyết Minh**; thanh thể loại Trang chủ bắt đầu bằng **`Thể loại phim`** và đồng bộ thời gian thực từ nguồn Web.
- **Trình phát thuần Native:** Đã xóa 100% chế độ WebView `Embed` khỏi `PlayerActivity`; chỉ phát HLS/MP4 qua Media3 ExoPlayer + `SanitizingTsDataSource` khử PNG-TS; giữ giao diện CloudStream APK v4.9.1, Seek 0ms (`CLOSEST_SYNC`), `btnSkipIntro` tự ẩn sau 5s, Storyboard Preview (`seekThumbCard`), tiếp tục xem đúng phút/giây.
- **Cài đặt & Giao diện Liquid Glass iOS 27:** 5 phân mục (`1. Trình phát tích hợp` → `5. Tiện ích mở rộng`, không có mục `Tài khoản`, không có nút dọn cache trong mục `1.`, không có nút `Đồng bộ Cloud` ở tiêu đề), Header bỏ chữ `Nguồn phim`, chọn nguồn kính trong suốt, Auto Update có kiểm tra `SHA-256`, Auto Clear Cache khi thoát, LAN Sync UDP `8776` & Đồng bộ Email.
- **Tương thích Android 6.0.1 / Xiaomi MiTV4-ANSM0 (`armeabi-v7a` 32-bit, `1.6 GB` RAM, `5.0 GB` bộ nhớ trong):**
  - Không dùng API Java/Android `>= 24` thiếu guard (như `java.util.Base64`, `Map.putIfAbsent`, `android:drawableStart` trỏ VectorDrawable trên `<Button>`).
  - Giữ `CompatTls` (15 Root CA hiện đại `res/raw/modern_roots.pem` + TLS 1.2 + `HTTP/1.1` trên API 23), `GlideOkHttpUrlLoader`, ngưỡng `isUltraLowRamDevice` (`<= 1800 MB`), `RGB_565`, `DefaultRenderersFactory.setEnableDecoderFallback(true)`.
