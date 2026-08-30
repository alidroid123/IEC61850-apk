# IEC61850 Utility (ComtradeDownloader)

**Toolkit lapangan untuk engineer proteksi** — mengunduh & menganalisis rekaman gangguan
COMTRADE, menjelajahi struktur data IED secara langsung, dan memantau pengukuran/alarm dari
banyak gardu induk sekaligus, semuanya lewat protokol **IEC 61850 MMS**.

developed by **alidev (Mahmud Sulaiman A)** — package `com.alidev.dfrtools`

---

## 1. Cara Membuka Project

1. Install **Android Studio** versi terbaru (Koala/2024.1 ke atas direkomendasikan), lengkap
   dengan **NDK (Side by side)** dan **CMake** dari SDK Manager → tab **SDK Tools** (aplikasi
   ini punya modul native, lihat bagian 5).
2. Pilih **File > Open**, arahkan ke folder root project ini (folder yang berisi
   `settings.gradle`).
3. Tunggu Gradle sync + CMake native build pertama selesai — butuh koneksi internet untuk
   mengunduh Gradle 8.6, Android Gradle Plugin, dependency AndroidX/Material/Firebase, dan
   untuk CMake mem-build `libiec61850` + JNI bridge menjadi `libdfr_jni.so`.
4. Jalankan ke emulator/device lewat tombol **Run ▶**.

Requirement: `compileSdk 34`, `minSdk 24` (Android 7.0+), `targetSdk 34`, Java 8 source/target.

---

## 2. Fitur Utama

| Fitur | Ringkasan |
|---|---|
| **Unduh COMTRADE** | Sambung ke relay lewat MMS, deteksi vendor otomatis, mode Bulk/Single/Deep Scan. |
| **Viewer DFR** | Plot channel analog & digital, kursor T1/T2, ekspor grafik/laporan PDF. |
| **Riwayat Unduhan** | Kelola file COMTRADE yang sudah diunduh, per folder. |
| **Database Relay** | Data gardu induk/bay/IP/merk/tipe per relay, ping satuan & massal, impor/ekspor CSV. |
| **IED Explorer** | Jelajahi model data IEC 61850 (LD/LN/DO/DA) sebuah IED secara langsung. |
| **Monitoring IED** | Polling titik pantau dari banyak IED, ambang alarm, bulk edit, refresh per grup. |
| **Template Relay** | Set titik pantau yang bisa dipakai ulang per tipe/merk relay. |
| **Backup & Pemulihan** | Ekspor/impor seluruh device + titik pantau + template jadi satu file JSON. |
| **Notifikasi** | Ikon lonceng di Home; mencatat riwayat update (lihat bagian 6). |
| **Tema & Bahasa** | 5 skema warna (termasuk tema Abstract multi-warna, rotasi per layar), Terang/Gelap, Indonesia/Inggris — default Indonesia. |

---

## 3. Struktur Kode

```
app/src/main/java/com/alidev/dfrtools/
 ├─ dfr/                     <- seluruh Activity utama & logic domain
 │   ├─ HomeActivity, DfrDownloadActivity, DfrViewerActivity
 │   ├─ MmsExplorerActivity, IEDMonitoringActivity, RelayTemplateEditActivity
 │   ├─ DeviceListActivity, InternalFileManagerActivity, SettingsActivity
 │   ├─ ThemeManager                 <- 5 tema, rotasi warna per-Activity untuk tema Abstract
 │   ├─ Iec61850DfrClient            <- wrapper Java atas native (BLOCKING, panggil dari background thread)
 │   └─ ComtradeSmartSearch          <- logika pencarian folder COMTRADE per-vendor
 ├─ update/                  <- update otomatis + notifikasi in-app
 │   ├─ UpdateChecker, UpdateFlow, UpdateDownloadService, UpdatePrefs
 │   ├─ AppFcmService                <- terima push FCM topic "app_updates"
 │   ├─ AppNotifications             <- feed notifikasi in-app (lonceng di Home)
 │   └─ NotificationActivity
 └─ utils/                   <- helper lintas Activity (LocaleHelper, ConfigHelper, dll)

app/src/main/cpp/            <- native layer (lihat bagian 5)
.github/workflows/           <- notify-release.yml (push FCM otomatis saat release)
.github/scripts/             <- send-fcm-notification.js
```

---

## 4. Alur Rilis Otomatis

`gradle assembleRelease` men-trigger task Gradle `publishReleaseToGit` (`app/build.gradle`)
yang: menaikkan versi (`version.properties`), commit + tag + push, lalu `gh release create`
mem-publish APK ke GitHub Releases.

**Menyertakan changelog di rilis:** kalau ada file `RELEASE_NOTES.md` di root project (berisi
ringkasan perbaikan/fitur rilis ini) saat `assembleRelease` dijalankan, isinya dipakai sebagai
release notes GitHub — lalu file itu dihapus otomatis setelah dipakai. Kalau tidak ada, release
notes-nya generik. Changelog itu kemudian mengalir otomatis ke tiga tempat:
- Body notifikasi push FCM (`.github/scripts/send-fcm-notification.js`, dipotong ~300 karakter).
- Bagian "Yang baru" di dialog update dalam aplikasi (`UpdateChecker` membaca field `body` dari
  GitHub Releases API).
- Entri di layar **Notifikasi** in-app (ikon lonceng di Home) — baik yang datang dari push FCM
  maupun dari pengecekan update di aplikasi (silent check di Home, atau tombol "Cek Update" di
  About), keduanya dedupe berdasarkan nomor versi.

Push FCM sendiri dipicu oleh **event GitHub "release published"** lewat
`.github/workflows/notify-release.yml` — jadi tetap terkirim meskipun rilis dibuat dari mesin
lain, `gh release create` manual, atau lewat web UI GitHub, bukan cuma dari build Gradle di atas.

---

## 5. Fitur Native (libiec61850 / IEC 61850 MMS)

### 5.1 Asal & Lisensi

Native layer memakai **libiec61850** (https://github.com/mz-automation/libiec61850, versi
1.6, source lengkap divendor di `app/src/main/cpp/libiec61850/`). Library ini **GPLv3**
(lihat `app/src/main/cpp/libiec61850/COPYING`) — copyleft bahkan untuk static linking. Kalau
APK hasil build ini didistribusikan ke pihak di luar tim Anda, source code aplikasi ini
idealnya juga tersedia terbuka, atau beli lisensi komersial dari MZ Automation untuk tetap
closed-source. Ini bukan nasihat hukum — sebaiknya dikonfirmasi ke pihak berwenang terkait
sebelum didistribusikan lebih luas.

### 5.2 Struktur Native

```
app/src/main/cpp/
 ├─ CMakeLists.txt        <- konfigurasi build native, klien-only (GOOSE/SV/TLS dimatikan)
 ├─ dfr_jni.c              <- JNI bridge: connect/listFiles/downloadFile/
 │                             getLogicalDevices/readDataAttribute/getLastError, dst
 └─ libiec61850/           <- source vendored (v1.6, GPLv3, tidak diubah)
```

### 5.3 Logika Pencarian Per-Vendor (`ComtradeSmartSearch.java`)

1. **Deteksi vendor** — baca `LPHD1.PhyNam.vendor` (fallback `.model`) dari relay lewat daftar
   Logical Device yang dibaca langsung (`IedConnection_getLogicalDeviceList`), bukan hardcode
   nama domain, supaya tidak gagal diam-diam kalau ada relay dengan konvensi penamaan berbeda.
2. **Strategi scan** — vendor yang dikenali (`NR/GE/ALSTOM/AREVA/SCHNEIDER/P44/SIEMENS`)
   memindai **semua** folder kandidat di `KNOWN_COMTRADE_PATHS`; vendor lain berhenti begitu 1
   folder ketemu file valid (lebih cepat).
3. **Smart pairing** — file `.zip` otomatis diikuti pasangan `...h.zip`-nya; file `.cfg`
   otomatis diikuti semua file lain dengan base-name sama (`.dat`, dst).
4. **Mode Bulk/Single** — Bulk = N file terbaru, Single = file ke-N dari urutan Z-A.

Unduhan berjalan **sekuensial** lewat 1 koneksi MMS yang sama (bukan paralel) — banyak relay
membatasi jumlah asosiasi MMS concurrent yang cukup rendah, jadi membuka banyak koneksi
paralel dari HP berisiko malah gagal konek.

### 5.4 Menambah Kapabilitas Native Baru

Fungsi tambahan dari libiec61850 (mis. `IedConnection_deleteFile`, atau
`ClientReport`/`ClientGooseControlBlock` untuk trigger event) sudah tersedia di source
vendored — tinggal ditambahkan sebagai fungsi baru di `dfr_jni.c` (pola JNI-nya sama persis
dengan fungsi yang sudah ada) dan method baru di `Iec61850DfrClient.java`.

---

## 6. Sistem Notifikasi & Update

- **Pengecekan silent** — setiap Home dibuka, aplikasi mengecek rilis terbaru di GitHub
  Releases API (`UpdateChecker`) tanpa mengganggu kalau tidak ada update atau gagal cek.
- **Push FCM** — topic `app_updates`, dikirim otomatis oleh GitHub Actions saat sebuah GitHub
  Release di-publish (lihat bagian 4). Diterima oleh `AppFcmService`, ditampilkan sebagai
  notifikasi sistem sekaligus dicatat ke feed in-app.
- **Feed notifikasi in-app** (`AppNotifications`, layar `NotificationActivity`) — diakses
  lewat ikon lonceng di header Home; titik merah muncul kalau ada yang belum dibaca. Entri
  dari update yang terdeteksi (baik lewat push maupun pengecekan aplikasi) dedupe per nomor
  versi, jadi rilis yang sama tidak muncul dobel.
- **Update sekali tap** — dari dialog update atau dari entri notifikasi, unduhan APK berjalan
  di foreground service (`UpdateDownloadService`) dan instalasi otomatis terbuka begitu
  selesai, tanpa perlu tap notifikasi unduhan sistem.

---

## 7. Sistem Tema

5 skema warna via `ThemeManager` (SharedPreferences, index 0–4), dipilih dari drawer Home:
**Modern Blue, Emerald, Purple, Red** — masing-masing satu hue di seluruh aplikasi — dan
**Abstract**, yang berbeda: memakai palet 6 warna (Azure/Violet/Magenta/Coral/Teal/Green) dan
merotasi kombinasi primary/secondary/accent-nya **per Activity**, supaya berpindah layar
terasa segar tanpa kehilangan satu identitas visual. Tiap skema punya varian Light & Dark
(`values/themes.xml` + `values-night/themes.xml`).
