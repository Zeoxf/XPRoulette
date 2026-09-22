package id.senzy.xpr;

import id.senzy.SenzyPlugin;
import id.senzy.util.EffectUtil;
import id.senzy.util.TextUtil;
import id.senzy.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry definisi boost + semua rumus progression. Tidak menyimpan data pemain.
 * Dibangun ulang penuh saat /senzy reload.
 */
public final class BoostManager {

    private final SenzyPlugin plugin;
    private final Map<String, Boost> boosts = new LinkedHashMap<>();
    private final Map<String, BoostSideEffect> sideEffects = new LinkedHashMap<>();

    private int maxLevel = 30;
    private long durationBase = 600_000L;
    private long durationPerLevel = 60_000L;
    private long cooldownBase = 1_800_000L;
    private long cooldownStep = 60_000L;
    private int cooldownEvery = 2;
    private long cooldownMin = 300_000L;
    private boolean autoAdvance = true;
    private boolean stageByProgression = false;

    public BoostManager(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.config().cfg();
        maxLevel = Math.max(1, c.getInt("xpr.max-level", 30));
        durationBase = plugin.config().duration("xpr.duration.base", 600_000L);
        durationPerLevel = plugin.config().duration("xpr.duration.per-level", 60_000L);
        cooldownBase = plugin.config().duration("xpr.cooldown.base", 1_800_000L);
        cooldownStep = plugin.config().duration("xpr.cooldown.reduction.amount", 60_000L);
        cooldownEvery = Math.max(1, c.getInt("xpr.cooldown.reduction.every-levels", 2));
        cooldownMin = plugin.config().duration("xpr.cooldown.minimum", 300_000L);
        autoAdvance = c.getBoolean("xpr.tiers.auto-advance", true);
        stageByProgression = "PROGRESSION".equalsIgnoreCase(c.getString("xpr.side-effect.stage-source", "TIER"));

        sideEffects.clear();
        ConfigurationSection se = c.getConfigurationSection("side-effects");
        if (se != null) {
            for (String id : se.getKeys(false)) {
                ConfigurationSection sec = se.getConfigurationSection(id);
                if (sec == null) continue;
                BoostSideEffect parsed = parseSideEffect(id.toLowerCase(Locale.ROOT), sec);
                if (parsed != null) sideEffects.put(parsed.id(), parsed);
            }
        }

        boosts.clear();
        ConfigurationSection bs = c.getConfigurationSection("boosts");
        if (bs != null) {
            for (String id : bs.getKeys(false)) {
                ConfigurationSection sec = bs.getConfigurationSection(id);
                if (sec == null) continue;
                Boost b = parseBoost(id.toLowerCase(Locale.ROOT), sec);
                if (b != null) boosts.put(b.id(), b);
            }
        }
        plugin.getLogger().info("XPR: " + boosts.size() + " boost, " + sideEffects.size() + " side effect dimuat.");
    }

    // ------------------------------------------------------------ parsing

    private BoostSideEffect parseSideEffect(String id, ConfigurationSection sec) {
        PotionEffectType type = EffectUtil.potion(sec.getString("type"));
        if (type == null) {
            plugin.getLogger().warning("side-effects." + id + ": type '" + sec.getString("type") + "' tidak dikenal, dilewati.");
            return null;
        }
        long defaultPulse = TimeUtil.parse(sec.getString("pulse-duration"), 5000L);

        List<Map<?, ?>> raw = new ArrayList<>();
        if (sec.isList("stages")) {
            raw.addAll(sec.getMapList("stages"));
        } else {
            ConfigurationSection st = sec.getConfigurationSection("stages");
            if (st != null) {
                List<String> keys = new ArrayList<>(st.getKeys(false));
                keys.sort(Comparator.comparingInt(BoostManager::numericKey));
                for (String k : keys) {
                    ConfigurationSection one = st.getConfigurationSection(k);
                    if (one != null) raw.add(one.getValues(false));
                }
            }
        }

        List<BoostSideEffect.Stage> stages = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            int amp = Math.max(1, intOf(m.get("amplifier"), 1));
            long interval = Math.max(1000L, TimeUtil.parse(strOf(m.get("interval")), 10_000L));
            long dur = TimeUtil.parse(strOf(m.get("duration")), defaultPulse);
            stages.add(new BoostSideEffect.Stage(amp, interval, (int) Math.min(Integer.MAX_VALUE, Math.max(1L, dur / 50L))));
        }
        if (stages.isEmpty()) {
            plugin.getLogger().warning("side-effects." + id + ": tidak punya stage, dilewati.");
            return null;
        }
        return new BoostSideEffect(id, type, stages);
    }

    private Boost parseBoost(String id, ConfigurationSection sec) {
        PotionEffectType effect = EffectUtil.potion(sec.getString("effect"));
        if (effect == null) {
            plugin.getLogger().warning("boosts." + id + ": effect '" + sec.getString("effect") + "' tidak dikenal, boost dilewati.");
            return null;
        }
        String name = sec.getString("display-name", "<white>" + TextUtil.title(id));
        Material icon = Material.matchMaterial(sec.getString("icon", "PAPER"));
        if (icon == null || icon.isAir()) icon = Material.PAPER;
        int weight = Math.max(0, sec.getInt("weight", 10));

        ConfigurationSection ts = sec.getConfigurationSection("tiers");
        List<BoostTier> tiers = new ArrayList<>();
        if (ts != null) {
            List<String> keys = new ArrayList<>(ts.getKeys(false));
            keys.sort(Comparator.comparingInt(BoostManager::numericKey));
            int number = 1;
            for (String k : keys) {
                ConfigurationSection t = ts.getConfigurationSection(k);
                if (t == null) continue;
                long base = TimeUtil.parse(t.getString("base-duration"), durationBase);
                int amp = Math.max(0, t.getInt("effect-amplifier", number - 1));
                String cd = t.getString("base-cooldown");
                long baseCd = cd == null ? -1L : TimeUtil.parse(cd, -1L);
                tiers.add(new BoostTier(number++, base, amp, baseCd));
            }
        }
        if (tiers.isEmpty()) {
            plugin.getLogger().warning("boosts." + id + ": tidak punya tier, boost dilewati.");
            return null;
        }

        BoostSideEffect side = null;
        Object seRaw = sec.get("side-effect");
        if (seRaw instanceof ConfigurationSection inline) {
            side = parseSideEffect(id + "-inline", inline);
        } else if (seRaw instanceof String ref && !ref.isBlank() && !ref.equalsIgnoreCase("none")) {
            side = sideEffects.get(ref.toLowerCase(Locale.ROOT));
            if (side == null) plugin.getLogger().warning("boosts." + id + ": side-effect '" + ref + "' tidak ditemukan.");
        }
        return new Boost(id, name, icon, weight, effect, tiers, side);
    }

    private static int numericKey(String k) {
        try {
            return Integer.parseInt(k.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // pakai default
            }
        }
        return def;
    }

    private static String strOf(Object o) {
        return o == null ? null : o.toString();
    }

    // ------------------------------------------------------------ rumus

    /** duration = tierBaseDuration + (level - 1) * durationPerLevel. */
    public long duration(Boost b, BoostLevel lv) {
        BoostTier t = b.tier(lv.tier());
        return t.baseDurationMillis() + (long) (Math.max(1, lv.level()) - 1) * durationPerLevel;
    }

    /** cooldown = max(minimum, base - floor((level - 1) / every) * amount). */
    public long cooldown(Boost b, BoostLevel lv) {
        BoostTier t = b.tier(lv.tier());
        long base = t.baseCooldownMillis() >= 0 ? t.baseCooldownMillis() : cooldownBase;
        long reductions = (Math.max(1, lv.level()) - 1) / cooldownEvery;
        return Math.max(cooldownMin, base - reductions * cooldownStep);
    }

    /** Stage side effect 1..N, atau 0 jika boost tidak punya side effect. */
    public int sideEffectStage(Boost b, BoostLevel lv) {
        BoostSideEffect se = b.sideEffect();
        if (se == null) return 0;
        int n = se.stageCount();
        if (stageByProgression) {
            long total = (long) b.tierCount() * maxLevel;
            long idx = lv.index(maxLevel);
            int stage = 1 + (int) (((idx - 1) * n) / Math.max(1, total));
            return Math.max(1, Math.min(n, stage));
        }
        return Math.max(1, Math.min(n, lv.tier()));
    }

    // ------------------------------------------------------------ akses

    public Boost get(String id) {
        return id == null ? null : boosts.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Boost> all() {
        return boosts.values();
    }

    public int maxLevel() {
        return maxLevel;
    }

    public boolean autoAdvance() {
        return autoAdvance;
    }
}
