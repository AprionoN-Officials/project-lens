# Project Asisten (Project Lens) — v0.1.0

Asisten layar Android native yang ringan, cepat, dan memprioritaskan privasi serta kompatibilitas aplikasi perbankan. Terinspirasi dari pengalaman **Google Assistant generasi lama** (*Search Screen*, *Translate Screen*, dan *Select Text / Copy Text OCR*) yang bekerja langsung dari konten layar aktif **tanpa membuka kamera**.

---

## Fitur Utama

1. **Scan Screen & Cari dengan Google Lens**:
   - Menangkap layar aktif secara resmi (tanpa kamera).
   - Menampilkan overlay seleksi interaktif (`CropOverlayView`) dengan sudut yang dapat digeser/diubah ukurannya.
   - Mengirim potongan gambar ke **Google Lens** melalui resolusi `Intent.ACTION_SEND` (`image/png`) + `FileProvider` resmi jika tersedia di perangkat, atau otomatis menggunakan fallback resmi **Android Sharesheet (`Intent.createChooser`)** tanpa klaim palsu.
2. **Pilih Teks / Copy Text (OCR Lokal)**:
   - Diakses langsung dari dalam mode **Scan** (`[ Pilih Teks ]`) agar menu utama tetap ringkas.
   - Menggunakan **Google ML Kit Text Recognition v2** secara *on-device* (tanpa server pribadi).
   - Mendukung ketuk 1 kata, ketuk ganda/tahan 1 baris, seret area (*marquee*) multi-baris, serta tombol **Select All** dan **Copy** ke clipboard dengan tetap menjaga urutan baca dan pemisahan baris.
3. **Terjemahkan Layar (Auto Language ID + On-Device Translation)**:
   - Mendeteksi bahasa sumber secara otomatis menggunakan **ML Kit Language Identification**.
   - Menerjemahkan ke **Bahasa Indonesia (`id`)** secara default, dengan pilihan bahasa tujuan lain (`en`, `ja`, `ko`, `zh`, `ar`, `de`, `es`, `fr`).
   - Meminta persetujuan pengguna beserta informasi ukuran unduhan (**~30 MB**) apabila model bahasa offline belum terunduh di perangkat.
   - Menampilkan terjemahan baik di **atas koordinat teks asli (`Overlay Koordinat`)** maupun pada **panel teks yang mudah dibaca** lengkap dengan tombol **Salin Terjemahan**.
4. **Tiga Metode Aktivasi Resmi**:
   - **Metode A — Default Digital Assistant (`VoiceInteractionService` & `RoleManager.ROLE_ASSISTANT`)**: Mendukung pemicu asisten sistem (termasuk gesture usapan diagonal dari sudut bawah layar jika didukung ROM perangkat) dan menerima tangkapan layar langsung dari OS (`onHandleScreenshot`) tanpa dialog berulang.
   - **Metode B — Accessibility Shortcut (`LensAccessibilityService`)**: Pintasan manual yang menggunakan `AccessibilityService.takeScreenshot()` pada Android 11+ (API 30+). Dikonfigurasi dengan `accessibilityEventTypes=""` dan `canRetrieveWindowContent="false"` sehingga **tidak pernah** memantau layar atau membaca input sensitif.
   - **Metode C — Quick Settings Tile (`LensTileService`)**: Tombol **CircleToSearch** di panel Quick Settings yang kompatibel dengan Android 10 hingga Android 15.

---

## Arsitektur & Struktur Proyek

```text
ProjectLens/
├── TECHNICAL_FEASIBILITY_AUDIT.md   # Audit kelayakan teknis API Android 10–15 (Phase 1)
├── TEST_AND_BUILD_REPORT.md         # Laporan pengujian nyata, metrik JVM, dan status build APK (Phase 5)
├── scripts/
│   └── build_and_verify.sh          # Skrip verifikasi otomatis JVM (OpenJDK 17) & pengecekan SDK Android
└── app/
    ├── build.gradle.kts             # Konfigurasi build Android (minSdk 29, targetSdk 34, Kotlin 17)
    ├── proguard-rules.pro           # Aturan penyusutan ukuran APK (R8/ProGuard)
    ├── jvm-verification/
    │   └── VerifyCoreLogic.java     # Suite pengujian & benchmark nyata pada OpenJDK 17
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/id/projectasisten/lens/
        │   │   ├── ProjectLensApp.kt
        │   │   ├── core/
        │   │   │   ├── BitmapSecurityInspector.kt
        │   │   │   ├── CropMath.kt
        │   │   │   ├── OcrLayoutAssembler.kt
        │   │   │   ├── ScreenshotRepository.kt
        │   │   │   └── SensitiveAppGuard.kt
        │   │   ├── data/
        │   │   │   └── AppPreferences.kt
        │   │   ├── feature/
        │   │   │   ├── ocr/OcrEngine.kt
        │   │   │   ├── search/LensImageSearchHelper.kt
        │   │   │   └── translate/TranslationEngine.kt
        │   │   ├── service/
        │   │   │   ├── AssistantInteractionService.kt
        │   │   │   ├── AssistantSession.kt
        │   │   │   ├── AssistantSessionService.kt
        │   │   │   ├── LensAccessibilityService.kt
        │   │   │   ├── LensTileService.kt
        │   │   │   └── MediaProjectionCaptureService.kt
        │   │   └── ui/
        │   │       ├── overlay/
        │   │       │   ├── AssistantOverlayActivity.kt
        │   │       │   ├── CropOverlayView.kt
        │   │       │   └── OcrOverlayView.kt
        │   │       └── settings/
        │   │           └── MainActivity.kt
        │   └── res/                 # Layout Material 3, Vector Drawable, XML Service Config, Strings
        └── test/
            └── java/id/projectasisten/lens/core/
                └── CoreLogicTest.kt # Unit test JUnit 4
```

---

## Cara Membangun APK Debug

Pada mesin pengembangan yang memiliki **Android SDK (API 34)** dan akses ke repositori Maven Google (`dl.google.com` / `maven.google.com`):

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
```

Hasil APK yang siap diinstal akan berada di:
`app/build/outputs/apk/debug/app-debug.apk`
