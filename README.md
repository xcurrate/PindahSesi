# PindahSesi

Aplikasi Android (butuh root) buat nyimpen beberapa sesi login dari app apa pun, lalu
pindah antar sesi dalam hitungan detik tanpa logout dan tanpa login ulang.

Awalnya dibuat khusus untuk ChatGPT, sekarang app targetnya bisa dipilih sendiri:
Discord, Instagram, WhatsApp, atau apa pun yang datanya ada di `/data/data/<paket>`.

## Cara kerjanya

Data login sebuah app ada di `/data/data/<paket>`. PindahSesi menyimpan salinan folder
itu per profil, lalu menukarnya balik saat kamu mau pindah akun. Semuanya lewat `su`:

1. `am force-stop` app target
2. sebelum pindah, data yang sedang aktif disimpan ulang ke profilnya sendiri, jadi
   chat atau setting terbaru tidak hilang (kalau belum ada profil aktif, salinannya
   ditaruh di `_last`)
3. profil tujuan disalin balik ke `/data/data/<paket>`
4. `chown` dan `restorecon` supaya pemilik file dan label SELinux tetap benar
5. app target dibuka lagi

Tidak ada permintaan logout ke server. Token sesi akun lama tetap hidup di file
profilnya, jadi waktu kamu balik lagi tidak perlu login.

## Yang dibutuhkan

- HP Android dengan root (dites pakai Magisk di Android 10)
- Android 7.0 ke atas (minSdk 24)

## Build

Butuh JDK 17 atau 21, Android SDK platform 34, dan build-tools 34.0.0.

```bash
git clone https://github.com/xcurrate/PindahSesi.git
cd PindahSesi
echo "sdk.dir=$HOME/android-sdk" > local.properties
./gradlew assembleDebug
```

Hasilnya `app/build/outputs/apk/debug/app-debug.apk`. APK debug sudah ditandatangani
debug key, jadi bisa langsung dipasang:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kalau malas menyiapkan toolchain, tiap push ke `main` GitHub Actions otomatis bikin
APK. Buka tab Actions, pilih run terakhir, lalu unduh artifact `PindahSesi-debug`.

## Pakai

1. Tekan tombol **Target** di atas, pilih app yang mau diatur. Pilihannya diambil dari
   app yang punya ikon launcher.
2. Buka app itu, login seperti biasa.
3. Balik ke PindahSesi, tekan **Simpan**, isi nama profil (misal `kerja`).
4. Tekan **Sesi baru** kalau mau masuk akun lain. Data akun sebelumnya diarsipkan,
   app target jadi seperti baru install dan menampilkan layar login.
5. Login akun kedua, simpan lagi dengan nama lain.
6. Untuk ganti-ganti, tinggal ketuk nama profil di daftar. Nama yang sedang dipakai
   ditandai titik putih.

Ketuk lama nama profil untuk membuka menu: **Hapus cache profil** atau **Hapus profil**.
Nama profil bebas, termasuk alamat email (`nama@gmail.com`). Yang tidak boleh cuma `/`,
baris baru, dan awalan `_` (dipakai folder internal), maksimal 60 karakter.

Pindah profil dan "Sesi baru" otomatis menyimpan state akun yang sedang aktif, jadi
tidak perlu menekan Simpan setiap kali habis chat.

## Soal ukuran

Folder data app bisa ratusan MB, tapi sebagian besar isinya cache. Yang disimpan cuma
data yang perlu:

- `cache/` dan `code_cache/` dilewati
- cache bawaan WebView (`GPUCache`, `Code Cache`, `CacheStorage`) dibuang, tapi
  `Cookies` dan `Local Storage` tetap ikut karena sering dipakai untuk sesi login
- `shared_prefs/`, `databases/`, `files/`, dan file tersembunyi tetap disalin

Ukuran tiap profil ditampilkan di daftar, begitu juga totalnya di baris status. Profil
lama yang dibuat sebelum aturan ini berlaku masih menyimpan cache; pakai **Hapus cache
profil** di menu ketuk-lama untuk mengecilkannya.

Kalau butuh lebih kecil lagi, langkah berikutnya yang masuk akal adalah menyimpan profil
sebagai arsip `tar.gz` (kompresi biasanya memotong 60–80% untuk XML/SQLite). Belum
dikerjakan karena menambah waktu saat pindah sesi.

## Struktur folder

```
/data/adb/pindah_sesi/
├── com.openai.chatgpt/
│   ├── kerja/       # salinan /data/data/com.openai.chatgpt
│   ├── pribadi/
│   └── _last/       # cadangan saat belum ada profil aktif
└── com.discord/
    └── utama/
```

Folder versi lama (`/data/adb/chatgpt_switcher`) otomatis dipindah ke
`/data/adb/pindah_sesi/com.openai.chatgpt` saat pertama dijalankan.

## Peringatan

- Aplikasi ini butuh root dan mengubah data app lain. Salah pilih app atau salah hapus
  folder bisa bikin app itu kehilangan data. Simpan cadangan sendiri kalau perlu.
- Jangan pakai untuk app sistem. Menyalin atau mengosongkan data app sistem bisa bikin
  HP bermasalah.
- Isi folder profil sama dengan isi data app, termasuk token login. Jangan disalin ke
  cloud, jangan dibagikan, dan hati-hati kalau backup seluruh partisi.
- Berpindah-pindah akun dari satu perangkat tetap terlihat sebagai aktivitas tidak biasa
  di sisi server app. Kalau akunnya penting, pikirkan dulu.
- Bukan buatan OpenAI (atau Discord, atau siapa pun yang app-nya kamu atur) dan tidak
  berafiliasi dengan mereka. Pakai dengan risiko sendiri.

## Roadmap

Dukungan tanpa root belum ada, karena app tidak bisa membaca `/data/data/<paket>` app
lain tanpa root. Rencana ke depannya:

- **Work profile** (Island/Shelter) untuk dua akun sekaligus tanpa root
- **Shizuku** untuk minimal "Sesi baru" (`pm clear`) tanpa root
- mode no-root akan jadi varian terpisah, bukan menggantikan versi root ini

## Lisensi

MIT. Lihat [LICENSE](LICENSE).
