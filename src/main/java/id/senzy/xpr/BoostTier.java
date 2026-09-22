package id.senzy.xpr;

/** Definisi satu tier sebuah boost (semua angka berasal dari config). */
public final class BoostTier {

    private final int tier;
    private final long baseDurationMillis;
    private final int amplifier;
    private final long baseCooldownMillis;

    /** @param baseCooldownMillis -1 = pakai xpr.cooldown.base */
    public BoostTier(int tier, long baseDurationMillis, int amplifier, long baseCooldownMillis) {
        this.tier = tier;
        this.baseDurationMillis = baseDurationMillis;
        this.amplifier = amplifier;
        this.baseCooldownMillis = baseCooldownMillis;
    }

    public int tier() {
        return tier;
    }

    public long baseDurationMillis() {
        return baseDurationMillis;
    }

    /** Amplifier potion (0 = level I). */
    public int amplifier() {
        return amplifier;
    }

    public long baseCooldownMillis() {
        return baseCooldownMillis;
    }
}
