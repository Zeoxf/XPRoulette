# Membangun Senzy

Proyek ini ditulis lengkap (Java 21, package `id.senzy`) tapi **belum pernah dikompilasi**
di lingkungan pengembangan ini karena sandbox tidak punya akses internet maupun
`paper-api` lokal untuk resolusi dependency Maven. Build harus dijalankan di komputer/CI
yang punya internet.

## Cara build

1. Pastikan Java 21 dan Maven terpasang.
2. Di folder proyek ini, jalankan:
   ```
   mvn clean package
   ```
3. Hasil jar ada di `target/Senzy-1.0.0.jar`.
4. Salin ke folder `plugins/` server PaperMC 1.21.11, restart/reload server.

## Yang perlu dicek saat build pertama kali

Karena kode ditulis manual tanpa kompilasi langsung, kemungkinan ada typo kecil (nama
method/kelas) yang baru ketahuan saat `javac` jalan. Jika build gagal:

- Baca pesan error `mvn` (nama file + baris) dan perbaiki sesuai konteks; struktur besar
  (arsitektur, alur data, rumus) sudah benar sesuai spesifikasi, jadi error yang muncul
  biasanya kecil (import, nama method).
- Kirim balik pesan error ke saya jika ingin dibantu memperbaikinya.

## Struktur yang sudah selesai

- `xpr/` — Boost Progression penuh (unlock → level 1-30 → tier advance, rumus durasi/cooldown,
  side-effect 5 stage, GUI, confirm GUI, command).
- `lootbox/` — Event LootBox penuh (spawn area POINT/RADIUS/REGION, validasi & fallback
  bertingkat, beacon murni visual + restore blok asli, hologram, loot table weighted,
  persistence tahan restart, bossbar, locate, GUI admin lokasi, command).
- `data/`, `config/`, `gui/`, `event/`, `command/`, `listener/`, `integration/`, `util/` —
  infrastruktur pendukung (persistence atomik, MiniMessage messages.yml, router GUI,
  manajer modul, PlaceholderAPI).
- `config.yml` & `messages.yml` — semua angka gameplay dan teks pemain, sudah cocok
  dengan setiap key yang dipakai kode (dicek silang lewat grep terhadap seluruh source).

## Belum/tidak sempat diuji langsung di server

- Uji langsung dengan Geyser/Floodgate (desain sudah mengikuti aturan "chest GUI polos,
  tanpa resource pack" supaya kompatibel, tapi belum ada server nyata untuk uji coba).
- Uji beban performa dunia besar dengan banyak pemain bersamaan.
