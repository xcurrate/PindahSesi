# PindahSesi

Aplikasi Android kecil buat nyimpen beberapa sesi login ChatGPT dan pindah antar akun
dalam hitungan detik, tanpa logout dan tanpa login ulang.

Di HP, namanya muncul sebagai "ChatGPT Switcher".

Kalau kamu punya lebih dari satu akun ChatGPT, kamu tahu rasanya: logout, masukin email,
tunggu OTP, ulang lagi besok. PindahSesi menyimpan isi data aplikasi ChatGPT per akun,
jadi pindah akun cuma soal menukar folder.

## Cara kerjanya

Aplikasi ChatGPT menyimpan sesi login di `/data/data/com.openai.chatgpt`. PindahSesi
melakukan ini, semuanya lewat `su`:

1. `am force-stop` aplikasi ChatGPT
2. menyalin folder data ke `/data/adb/chatgpt_switcher/<nama profil>`
3. sebelum pindah, data yang sedang aktif disimpan ulang ke profilnya sendiri, jadi
   chat atau setting terbaru tidak hilang. Kalau belum ada profil aktif, salinannya
   ditaruh di `_last`
4. profil tujuan disalin balik ke `/data/data/com.openai.chatgpt`
5. `chown` dan `restorecon` supaya pemilik file dan label SELinux tetap benar
6. aplikasi ChatGPT dibuka lagi

Tidak ada permintaan logout ke server. Token sesi akun lama tetap hidup di file
profilnya, jadi waktu kamu balik lagi tidak perlu login.

## Yang dibutuhkan

- HP Android dengan root (sejauh ini dites pakai Magisk di Android 10)
- Aplikasi ChatGPT (`com.openai.chatgpt`) sudah terpasang
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

1. Login ke akun pertama di aplikasi ChatGPT seperti biasa.
2. Buka PindahSesi, tekan **Simpan**, isi nama profil (misal `kerja`).
3. Tekan **Sesi baru** kalau mau masuk akun lain. Data akun sebelumnya diarsipkan,
   aplikasi ChatGPT jadi seperti baru install dan menampilkan layar login.
4. Login akun kedua, simpan lagi dengan nama lain.
5. Untuk ganti-ganti, tinggal ketuk nama profil di daftar.

Ketuk lama nama profil untuk menghapusnya. Nama profil boleh huruf, angka, spasi,
titik, minus, garis bawah, dan tanda kurung, maksimal 40 karakter.

Pindah profil dan "Sesi baru" otomatis menyimpan state akun yang sedang aktif, jadi
tidak perlu menekan Simpan setiap kali habis chat.

## Isi folder profil

```
/data/adb/chatgpt_switcher/
├── kerja/       # salinan penuh /data/data/com.openai.chatgpt
├── pribadi/
└── _last/       # cadangan saat belum ada profil aktif
```

## Peringatan

- Aplikasi ini butuh root dan mengubah data aplikasi lain. Salah hapus folder bisa
  bikin sesi ChatGPT hilang. Simpan cadangan sendiri kalau perlu.
- Isi folder profil sama dengan isi data aplikasi ChatGPT, termasuk token login.
  Jangan disalin ke cloud, jangan dibagikan, dan hati-hati kalau backup seluruh
  partisi.
- Bukan buatan OpenAI dan tidak berafiliasi dengan mereka. Pakai dengan risiko sendiri.
- Berpindah-pindah akun dari satu perangkat tetap terlihat sebagai aktivitas tidak
  biasa di sisi server. Kalau akunnya penting, pikirkan dulu.

## Lisensi

MIT. Lihat [LICENSE](LICENSE).
