package id.xproulette;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/** Menghitung pengali berdasarkan level XP dan mengundi efek acak. */
final class EffectRoller {

    record Entry(String key, int weight, int maxLevel) {
    }

    record Roll(int level, int tier, int multiplier, List<RolledEffect> effects) {
    }

    private final List<Entry> good = new ArrayList<>();
    private final List<Entry> bad = new ArrayList<>();

    private int levelsPerTier = 5;
    private boolean doubleMode = true;
    private int baseMultiplier = 1;
    private int linearStep = 1;
    private int globalMax = 64;

    private int goodBase = 2;
    private int goodExtraEvery = 2;
    private int goodMax = 5;
    private double riskBase = 0.30;
    private double riskPerTier = 0.10;
    private double riskMaxChance = 1.0;
    private int riskExtraEvery = 3;
    private int riskMax = 3;

    void load(FileConfiguration c, Logger log) {
        levelsPerTier = Math.max(1, c.getInt("scaling.levels-per-tier", 5));
        doubleMode = !"LINEAR".equalsIgnoreCase(c.getString("scaling.mode", "DOUBLE"));
        baseMultiplier = Math.max(1, c.getInt("scaling.base-multiplier", 1));
        linearStep = Math.max(1, c.getInt("scaling.linear-step", 1));
        globalMax = Math.max(1, Math.min(256, c.getInt("scaling.max-effect-level", 64)));

        goodBase = Math.max(0, c.getInt("roll.good-base-count", 2));
        goodExtraEvery = c.getInt("roll.good-extra-every-tiers", 2);
        goodMax = Math.max(0, c.getInt("roll.good-max-count", 5));
        riskBase = c.getDouble("roll.risk-base-chance", 0.30);
        riskPerTier = c.getDouble("roll.risk-chance-per-tier", 0.10);
        riskMaxChance = c.getDouble("roll.risk-max-chance", 1.0);
        riskExtraEvery = c.getInt("roll.risk-extra-every-tiers", 3);
        riskMax = Math.max(0, c.getInt("roll.risk-max-count", 3));

        loadPool(c.getConfigurationSection("effects.good"), good, log, "good");
        loadPool(c.getConfigurationSection("effects.bad"), bad, log, "bad");
        log.info("Efek dimuat: " + good.size() + " berkah, " + bad.size() + " risiko.");
    }

    private void loadPool(ConfigurationSection sec, List<Entry> out, Logger log, String label) {
        out.clear();
        if (sec == null) {
            log.warning("Bagian effects." + label + " tidak ada di config.yml");
            return;
        }
        for (String rawKey : sec.getKeys(false)) {
            String key = rawKey.toLowerCase(Locale.ROOT);
            ConfigurationSection e = sec.getConfigurationSection(rawKey);
            if (e == null) {
                log.warning("Format efek '" + rawKey + "' salah. Pakai: {weight: 5, max-level: 3}");
                continue;
            }
            if (typeOf(key) == null) {
                log.warning("Efek tidak dikenal, dilewati: " + key);
                continue;
            }
            int weight = Math.max(1, e.getInt("weight", 5));
            int max = Math.max(1, Math.min(255, e.getInt("max-level", 5)));
            out.add(new Entry(key, weight, max));
        }
    }

    static PotionEffectType typeOf(String key) {
        return PotionEffectType.getByKey(NamespacedKey.minecraft(key));
    }

    // ---------------------------------------------------------------- pengali

    int tierOf(int xpLevel) {
        return Math.max(0, xpLevel) / levelsPerTier;
    }

    int levelsPerTier() {
        return levelsPerTier;
    }

    int levelsToNextTier(int xpLevel) {
        return levelsPerTier - (Math.max(0, xpLevel) % levelsPerTier);
    }

    /** Level 0-4 = x1, tiap naik 5 level dikali 2 (mode DOUBLE): x1, x2, x4, x8, ... */
    int multiplierFor(int tier) {
        long m;
        if (doubleMode) {
            m = (long) baseMultiplier << Math.min(Math.max(tier, 0), 20);
        } else {
            m = baseMultiplier + (long) Math.max(tier, 0) * linearStep;
        }
        return (int) Math.min(m, globalMax);
    }

    String ladder(int steps) {
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < steps; t++) {
            if (t > 0) sb.append(" → ");
            sb.append('x').append(multiplierFor(t));
        }
        return sb.append(" ...").toString();
    }

    int goodCountFor(int tier) {
        int extra = goodExtraEvery > 0 ? tier / goodExtraEvery : 0;
        return Math.min(goodMax, goodBase + extra);
    }

    double riskChanceFor(int tier) {
        return Math.max(0, Math.min(riskMaxChance, riskBase + riskPerTier * tier));
    }

    // ------------------------------------------------------------------ undian

    Roll roll(int xpLevel) {
        int tier = tierOf(xpLevel);
        int mult = multiplierFor(tier);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        int goodCount = goodCountFor(tier);
        int riskCount = (rnd.nextDouble() < riskChanceFor(tier) ? 1 : 0)
                + (riskExtraEvery > 0 ? tier / riskExtraEvery : 0);
        riskCount = Math.min(riskMax, riskCount);

        List<RolledEffect> result = new ArrayList<>();
        for (Entry e : pick(good, goodCount)) {
            result.add(new RolledEffect(e.key(), Math.min(mult, e.maxLevel()), false));
        }
        for (Entry e : pick(bad, riskCount)) {
            result.add(new RolledEffect(e.key(), Math.min(mult, e.maxLevel()), true));
        }
        return new Roll(xpLevel, tier, mult, result);
    }

    /** Undian berbobot tanpa pengembalian. */
    private List<Entry> pick(List<Entry> pool, int count) {
        List<Entry> copy = new ArrayList<>(pool);
        List<Entry> out = new ArrayList<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (out.size() < count && !copy.isEmpty()) {
            int total = 0;
            for (Entry e : copy) total += e.weight();
            int r = rnd.nextInt(total);
            Iterator<Entry> it = copy.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                r -= e.weight();
                if (r < 0) {
                    out.add(e);
                    it.remove();
                    break;
                }
            }
        }
        return out;
    }
}
