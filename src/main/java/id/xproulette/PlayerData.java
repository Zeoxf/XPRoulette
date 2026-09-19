package id.xproulette;

import java.util.ArrayList;
import java.util.List;

/** Status Roulette milik satu pemain. */
final class PlayerData {

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

    boolean hasRisk() {
        for (RolledEffect e : effects) {
            if (e.risk()) return true;
        }
        return false;
    }
}
