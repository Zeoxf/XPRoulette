# XP Roulette (PaperMC 1.21.11)

Plugin efek acak berdasarkan **level XP**. Makin tinggi level, makin kuat efeknya - tapi makin besar juga risikonya.

## Aturan main

1. Tiap **30 menit**, roda takdir berputar dan memberi pemain efek acak selama **15 menit**. Sisa 15 menit = **cooldown**.
2. Efek dipilih berdasarkan **level XP saat roda berputar**.
3. **Pengali**: level 0-4 = x1. Tiap naik 5 level, dikali 2 -> `x1 -> x2 -> x4 -> x8 -> x16 -> x32 ...`
   (level 0-4 = x1, 5-9 = x2, 10-14 = x4, 15-19 = x8, dst). Level efek = pengali (Speed x4 = Speed IV).
4. Makin tinggi level: makin banyak efek positif, tapi **risiko** (Poison, Weakness, Wither, Slowness, dll) makin sering & banyak.
5. **Mati saat efek aktif** -> semua efek hangus & tidak diberikan lagi. Sisa siklus menjadi cooldown.
6. Waktu hanya berjalan saat pemain **online**. Susu / totem tidak bisa menghapus efek (dipulihkan tiap detik).
7. Ikut Roulette itu **pilihan**: pemain bisa ikut / keluar **selamanya** atau **sementara** (per durasi atau per round).

## Build

Butuh Java 21 dan Maven:

```
mvn clean package
```

Hasilnya `target/XPRoulette-1.0.0.jar` -> taruh di folder `plugins/` server Paper 1.21.11.

## Perintah

| Perintah | Fungsi | Izin |
|---|---|---|
| `/xpr` atau `/xpr status` | Status Roulette, efek aktif, sisa waktu | `xproulette.use` |
| `/xpr join` | Ikut Roulette selamanya | `xproulette.use` |
| `/xpr join [durasi/round]` | Ikut sementara, lalu otomatis keluar | `xproulette.use` |
| `/xpr leave` | Keluar dari Roulette selamanya | `xproulette.use` |
| `/xpr leave [durasi/round]` | Libur sementara, lalu otomatis ikut lagi | `xproulette.use` |
| `/xpr help` | Bantuan semua perintah | `xproulette.use` |
| `/xpr aturan` | Tampilkan aturan main | `xproulette.use` |
| `/xpr preview [level]` | Lihat pengali di level tertentu | `xproulette.use` |
| `/xpr reload` | Muat ulang config.yml & messages.yml | `xproulette.admin` |
| `/xpr force [pemain]` | Paksa roda berputar sekarang (untuk tes) | `xproulette.admin` |
| `/xpr reset [pemain]` | Reset data pemain | `xproulette.admin` |

## Ikut / keluar (partisipasi)

Format durasi: `30m` (menit), `2h` (jam), `1d` (hari), `3r` (3 round), `forever` (selamanya). Spasi boleh: `/xpr join 5 round`.

| Contoh | Arti |
|---|---|
| `/xpr join` | Ikut permanen |
| `/xpr join 2h` | Ikut 2 jam, lalu otomatis keluar |
| `/xpr join 5r` | Ikut 5 putaran roda, lalu otomatis keluar |
| `/xpr leave` | Keluar permanen |
| `/xpr leave 1d` | Libur 1 hari, lalu otomatis ikut lagi |
| `/xpr leave 3r` | Lewati 3 putaran roda, lalu otomatis ikut lagi |

* Durasi waktu dihitung waktu nyata (termasuk saat offline). 1 round = 1 putaran roda (hanya berjalan saat online).
* Saat libur, jadwal roda tetap jalan, jadi keluar-masuk tidak bisa dipakai untuk melewati cooldown.
* Saat keluar, efek yang sedang aktif langsung dicabut.
* Di `config.yml` bagian `participation`: `default-opt-in` (pemain baru otomatis ikut atau tidak), `allow-leave` (larang pemain keluar), batas maksimal durasi/round.

## Konfigurasi

* `config.yml` - waktu siklus, pengali, jumlah efek, peluang risiko, daftar efek + bobot + batas level.
* `messages.yml` - semua pesan (format MiniMessage), bebas diubah/ditambah.
* `data.yml` - dibuat otomatis, menyimpan status tiap pemain.

Tips: kalau `DOUBLE` terlalu gila, ubah `scaling.mode` ke `LINEAR` (x1, x2, x3, ...).
Batas level tiap efek diatur di `max-level` (mis. Resistance dibatasi 4 supaya tidak kebal total).

---

## Random Loot Box Event

Event terjadwal: 5 Loot Box muncul acak di sekitar seorang pemain (area 500x500 secara default),
berlangsung 1 jam, lalu cooldown 30 menit. Klik kanan pada box untuk membukanya. Box yang tidak
sempat dibuka dihapus saat sesi selesai. Berjalan **berdampingan** dengan XP Roulette; XP Roulette
sendiri tidak diubah.

### Perintah `/xpr loot`

| Perintah | Siapa | Fungsi |
|---|---|---|
| `/xpr loot` atau `/xpr loot status` | semua | status, sisa waktu, box tersedia/dibuka, rarity tertinggi |
| `/xpr loot locations` | admin | koordinat semua box sesi ini |
| `/xpr loot start [pemain] [force]` | admin | mulai event (pemain = pusat area; `force` = lewati cooldown) |
| `/xpr loot stop` | admin | hentikan event, box dihapus |
| `/xpr loot reload` | admin | muat ulang `lootbox.yml` + `events.yml` |

Admin = permission `xproulette.admin`.

### File konfigurasi

| File | Isi |
|---|---|
| `lootbox.yml` | durasi/cooldown, jumlah & area spawn, aturan lokasi, fallback, rarity, loot table, efek, bossbar, announcement |
| `events.yml` | EventManager: auto-start, jeda retry, bobot & enable/disable tiap jenis event |
| `messages.yml` | semua teks (blok `lootbox:`) |
| `lootbox-state.yml` | *otomatis*: state sesi untuk restart/crash. Jangan diedit saat server hidup |

`config.yml` (XP Roulette) tidak disentuh.

### Cara kerja pencarian lokasi

```
RANDOM X/Z -> VALIDATE -> RETRY -> FALLBACK -> SUCCESS      (tidak pernah: RANDOM -> SPAWN PAKSA)
```

Validasi berurutan: dunia -> world border -> jarak dari pemain -> zona terlarang -> chunk ->
permukaan (Y dari terrain, **tidak pernah random**) -> rentang Y -> block tanah -> cairan ->
ruang/atap/pohon -> jarak antar box.

Jika sebuah box tak kunjung dapat lokasi, urutan fallback-nya: **retry** (`max-location-attempts`) ->
**perlebar radius** (sampai `fallback.max-radius`) -> **longgarkan** jarak antar box lalu jarak dari
pemain -> **lokasi valid terdekat** dari pusat. Jika semua gagal, box itu dianggap *FAILED*
(event jalan terus dengan sisanya). Jika yang berhasil kurang dari `minimum-successful-spawns`,
seluruh sesi dibatalkan dan dibersihkan.

Tidak pernah dilonggarkan oleh fallback: dunia, world border, zona terlarang, void, lava, air, block solid.
Pemuatan chunk selalu asinkron dan dibatasi `max-chunk-loads`.

### Catatan perilaku

- Pusat area = koordinat pemain **saat sesi dimulai**; pemain keluar/teleport/mati tidak menggeser box.
- Box berupa block sungguhan (Barrel/Chest/Ender Chest/Shulker Box menurut rarity). Block asli di tempat itu
  dipulihkan saat box dibuka/dihapus. Box kebal dari dihancurkan, ledakan, piston, enderman, api.
- Klaim memakai *lock*: dua pemain yang klik bersamaan tidak bisa mengambil box yang sama.
- Inventory penuh -> sisa item dijatuhkan di kaki pemain (tidak ada yang hilang).
- `persistence: true` -> event dipulihkan setelah restart selama waktunya belum habis; jika sudah habis,
  box dibersihkan otomatis saat server hidup lagi. Waktu berjalan juga saat server mati (jam dinding).
- Nether tidak didukung sebagai lokasi (deteksi permukaan tidak berlaku di sana); `allow-water` belum didukung.
- Menambah event baru (Supply Drop, Meteor, dst.): implementasikan `GameEvent`, daftarkan lewat
  `EventManager.registerEvent(...)`, aktifkan di `events.yml`. `EventManager` tidak perlu diubah.

### Struktur kode

```
id.xproulette.event      EventType, EventState, GameEvent, EventManager (registry + scheduler + anti-tabrakan), XpRouletteEvent (adapter)
id.xproulette.lootbox    LootBoxEvent, LocationFinder, LootGenerator, LootBox, LootBoxSettings, LootBoxStore, PlayerSelector, ...
id.xproulette.util       WeightedRandom (RNG berbobot terpusat), TimeParser, Lookup, Configs
```
