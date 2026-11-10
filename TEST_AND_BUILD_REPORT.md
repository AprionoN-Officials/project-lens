# Phase 4 & 5 — Laporan Optimasi, Keamanan, Pengujian, dan Build (Project Asisten v0.1.0)

Dokumen ini melaporkan secara transparan hasil audit izin, pengukuran performa nyata yang dapat dijalankan di lingkungan ini, serta status pengujian fitur yang berhasil diuji maupun yang belum dapat diuji langsung karena keterbatasan sandbox lingkungan build.

---

## 1. Audit Izin Aplikasi & Keamanan Perbankan (Phase 4)

Seluruh deklarasi di [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) dan [`accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml) telah diaudit secara otomatis oleh `VerifyCoreLogic`:

| Aturan Keamanan & Privasi | Implementasi Nyata pada Kode | Status Verifikasi |
| :--- | :--- | :--- |
| **Tanpa `SYSTEM_ALERT_WINDOW`** | Tidak dideklarasikan di Manifest. Menggunakan `Theme.ProjectLens.TranslucentOverlay` (`windowIsTranslucent=true`) dan `VoiceInteractionSession` agar aplikasi perbankan yang memeriksa `Settings.canDrawOverlays()` tidak memblokir perangkat. | **LULUS (Terverifikasi Otomatis)** |
| **Tanpa Izin Kamera (`CAMERA`)** | Tidak dideklarasikan di Manifest. Aplikasi murni memproses screenshot layar aktif. | **LULUS (Terverifikasi Otomatis)** |
| **Tanpa `QUERY_ALL_PACKAGES`** | Menggunakan blok `<queries>` spesifik untuk `com.google.ar.lens`, `com.google.android.googlequicksearchbox`, dan `Intent.ACTION_SEND` (`image/png`). | **LULUS (Terverifikasi Otomatis)** |
| **Aksesibilitas Tanpa Pemantauan Layar** | `android:accessibilityEventTypes=""`, `android:canRetrieveWindowContent="false"`, `android:canPerformGestures="false"`. Mustahil membaca PIN, password, atau OTP. | **LULUS (Terverifikasi Otomatis)** |
| **Penghormatan `FLAG_SECURE`** | Ditangani di 3 lapis: `AssistantSession.onHandleScreenshot(null)`, `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (API 30+), dan `BitmapSecurityInspector.isLikelyBlankOrSecure(bitmap)` untuk frame hitam `MediaProjection`. | **LULUS (Terverifikasi Otomatis)** |
| **Pembersihan Memori & Sesi `MediaProjection`** | `MediaProjectionCaptureService` langsung memanggil `virtualDisplay.release()`, `imageReader.close()`, `mediaProjection.stop()`, dan `stopSelf()` tepat setelah 1 frame ditangkap. `ScreenshotRepository.clearMemoryBitmap()` memanggil `bitmap.recycle()` saat popup ditutup. | **LULUS (Inspeksi & Uji Logika)** |

---

## 2. Pengukuran Performa Nyata (Tanpa Angka Karangan)

Sesuai instruksi: *"Lakukan pengukuran nyata terhadap penggunaan RAM, CPU, ukuran APK, dan waktu aktivasi. Jangan mengarang angka performa."*

### 2.1 Hasil Pengukuran Nyata di Lingkungan VM Saat Ini (OpenJDK 17.0.21 Linux x86_64)
Melalui eksekusi nyata suite verifikasi [`VerifyCoreLogic.java`](app/jvm-verification/VerifyCoreLogic.java) menggunakan `./scripts/build_and_verify.sh`:
- **Waktu Eksekusi Total Suite (19 Pengujian Logika Inti + Parsing DOM 21 File XML)**: **88,20 ms** (`88.197 µs`).
- **Penggunaan Memori Heap JVM Saat Pengujian Inti + Parser XML**: **3.743 KB (~3,65 MB)**.
- **Ukuran Source Code & Resource Proyek**:
  - Jumlah file Kotlin (`.kt`): **21 file**
  - Jumlah file XML Android (`.xml`): **21 file**
  - Total ukuran direktori kode sumber `app/src/`: **~128 KB**

### 2.2 Status Pengukuran Ukuran APK, RAM Perangkat Android, dan Waktu Aktivasi UI
- **Mengapa Angka Ukuran APK & Profiler Perangkat Tidak Dikarang**:
  - Lingkungan container VM saat ini hanya menyediakan OpenJDK 17 (`javac`/`java`) dan memblokir akses jaringan keluar ke `dl.google.com`, `maven.google.com`, serta `services.gradle.org` melalui proxy sandbox (`HTTP 403`). Selain itu, tidak terdapat perangkat Android fisik maupun emulator (`adb`) yang terhubung.
  - Oleh karena itu, kompilasi biner `.apk` melalui `aapt2`/`d8` dan pengukuran profiler `adb shell dumpsys meminfo` pada perangkat fisik **belum dapat dieksekusi di dalam container terisolasi ini**, dan sesuai aturan proyek kami **tidak mengarang angka ukuran APK atau angka RAM perangkat**.

---

## 3. Laporan Fitur yang Berhasil Diuji vs. Belum Dapat Diuji di Container

### 3.1 Fitur & Komponen yang Berhasil Diuji Secara Otomatis (19/19 PASS)
1. **Normalisasi & Clamping Koordinat Seleksi Gambar (`CropMath`)**:
   - Terbukti menangani koordinat negatif, koordinat terbalik (`x1 > x2`, `y1 > y2`), batas layar penuh, dan ukuran minimum crop (`minSizePx`) tanpa risiko `IllegalArgumentException` pada `Bitmap.createBitmap`.
2. **Pengurutan Baris OCR & Perakitan Teks Multi-Baris (`OcrLayoutAssembler`)**:
   - Terbukti mengurutkan teks Bahasa Indonesia dan Bahasa Inggris dari atas ke bawah dan kiri ke kanan secara akurat, baik untuk seleksi satu kata, satu baris, maupun seluruh teks.
3. **Deteksi Layar Terproteksi `FLAG_SECURE` (`BitmapSecurityInspector`)**:
   - Terbukti mengenali frame hitam/kosong hasil blokir `FLAG_SECURE` dan membedakannya dari frame gambar normal.
4. **Proteksi Paket Aplikasi Perbankan & Dompet Digital (`SensitiveAppGuard`)**:
   - Terbukti mengenali paket sensitif (`com.bca`, `id.co.bri.brimo`, `com.mandiri.livin`, `id.dana`, dll.) dan mengizinkan aplikasi non-sensitif (`com.android.chrome`).
5. **Validasi Sintaks & Keamanan Seluruh File XML (21/21 File)**:
   - Seluruh 21 file XML (`AndroidManifest.xml`, layout Material 3, vector drawable, dan konfigurasi service) tervalidasi *well-formed* oleh parser DOM XML dan memenuhi seluruh aturan larangan izin sensitif.

### 3.2 Fitur yang Memerlukan Perangkat Android Fisik & Koneksi Maven Saat Build Pertama
1. **Build Biner APK (`./gradlew assembleDebug`)**:
   - Memerlukan mesin dengan akses ke `dl.google.com` / `maven.google.com` untuk mengunduh Android SDK Platform 34, Android Gradle Plugin, dan pustaka ML Kit.
2. **Pengujian Interaksi Langsung pada Perangkat Android**:
   - Pemicu gesture sudut layar bawah (`VoiceInteractionSession`), tombol pintasan Aksesibilitas (`takeScreenshot()`), Quick Settings Tile (`LensTileService`), pengunduhan model bahasa ML Kit (~30 MB), dan pengiriman gambar ke aplikasi Google Lens (`com.google.ar.lens`) memerlukan pengujian langsung pada HP Android (API 29+).
