package id.senzy.xpr;

import id.senzy.data.PlayerData;

/** Posisi progression sebuah boost: Tier + Level (Level 1..max-level per tier). */
public record BoostLevel(int tier, int level) {

    public static BoostLevel of(PlayerData.BoostState state) {
        return new BoostLevel(state.tier, state.level);
    }

    /** Indeks progression global 1-based: (tier-1) * maxLevel + level. */
    public int index(int maxLevel) {
        return (tier - 1) * maxLevel + level;
    }
}
