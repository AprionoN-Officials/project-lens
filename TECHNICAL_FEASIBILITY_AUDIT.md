# Phase 1 — Technical Feasibility Audit (Project Asisten / Project Lens v0.1)

Dokumen ini menyajikan hasil pemeriksaan kelayakan teknis terhadap API resmi Android (API 29 / Android 10 hingga API 35 / Android 15) sebelum implementasi aplikasi **Project Asisten (`id.projectasisten.lens`)**.

---

## 1. Ringkasan Klasifikasi Fitur

| Komponen / Fitur | Status Teknis | Mekanisme Resmi yang Digunakan | Catatan / Batasan |
| :--- | :--- | :--- | :--- |
| **Default Digital Assistant (Metode A)** | **Didukung Resmi (Bergantung ROM untuk Gesture)** | `VoiceInteractionService`, `VoiceInteractionSessionService`, `RoleManager.ROLE_ASSISTANT`, `Settings.ACTION_VOICE_INPUT_SETTINGS` | Menerima tangkapan layar langsung dari sistem melalui `onHandleScreenshot(Bitmap?)` tanpa dialog `MediaProjection` berulang. Gesture sudut layar bawah / tekan tombol daya mengikuti kebijakan ROM. |
| **Accessibility Shortcut (Metode B)** | **Didukung Resmi** | `AccessibilityService` dengan `flagRequestAccessibilityButton` dan `canTakeScreenshot="true"` (API 30+) | `accessibilityEventTypes=""` dan `canRetrieveWindowContent="false"` agar **tidak pernah** membaca isi layar di latar belakang atau memicu deteksi pembacaan teks pada aplikasi perbankan. |
| **Quick Settings Tile (Metode C)** | **Didukung Resmi (100% Universal)** | `android.service.quicksettings.TileService` | Kompatibel penuh dengan API 29–35. Pada API 34+ menggunakan `startActivityAndCollapse(PendingIntent)`. |
| **Screen Capture / Screenshot** | **Didukung Resmi (3 Jalur Berlapis)** | 1) `VoiceInteractionSession.onHandleScreenshot`<br>2) `AccessibilityService.takeScreenshot` (API 30+)<br>3) `MediaProjectionManager` (Fallback) | Jalur 1 & 2 tidak memerlukan persetujuan ulang setiap sesi. Jalur 3 menggunakan one-shot Foreground Service (`foregroundServiceType="mediaProjection"`) yang langsung dihentikan setelah 1 frame. |
| **Google Lens Image Search** | **Perlu Resolusi Intent / Sharesheet Fallback** | `PackageManager.queryIntentActivities` (`ACTION_SEND`, `image/png`) + `FileProvider` | Google **tidak** menyediakan SDK publik pihak ketiga untuk mengirim gambar diam-diam ke Google Lens. Aplikasi mengecek ketersediaan aktivitas share `com.google.ar.lens` / `com.google.android.googlequicksearchbox` secara resmi, dan menyediakan fallback **Android Sharesheet** (`Intent.createChooser`). |
| **OCR & Pilih Teks** | **Didukung Resmi (On-Device)** | Google ML Kit Text Recognition v2 (`com.google.mlkit:text-recognition:16.0.1`) | Berjalan 100% lokal di perangkat tanpa internet. Mendukung seleksi per kata (`Element`), per baris (`Line`), multi-baris, dan seluruh teks. |
| **Identifikasi Bahasa & Terjemahan** | **Didukung Resmi (On-Device)** | Google ML Kit Language ID (`17.0.6`) & On-Device Translation (`17.0.3`) | Model bahasa (~30 MB per bahasa) diunduh hanya setelah pengguna menyetujui dialog konfirmasi ukuran unduhan. Mendukung overlay terjemahan di atas koordinat OCR maupun tampilan panel. |
| **Kompatibilitas Perbankan & `FLAG_SECURE`** | **Prioritas Keamanan Tertinggi** | Tanpa `SYSTEM_ALERT_WINDOW`, tanpa pembacaan event Accessibility, deteksi `FLAG_SECURE` otomatis | Tidak mendeklarasikan izin `SYSTEM_ALERT_WINDOW` agar tidak diblokir aplikasi bank yang merazia izin overlay. Menghormati `FLAG_SECURE` secara penuh. |

---

## 2. Audit Mendalam Per Area API Android

### 2.1 `RoleManager` dan `VoiceInteractionService`
- **Kelayakan**: Android menyediakan kerangka kerja asisten digital resmi melalui `android.service.voice.VoiceInteractionService` dan `VoiceInteractionSession`.
- **Cara Kerja**:
  - Ketika pengguna memilih **Project Asisten** sebagai *Default Digital Assistant app* (melalui `RoleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)` atau `Settings.ACTION_VOICE_INPUT_SETTINGS`), sistem Android mengikat `AssistantInteractionService`.
  - Saat pengguna memicu asisten (usapan diagonal dari sudut bawah layar pada navigasi gesture standar, tahan tombol Home pada navigasi 3-tombol, atau tahan tombol Power tergantung pengaturan OEM/ROM), Android memanggil `AssistantSession.onShow()` dan mengirimkan screenshot layar saat ini ke `AssistantSession.onHandleScreenshot(Bitmap?)`.
- **Batasan**:
  - Aplikasi pihak ketiga **tidak dapat** mengambil alih gesture navigasi sistem secara paksa di luar mekanisme *Default Digital Assistant*.
  - Beberapa ROM kustom (mis. varian tertentu dari MIUI/HyperOS, ColorOS, atau OneUI) mungkin membatasi gesture sudut bawah hanya untuk asisten bawaan pabrik, sehingga pengguna tetap dapat menggunakan **Quick Settings Tile** atau **Accessibility Shortcut**.

### 2.2 `MediaProjection` dan Persetujuan Screen Capture
- **Kelayakan**: Digunakan sebagai jalur tangkapan layar universal apabila aplikasi dipicu dari Quick Settings Tile (atau pada Android 10 / API 29 di mana `AccessibilityService.takeScreenshot` belum tersedia).
- **Perubahan Android 14 (API 34) & Android 15 (API 35)**:
  - Wajib mendeklarasikan izin `FOREGROUND_SERVICE` dan `FOREGROUND_SERVICE_MEDIA_PROJECTION`.
  - Service wajib memanggil `startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` **hanya setelah** pengguna memberikan persetujuan pada dialog `MediaProjectionManager.createScreenCaptureIntent()`.
  - Android 14+ melarang penggunaan ulang token `MediaProjection` tanpa persetujuan baru. Oleh karena itu, `MediaProjectionCaptureService` dirancang *strictly one-shot*: menangkap **1 frame** dari `ImageReader`, lalu segera memanggil `VirtualDisplay.release()`, `ImageReader.close()`, `MediaProjection.stop()`, dan `stopSelf()`.

### 2.3 `AccessibilityService`
- **Kelayakan**: Digunakan untuk menyediakan **Tombol / Shortcut Aksesibilitas** resmi (`flagRequestAccessibilityButton`) dan tangkapan layar langsung tanpa dialog berulang pada Android 11+ (`API 30+`) menggunakan `AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)`.
- **Mitigasi Konflik Aplikasi Perbankan**:
  - Banyak aplikasi layanan aksesibilitas gagal karena menyetel `android:accessibilityEventTypes="typeAllMask"` dan `android:canRetrieveWindowContent="true"`. Hal tersebut memicu peringatan keamanan pada aplikasi m-banking/dompet digital.
  - Pada **Project Asisten**, konfigurasi `accessibility_service_config.xml` secara eksplisit menyetel:
    - `android:accessibilityEventTypes=""` (kosong — tidak menerima event perubahan layar, ketikan, atau fokus input).
    - `android:canRetrieveWindowContent="false"` (tidak dapat membaca pohon View, PIN, password, atau OTP).
    - `android:canTakeScreenshot="true"` (hanya menangkap bitmap piksel layar saat tombol shortcut ditekan pengguna, dan tetap tunduk pada `FLAG_SECURE`).

### 2.4 `SYSTEM_ALERT_WINDOW` (Mengapa Sengaja Tidak Digunakan)
- **Hasil Audit**: Izin `android.permission.SYSTEM_ALERT_WINDOW` (`Display over other apps`) adalah pemicu nomor satu pemblokiran oleh aplikasi perbankan dan dompet digital di Indonesia (yang memeriksa `Settings.canDrawOverlays(context)`).
- **Keputusan Arsitektur**: **Project Asisten TIDAK meminta izin `SYSTEM_ALERT_WINDOW`.**
  - Sebagai gantinya, panel popup bawah (*floating bottom panel*) dirender menggunakan Activity transparan (`AssistantOverlayActivity` dengan `windowIsTranslucent="true"`, `windowBackground="@android:color/transparent"`, dan `Theme.Material3.DayNight.NoActionBar`).
  - Saat aplikasi dibuka dari Asisten Default, Quick Settings Tile, atau Shortcut Aksesibilitas, konten aplikasi sebelumnya tetap terlihat di belakang panel bawah, dan tidak ada izin overlay permanen yang mengganggu aplikasi perbankan.

### 2.5 Quick Settings Tile (`TileService`)
- **Kelayakan**: Didukung penuh sejak API 24 hingga API 35.
- **Penanganan Android 14+ (API 34+)**:
  - Pada API 34+, `TileService.startActivityAndCollapse(Intent)` telah usang (*deprecated*) dan melempar `UnsupportedOperationException` jika dipanggil dari aplikasi yang menargetkan SDK 34+.
  - `LensTileService` menggunakan percabangan versi SDK: `startActivityAndCollapse(PendingIntent)` pada API 34+ dan `startActivityAndCollapse(Intent)` pada API 29–33.

### 2.6 ML Kit OCR (`text-recognition`) dan Translation (`translate` + `language-id`)
- **Kelayakan**:
  - `com.google.mlkit:text-recognition:16.0.1` mengekstrak teks Latin (termasuk Bahasa Indonesia dan Bahasa Inggris) secara *on-device* beserta hierarki `TextBlock` -> `Line` -> `Element` dan koordinat `Rect`.
  - `com.google.mlkit:language-id:17.0.6` mendeteksi kode bahasa BCP-47 secara otomatis di perangkat.
  - `com.google.mlkit:translate:17.0.3` menerjemahkan teks secara *on-device* ke Bahasa Indonesia (`id`, default) atau bahasa tujuan pilihan pengguna (`en`, `ja`, `ko`, `zh`, `ar`, `de`, `es`, `fr`).
  - Sebelum mengunduh model terjemahan yang belum tersedia di cache lokal, aplikasi memeriksa status model via `RemoteModelManager.isModelDownloaded()` dan menampilkan dialog konfirmasi ukuran (~30 MB) kepada pengguna.

### 2.7 Android Sharesheet, `FileProvider`, dan Integrasi Google Lens
- **Hasil Audit Google Lens**:
  - Google **tidak** merilis API publik atau kontrak Intent resmi yang terdokumentasi bagi aplikasi pihak ketiga untuk menginjeksi gambar langsung ke mesin pencarian Google Lens tanpa interaksi pengguna.
  - Namun, aplikasi Google Lens (`com.google.ar.lens`) dan Google App (`com.google.android.googlequicksearchbox`) mendaftarkan `<intent-filter>` untuk `android.intent.action.SEND` dengan MIME type `image/*`.
- **Pendekatan Implementasi Jujur & Transparan**:
  1. Gambar hasil crop disimpan sementara di direktori internal `cacheDir/shared_crops/crop_latest.png` (langsung ditimpa/dibersihkan saat sesi ditutup) dan diekspos melalui `androidx.core.content.FileProvider` dengan `FLAG_GRANT_READ_URI_PERMISSION`.
  2. `LensImageSearchHelper` menggunakan `PackageManager.queryIntentActivities()` (dengan deklarasi `<queries>` resmi di `AndroidManifest.xml` sesuai aturan Package Visibility Android 11+) untuk mendeteksi apakah penerima share Google Lens (`com.google.ar.lens` atau aktivitas Lens di `com.google.android.googlequicksearchbox`) terinstal di perangkat.
  3. Jika terdeteksi, tombol **"Cari dengan Google Lens"** mengarahkan `ACTION_SEND` langsung ke komponen penerima Google Lens tersebut.
  4. Jika tidak terdeteksi (atau gagal dibuka), aplikasi **tidak mengklaim integrasi palsu**, melainkan memberi tahu pengguna secara jelas dan langsung membuka **Android Sharesheet (`Intent.createChooser`)** agar gambar hasil seleksi dapat dibagikan ke aplikasi pencarian gambar atau browser apa pun yang kompatibel.

### 2.8 Penanganan `FLAG_SECURE` dan Layar Sensitif
- **Jalur `VoiceInteractionSession`**: Sistem Android mengirimkan `bitmap = null` ke `onHandleScreenshot(null)` jika layar aktif menggunakan `FLAG_SECURE`.
- **Jalur `AccessibilityService.takeScreenshot` (API 30+)**: Sistem mengembalikan kode error `AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (`3`).
- **Jalur `MediaProjection`**: Pada Android 10–15, jendela dengan `FLAG_SECURE` menghasilkan frame hitam pekat pada `VirtualDisplay`. Kelas `BitmapSecurityInspector` melakukan pemeriksaan sampling piksel cepat (`isLikelyBlankOrSecure`) untuk mendeteksi tangkapan layar hitam akibat `FLAG_SECURE`.
- **Deteksi Paket Sensitif (`SensitiveAppGuard`)**: Memeriksa nama paket terdepan (apabila diberikan oleh konteks asisten) terhadap daftar aplikasi sensitif/perbankan populer dan menolak memproses layar jika terdeteksi dilindungi.
- Pada ketiga kondisi di atas, aplikasi langsung membatalkan pemrosesan dan menampilkan pesan yang jelas kepada pengguna bahwa layar dilindungi oleh sistem (`FLAG_SECURE`).
