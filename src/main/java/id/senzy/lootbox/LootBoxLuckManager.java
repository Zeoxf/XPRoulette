package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.util.RandomUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Mystery Luck": makin banyak LootBox dibuka BERTURUT-TURUT tanpa mendapat hadiah utama
 * (default: rarity LEGENDARY), makin besar peluang rarity tinggi - termasuk peluang Legendary
 * Template - sampai batas plafon (tidak pernah 100%, tetap RNG). Begitu hadiah utama didapat,
 * streak-nya direset ke 0 supaya tidak keterusan OP.
 *
 * <p>Disimpan per-pemain di data/lootbox-luck.yml supaya bertahan lintas restart & session.
 */
public final class LootBoxLuckManager {

    private final SenzyPlugin plugin;
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private boolean dirty;

    private boolean enabled = true;
    private LootBoxRarity resetOn = LootBoxRarity.LEGENDARY;
    private int maxStreak = 40;
    private final Map<LootBoxRarity, Double> boostMultiplier = new EnumMap<>(LootBoxRarity.class);
    private double commonReductionFactor = 0.6;
    private double maxTemplateChance = 0.05;

    public LootBoxLuckManager(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.data().dataDir(), "lootbox-luck.yml");
    }

    public void load() {
        FileConfiguration c = plugin.config().cfg();
        enabled = c.getBoolean("lootbox.luck.enabled", true);
        resetOn = LootBoxRarity.fromId(c.getString("lootbox.luck.reset-on", "legendary"));
        maxStreak = Math.max(1, c.getInt("lootbox.luck.max-streak", 40));
        commonReductionFactor = clamp01(c.getDouble("lootbox.luck.common-reduction-factor", 0.6));
        maxTemplateChance = clamp01(c.getDouble("lootbox.luck.max-template-chance", 0.05));

        boostMultiplier.clear();
        boostMultiplier.put(LootBoxRarity.COMMON, 0.0);
        boostMultiplier.put(LootBoxRarity.RARE, c.getDouble("lootbox.luck.boost-multiplier.rare", 1.0));
        boostMultiplier.put(LootBoxRarity.EPIC, c.getDouble("lootbox.luck.boost-multiplier.epic", 3.0));
        boostMultiplier.put(LootBoxRarity.LEGENDARY, c.getDouble("lootbox.luck.boost-multiplier.legendary", 8.0));

        streaks.clear();
        YamlConfiguration y = plugin.data().readYaml(file());
        if (y != null) {
            var sec = y.getConfigurationSection("players");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        streaks.put(UUID.fromString(key), Math.max(0, sec.getInt(key)));
                    } catch (IllegalArgumentException ignored) {
                        // uuid rusak, dilewati
                    }
                }
            }
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public boolean enabled() {
        return enabled;
    }

    private int streakOf(UUID uuid) {
        return streaks.getOrDefault(uuid, 0);
    }

    /** 0.0 (baru mulai / baru dapat hadiah utama) .. 1.0 (sudah mencapai plafon max-streak). */
    public double luckFactor(UUID uuid) {
        if (!enabled) return 0.0;
        return Math.min(1.0, streakOf(uuid) / (double) maxStreak);
    }

    /**
     * Rarity untuk satu bukaan LootBox oleh {@code uuid}, bobot dasar dinaikkan bertahap sesuai
     * luck pemain itu (RARE/EPIC/LEGENDARY dinaikkan, COMMON diturunkan) - tetap RNG tertimbang,
     * bukan guarantee.
     */
    public LootBoxRarity rollRarity(UUID uuid, Map<LootBoxRarity, Integer> baseWeights) {
        if (!enabled) return weightedPick(baseWeights);
        double factor = luckFactor(uuid);
        if (factor <= 0) return weightedPick(baseWeights);

        Map<LootBoxRarity, Double> adjusted = new EnumMap<>(LootBoxRarity.class);
        for (LootBoxRarity r : LootBoxRarity.values()) {
            double base = Math.max(0, baseWeights.getOrDefault(r, 0));
            if (r == LootBoxRarity.COMMON) {
                adjusted.put(r, base * (1.0 - factor * commonReductionFactor));
            } else {
                double mult = boostMultiplier.getOrDefault(r, 0.0);
                adjusted.put(r, base * (1.0 + factor * mult));
            }
        }
        double total = adjusted.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return weightedPick(baseWeights);
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (LootBoxRarity r : LootBoxRarity.values()) {
            double w = adjusted.get(r);
            if (roll < w) return r;
            roll -= w;
        }
        return LootBoxRarity.LEGENDARY;
    }

    private LootBoxRarity weightedPick(Map<LootBoxRarity, Integer> weights) {
        var r = RandomUtil.weighted(java.util.Arrays.asList(LootBoxRarity.values()), weights::get);
        return r == null ? LootBoxRarity.COMMON : r;
    }

    /** Peluang Legendary Template untuk pemain ini, naik bertahap sesuai luck, dijepit ke plafon. */
    public double templateChance(UUID uuid, double configuredBaseChance) {
        if (!enabled) return configuredBaseChance;
        double factor = luckFactor(uuid);
        double boosted = configuredBaseChance + factor * (maxTemplateChance - configuredBaseChance);
        return Math.max(configuredBaseChance, Math.min(maxTemplateChance, boosted));
    }

    /** Dipanggil setelah rarity SEBENARNYA diketahui (setelah reveal). Reset kalau itu hadiah utama. */
    public void recordOpen(UUID uuid, LootBoxRarity actualRarity) {
        if (!enabled) return;
        if (actualRarity == resetOn) {
            streaks.remove(uuid);
            plugin.debug("LootBox luck: " + uuid + " dapat hadiah utama (" + actualRarity + "), streak direset.");
        } else {
            int next = Math.min(maxStreak, streakOf(uuid) + 1);
            streaks.put(uuid, next);
            plugin.debug("LootBox luck: " + uuid + " streak=" + next + "/" + maxStreak);
        }
        dirty = true;
        save();
    }

    public void save() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : streaks.entrySet()) {
            y.set("players." + e.getKey(), e.getValue());
        }
        plugin.data().writeAsync(file(), y.saveToString(), false);
    }
}
