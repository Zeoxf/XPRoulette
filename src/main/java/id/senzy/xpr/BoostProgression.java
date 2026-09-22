package id.senzy.xpr;

import id.senzy.data.PlayerData;

/**
 * Aturan progression murni (tanpa I/O, tanpa Bukkit). RNG hanya memilih JENIS boost;
 * fungsi di sini memutuskan apa yang terjadi pada boost yang terpilih.
 *
 * <ul>
 *   <li>Belum punya -> Unlock: Tier I Level 1</li>
 *   <li>Sudah punya -> Level + 1</li>
 *   <li>Level = max-level -> milestone (MASTERED). Level TIDAK PERNAH melebihi max-level.
 *       Tier berikutnya dibuka lewat {@link #advance}, lalu progression kembali ke Level 1.</li>
 * </ul>
 */
public final class BoostProgression {

    public enum Outcome {
        /** Boost baru dimiliki (Tier I, Level 1). */
        UNLOCKED,
        /** Level naik di tier yang sama. */
        LEVEL_UP,
        /** Sudah Level max dan tier berikutnya menunggu konfirmasi pemain (auto-advance = false). */
        TIER_READY,
        /** Tier berikutnya terbuka; sekarang Level 1 di tier baru. */
        TIER_ADVANCED,
        /** Tier terakhir sudah Level max: tidak ada yang bisa naik lagi. */
        MAXED
    }

    /**
     * @param mastered         level sekarang = max-level
     * @param awaitingAdvance  mastered dan masih ada tier berikutnya yang menunggu dibuka
     */
    public record Result(Outcome outcome, int tier, int level, boolean mastered, boolean awaitingAdvance) {}

    private final BoostManager boosts;

    public BoostProgression(BoostManager boosts) {
        this.boosts = boosts;
    }

    /** Menerapkan satu hasil RNG (boost ini terpilih) pada state pemain. */
    public Result applyDuplicate(PlayerData.BoostState st, Boost boost) {
        int max = boosts.maxLevel();
        boolean auto = boosts.autoAdvance();

        if (!st.unlocked) {
            st.unlocked = true;
            st.tier = 1;
            st.level = 1;
            st.rolls++;
            return result(Outcome.UNLOCKED, st, boost);
        }

        normalize(st, boost);
        if (isFullyMaxed(st, boost)) {
            return result(Outcome.MAXED, st, boost);
        }

        // Level max di tier yang belum terakhir = milestone yang menunggu dibuka.
        if (st.level >= max) {
            if (auto) {
                advance(st, boost);
                st.rolls++;
                return result(Outcome.TIER_ADVANCED, st, boost);
            }
            return result(Outcome.TIER_READY, st, boost);
        }

        st.level++;
        st.rolls++;
        if (st.level >= max && canAdvance(st, boost) && auto) {
            advance(st, boost);
            return result(Outcome.TIER_ADVANCED, st, boost);
        }
        return result(Outcome.LEVEL_UP, st, boost);
    }

    public boolean canAdvance(PlayerData.BoostState st, Boost boost) {
        return st != null && st.unlocked && st.level >= boosts.maxLevel() && st.tier < boost.tierCount();
    }

    /** Membuka tier berikutnya: Tier + 1, Level 1. False jika belum memenuhi syarat. */
    public boolean advance(PlayerData.BoostState st, Boost boost) {
        if (!canAdvance(st, boost)) return false;
        st.tier++;
        st.level = 1;
        return true;
    }

    /** Sudah di tier terakhir dan Level max. */
    public boolean isFullyMaxed(PlayerData.BoostState st, Boost boost) {
        return st != null && st.unlocked && st.tier >= boost.tierCount() && st.level >= boosts.maxLevel();
    }

    /** Level max di tier sekarang (status MASTERED / STABLE). */
    public boolean isMastered(PlayerData.BoostState st) {
        return st != null && st.unlocked && st.level >= boosts.maxLevel();
    }

    /** Menjepit data ke definisi saat ini (mis. admin menurunkan max-level atau jumlah tier di config). */
    private void normalize(PlayerData.BoostState st, Boost boost) {
        st.tier = Math.max(1, Math.min(boost.tierCount(), st.tier));
        st.level = Math.max(1, Math.min(boosts.maxLevel(), st.level));
    }

    private Result result(Outcome outcome, PlayerData.BoostState st, Boost boost) {
        boolean mastered = st.level >= boosts.maxLevel();
        boolean awaiting = mastered && st.tier < boost.tierCount();
        return new Result(outcome, st.tier, st.level, mastered, awaiting);
    }
}
