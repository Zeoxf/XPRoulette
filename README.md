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
| `/xpr aturan` | Tampilkan aturan main | `xproulette.use` |
| `/xpr preview [level]` | Lihat pengali di level tertentu | `xproulette.use` |
| `/xpr reload` | Muat ulang config.yml & messages.yml | `xproulette.admin` |
| `/xpr force [pemain]` | Paksa roda berputar sekarang (untuk tes) | `xproulette.admin` |
| `/xpr reset [pemain]` | Reset data pemain | `xproulette.admin` |

## Konfigurasi

* `config.yml` - waktu siklus, pengali, jumlah efek, peluang risiko, daftar efek + bobot + batas level.
* `messages.yml` - semua pesan (format MiniMessage), bebas diubah/ditambah.
* `data.yml` - dibuat otomatis, menyimpan status tiap pemain.

Tips: kalau `DOUBLE` terlalu gila, ubah `scaling.mode` ke `LINEAR` (x1, x2, x3, ...).
Batas level tiap efek diatur di `max-level` (mis. Resistance dibatasi 4 supaya tidak kebal total).
