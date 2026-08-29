# siPRO PLN
**Tools Serbaguna Perhitungan Setting Proteksi Sistem Tenaga Listrik**
developed by **alidev (Mahmud Sulaiman A)**

Aplikasi Android (Java) untuk membantu engineer proteksi melakukan perhitungan setting
proteksi secara cepat, sistematis, dan terdokumentasi — mencakup Trafo Tenaga, Busbar,
Kapasitor Bank, dan Transmisi (Distance/DEF) — lengkap dengan visualisasi kurva TCC,
diagram R-X, karakteristik diferensial, dan diagram koordinasi waktu-jarak (scanning).

---

## 1. Cara Membuka Project

1. Install **Android Studio** versi terbaru (Koala/2024.1 ke atas direkomendasikan).
2. Pilih **File > Open**, arahkan ke folder `SiProPLN` (folder yang berisi file `settings.gradle` ini).
3. Android Studio akan otomatis men-generate Gradle Wrapper (`gradlew`) dan melakukan sinkronisasi
   dependency saat pertama kali dibuka — pastikan komputer terkoneksi internet pada tahap ini
   (untuk mengunduh Gradle 8.6, Android Gradle Plugin, dan library AndroidX/Material yang dipakai).
4. Setelah Gradle sync selesai, jalankan aplikasi ke emulator/device melalui tombol **Run ▶**.

Requirement: `compileSdk 34`, `minSdk 24` (Android 7.0+), `targetSdk 34`, Java 8 source/target.

> Catatan: project ini disusun tanpa file biner `gradle-wrapper.jar` (dibuat di lingkungan
> tanpa akses internet ke server distribusi Gradle). Android Studio akan membuatkan file
> tersebut secara otomatis saat sinkronisasi pertama, atau bisa dibuat manual dengan
> menjalankan `gradle wrapper` bila Anda memiliki instalasi Gradle lokal.

---

## 2. Struktur Aplikasi

```
MainActivity (Dashboard 5 kategori)
 └─ CategoryActivity (daftar kalkulator per kategori)
     └─ CalculatorActivity (form input -> HITUNG -> hasil + penjelasan + chart)
AboutActivity (tentang aplikasi & kredit pengembang)
```

Seluruh kalkulator didefinisikan secara terpusat di `data/AppData.java` (form field,
label, satuan, nilai default, teori) dan rumusnya diimplementasikan di
`logic/Calculators.java`. Menambah kalkulator baru cukup dengan menambahkan satu entri
`CalcDefinition` di `AppData` dan satu method perhitungan di `Calculators` — tidak perlu
membuat Activity atau layout XML baru, karena form, hasil, dan status dirender secara
dinamis oleh `CalculatorActivity`.

### 5 Kategori & 27 Kalkulator

**Proteksi Trafo Tenaga**
- Arus Nominal Trafo & NGR
- Setting Relai Differensial (87T)
- Setting REF - Restricted Earth Fault (87N)
- OCR Incoming Sisi Sekunder (50/51)
- GFR Incoming Sisi Sekunder (50N/51N)
- OCR Sisi Primer 150 kV (50/51)
- GFR Sisi Primer 150 kV
- Setting SBEF (Stand By Earth Fault)

**Proteksi Busbar & Kopel**
- Diff Busbar High Impedance (87B)
- Proteksi Arus Sirkulasi - CCP (87)
- Diff Busbar Low Impedance (87B)
- Setting Breaker Failure - CBF (50BF)
- Setting Proteksi Kopel (OCR/GFR)

**Proteksi Kapasitor Bank**
- Setting OCR/GFR Kapasitor (51/51N)
- Setting Over Load Kapasitor
- Cek Relai Unbalance (46)
- Setting Relai Tegangan (59/27)

**Proteksi Transmisi (Distance/DEF)**
- Zone Reach Distance Relay (Z1/Z2/Z3)
- SIR - Source to Line Impedance Ratio
- Faktor Infeed / Outfeed
- Resistive Reach (RLoad) & Blinder
- Setting DEF (Directional Earth Fault)
- Setting Synchro Check

**Scanning & Kurva Karakteristik**
- Kurva Karakteristik Arus-Waktu (IEC SI/VI/EI/LTI/Definite)
- Diagram R-X Koordinasi Zone Distance
- Karakteristik Diferensial (cek titik kerja terhadap slope)
- Koordinasi Waktu-Jarak 2 Seksi (scanning antar penghantar)

---

## 3. Sumber Rumus

Formula diambil dan disusun ulang (dalam bahasa sendiri, dengan variabel dan satuan yang
konsisten) dari 6 modul referensi internal:

1. Pola Proteksi Gardu Induk
2. Filosofi Proteksi Transmisi
3. Scanning Proteksi Transmisi
4. Perhitungan Setting Relai Proteksi Trafo Tenaga
5. Perhitungan Setting Proteksi Busbar
6. Perhitungan Setting Relai Proteksi Kapasitor

Kurva arus-waktu memakai standar IEC 60255: `t = TMS x k / ((I/Is)^alpha - 1)`, dengan
konstanta k dan alpha untuk Standard Inverse, Very Inverse, Extremely Inverse, dan Long
Time Inverse (lihat `logic/CurveUtils.java`).

Beberapa nilai (mis. tegangan setting rangkaian supervisi CT pada proteksi busbar) memakai
pendekatan yang diberi catatan eksplisit di dalam aplikasi — sebaiknya diverifikasi ulang
terhadap manual book relai yang dipakai di lapangan sebelum diterapkan.

**Penting:** aplikasi ini bersifat bantu-hitung (assistive tool). Setting akhir yang
diterapkan di jaringan harus tetap diverifikasi oleh engineer proteksi yang berwenang.

---

## 4. Kustomisasi Tampilan

- Warna: `res/values/colors.xml` (palet navy + amber bertema kelistrikan/PLN).
- Ikon peluncur (launcher icon) dibuat sebagai PNG raster (shield ring + petir) di setiap
  folder `mipmap-*dpi`, plus adaptive icon (`mipmap-anydpi-v26`) untuk Android 8+.
- Kredit pengembang ditampilkan di splash screen, header dashboard, dan halaman About.
  Android tidak menyediakan mekanisme bawaan untuk menaruh teks kecil di bawah ikon
  aplikasi pada home screen/launcher (di luar kendali developer, tergantung launcher OEM),
  sehingga kredit "developed by alidev (Mahmud Sulaiman A)" ditempatkan pada titik-titik
  yang paling terlihat di dalam aplikasi sebagai gantinya.

---

## 5. Menambah Kalkulator Baru

1. Tambahkan entri baru di `AppData.java` (`ALL.put("id_baru", new CalcDefinition(...))`)
   dengan daftar `FieldDef` (key, label, unit, default, help).
2. Tambahkan `case "id_baru": return methodBaru(in);` di `Calculators.compute()`.
3. Implementasikan `private static CalcResult methodBaru(Map<String, Double> in)` yang
   membaca field dengan key yang sama persis seperti langkah 1, lalu mengisi
   `resultText`, `explanationText`, `status`, dan (opsional) `chartParams` sesuai
   `ChartType` yang dipilih.

Tidak ada layout XML atau Activity baru yang perlu dibuat.

---

## 6. Fitur DFR Downloader (IEC 61850 / MMS)

Fitur terpisah dari kalkulator (kartu "DFR Downloader" di dashboard) untuk mengunduh file
COMTRADE/DFR langsung dari relay proteksi lewat protokol IEC 61850 (MMS File Transfer
Service). Fitur ini pakai native code (C), bukan cuma Java, jadi butuh langkah setup
tambahan sebelum bisa di-build.

### 6.1 Asal & Lisensi

Native layer memakai **libiec61850** (https://github.com/mz-automation/libiec61850, versi
1.6, source lengkap divendor di `app/src/main/cpp/libiec61850/`). Library ini **GPLv3**
(lihat `app/src/main/cpp/libiec61850/COPYING`) — beda dengan LGPL, GPLv3 bersifat copyleft
bahkan untuk static linking. Kalau APK hasil build ini didistribusikan ke pihak di luar tim
Anda, source code aplikasi ini idealnya juga tersedia terbuka, atau beli lisensi komersial
dari MZ Automation untuk tetap closed-source. Ini bukan nasihat hukum — sebaiknya
dikonfirmasi ke pihak yang berwenang di internal PLN kalau app ini akan didistribusikan
lebih luas.

### 6.2 Yang Perlu Disiapkan Sebelum Build Pertama

1. Buka **SDK Manager** di Android Studio → tab **SDK Tools** → centang **NDK (Side by
   side)** dan **CMake** → Apply. (Kalau belum pernah build native code sebelumnya, dua
   komponen ini kemungkinan belum terpasang.)
2. Sync Gradle ulang. Android Studio akan otomatis menjalankan CMake untuk mem-build
   `libiec61850` (client-only — GOOSE/Sampled Values/TLS sengaja dimatikan karena tidak
   dibutuhkan untuk download file, lihat opsi CMake di `app/src/main/cpp/CMakeLists.txt`)
   plus JNI bridge `dfr_jni.c` menjadi `libdfr_jni.so` untuk `armeabi-v7a` dan `arm64-v8a`.

**Catatan jujur:** seluruh kode native (`dfr_jni.c` + konfigurasi CMake) ditulis tanpa akses
ke Android NDK/cross-compiler saat development, jadi **belum pernah ter-compile-test**.
Source-nya sudah diverifikasi manual terhadap header resmi libiec61850 (nama fungsi, enum,
signature semua dicocokkan satu-satu), tapi build pertama di Android Studio Anda mungkin
memunculkan error kecil terkait perbedaan Bionic libc (Android) vs glibc (Linux biasa) yang
baru ketahuan saat compile sungguhan. Kalau muncul error build, salin pesan errornya —
biasanya perbaikannya kecil (nama header/flag yang perlu disesuaikan).

### 6.3 Struktur Kode

```
app/src/main/cpp/
 ├─ CMakeLists.txt        <- konfigurasi build native, klien-only
 ├─ dfr_jni.c              <- JNI bridge: connect/listFiles/downloadFile/
 │                             getLogicalDevices/readDataAttribute/getLastError
 └─ libiec61850/           <- source vendored (v1.6, GPLv3, tidak diubah)

app/src/main/java/com/alidev/sipropln/dfr/
 ├─ Iec61850DfrClient.java   <- wrapper Java atas native (semua method BLOCKING,
 │                               wajib dipanggil dari background thread)
 ├─ DfrFileEntry.java        <- model 1 baris hasil listing direktori relay
 ├─ ComtradeSmartSearch.java <- LOGIKA PENCARIAN PER-VENDOR (lihat 6.4)
 └─ DfrDownloadActivity.java <- UI: connect -> auto smart-scan -> pilih mode -> unduh -> bagikan
```

### 6.4 Logika Pencarian Per-Vendor (`ComtradeSmartSearch.java`)

Di-port dari project Windows lama Anda (`Comtrade_Downloader`, `DownloaderLogic.cpp` v4.14.0),
bukan ditulis dari nol. Alurnya:

1. **Deteksi vendor** — baca `LPHD1.PhyNam.vendor` (fallback `.model`) dari relay. Beda dengan
   versi Windows yang hardcode nama domain `"IEDRCD"`, versi Android ini membaca daftar
   Logical Device relay dulu (`IedConnection_getLogicalDeviceList`) dan pakai yang pertama —
   supaya tidak gagal diam-diam kalau ada relay dengan konvensi penamaan LD yang berbeda.
2. **Strategi scan** — kalau vendor terdeteksi termasuk `NR/GE/ALSTOM/AREVA/SCHNEIDER/P44/SIEMENS`,
   scan **semua** 10 folder kandidat di `KNOWN_COMTRADE_PATHS` (`dr`, `COMTRADE`, `PROT`, `REC`,
   `RECORD`, `measurements`, `HMI/recordings`, `disturbance`, dll). Vendor lain: berhenti begitu
   1 folder ketemu file valid (lebih cepat).
3. **Smart pairing** — file `.zip` otomatis diikuti pasangan `...h.zip`-nya; file `.cfg` otomatis
   diikuti semua file lain dengan base-name sama (`.dat`, dst).
4. **Mode Bulk/Single** — sama seperti versi Windows: Bulk = N file terbaru, Single = file ke-N
   dari urutan Z-A.

**Perbedaan sengaja dari versi Windows** (bukan kelalaian — dicatat di komentar kode juga):
unduhan berjalan **sekuensial** lewat 1 koneksi yang sama, bukan 2 worker thread paralel dengan
koneksi terpisah seperti versi Windows. Alasannya: banyak relay membatasi jumlah asosiasi MMS
concurrent yang cukup rendah, jadi membuka banyak koneksi paralel dari HP berisiko malah gagal
konek. Kalau nanti terbukti aman/perlu lebih cepat, tinggal diubah ke `ExecutorService` 2 thread.

### 6.5 Cara Pakai di Aplikasi

1. Buka kartu "DFR Downloader" dari dashboard.
2. Isi IP relay dan port (default MMS = **102**), tekan HUBUNGKAN.
3. Aplikasi otomatis mendeteksi vendor lalu memindai folder COMTRADE (indikator vendor +
   mode scan tampil di bawah status koneksi).
4. Daftar file COMTRADE ditemukan tampil terurut terbaru-di-atas. Tekan **UNDUH SET** pada
   file tertentu untuk mengunduh file itu + pasangannya secara otomatis.
5. Atau pilih **Mode Unduh Massal** (Bulk N-terbaru / Single ke-N), isi N, tekan
   **UNDUH SESUAI MODE** untuk mengunduh sekaligus.
6. File tersimpan di `Android/data/com.alidev.sipropln/files/DFR/`, muncul opsi bagikan
   setelah selesai.

### 6.6 Keterbatasan Versi Saat Ini

- Progress unduhan berupa teks status per-file, belum persentase real-time per file.
- Belum ada trigger otomatis "DFR baru siap diunduh" lewat report/GOOSE saat ada gangguan —
  saat ini dipicu manual setiap kali connect.
- Belum ada dukungan TLS (koneksi MMS polos, sesuai kebiasaan umum relay proteksi di
  jaringan OT yang terisolasi/tidak terhubung internet langsung).
- Daftar `KNOWN_COMTRADE_PATHS` dan kata kunci vendor deep-scan sama persis dengan versi
  Windows — kalau di lapangan ada folder/vendor baru yang belum tercakup, tinggal tambah di
  `ComtradeSmartSearch.java` (2 array di bagian atas file).

### 6.7 Menambah Kapabilitas Baru

Fungsi tambahan dari libiec61850 (mis. `IedConnection_deleteFile` untuk hapus file di relay
setelah diunduh, atau `ClientReport`/`ClientGooseControlBlock` untuk trigger event) sudah
tersedia di source vendored — tinggal ditambahkan sebagai fungsi baru di `dfr_jni.c`
(pola JNI-nya sama persis dengan fungsi yang sudah ada) dan method baru di
`Iec61850DfrClient.java`.

