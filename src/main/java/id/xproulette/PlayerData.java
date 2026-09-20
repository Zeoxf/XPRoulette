package id.xproulette;

import java.util.ArrayList;
import java.util.List;

/** Status Roulette milik satu pemain. */
final class PlayerData {

    /** Jenis pengaturan sementara (override) partisipasi. */
    enum Temp {NONE, TIME, ROUNDS}

    /** Detik yang sudah berjalan di siklus saat ini (hanya bertambah saat online). */
    int cycleElapsed;

    /** true = efek Roulette sedang aktif. */
    boolean active;

    /** true = pemain mati pada siklus ini, jadi tidak dapat efek lagi sampai siklus berikutnya. */
    boolean died;

    /** Level XP saat roda berputar terakhir kali. */
    int rolledLevel;

    /** Pengali saat roda berputar terakhir kali. */
    int multiplier = 1;

    /** Efek yang sedang aktif. */
    List<RolledEffect> effects = new ArrayList<>();

    // ---------------------------------------------------------------- partisipasi

    /** Pengaturan permanen: true = ikut, false = tidak ikut. */
    boolean optIn = true;

    /** Pengaturan sementara. Jika bukan NONE, ini menimpa optIn sampai habis. */
    Temp tempType = Temp.NONE;

    /** Nilai pengaturan sementara: true = ikut sementara, false = keluar sementara. */
    boolean tempOn;

    /** (TIME) waktu berakhir, epoch millis. */
    long tempUntil;

    /** (ROUNDS) sisa putaran roda. */
    int tempRounds;

    /** Cache (tidak disimpan): apakah saat ini pemain benar-benar ikut. */
    boolean participating = true;

    /** Status ikut/tidak yang berlaku sekarang, dengan memperhitungkan pengaturan sementara. */
    boolean effective() {
        return tempType != Temp.NONE ? tempOn : optIn;
    }

    boolean hasRisk() {
        for (RolledEffect e : effects) {
            if (e.risk()) return true;
        }
        return false;
    }
}
