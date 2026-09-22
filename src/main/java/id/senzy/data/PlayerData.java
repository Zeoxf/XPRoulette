package id.senzy.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Data XPR satu pemain. Hanya diakses dari main thread.
 *
 * <pre>
 * boosts:
 *   mining:
 *     unlocked: true
 *     tier: 2
 *     level: 17
 *     cooldown-until: 0
 * </pre>
 */
public final class PlayerData {

    /** Progres satu boost. Boost baru tidak ada di map sampai pernah didapat. */
    public static final class BoostState {
        public boolean unlocked;
        public int tier = 1;
        public int level = 1;
        /** Epoch millis; boost tidak boleh diaktifkan sebelum waktu ini. */
        public long cooldownUntil;
        /** Berapa kali RNG memberikan boost ini (statistik). */
        public int rolls;
    }

    private final UUID uuid;
    private String name;
    private final Map<String, BoostState> boosts = new LinkedHashMap<>();
    private String activeBoost;
    private long activeExpiration;
    /** Epoch millis; anti-spam roll, disimpan supaya tidak bisa dilewati dengan relog. */
    private long rollLockUntil;
    private boolean dirty;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.equals(this.name)) {
            this.name = name;
            dirty = true;
        }
    }

    public Map<String, BoostState> boosts() {
        return Collections.unmodifiableMap(boosts);
    }

    /** State boost, atau null jika belum pernah dimiliki. */
    public BoostState state(String boostId) {
        return boosts.get(boostId);
    }

    public BoostState stateOrCreate(String boostId) {
        return boosts.computeIfAbsent(boostId, k -> new BoostState());
    }

    public boolean owns(String boostId) {
        BoostState s = boosts.get(boostId);
        return s != null && s.unlocked;
    }

    public String activeBoost() {
        return activeBoost;
    }

    public long activeExpiration() {
        return activeExpiration;
    }

    public void setActive(String boostId, long expiration) {
        this.activeBoost = boostId;
        this.activeExpiration = expiration;
        this.dirty = true;
    }

    public long rollLockUntil() {
        return rollLockUntil;
    }

    public void setRollLockUntil(long value) {
        this.rollLockUntil = value;
        this.dirty = true;
    }

    public void clearBoosts() {
        boosts.clear();
        activeBoost = null;
        activeExpiration = 0;
        rollLockUntil = 0;
        dirty = true;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    public YamlConfiguration toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("version", 1);
        y.set("uuid", uuid.toString());
        y.set("name", name);
        y.set("active-boost", activeBoost);
        y.set("active-expiration", activeExpiration);
        y.set("roll-lock-until", rollLockUntil);
        for (Map.Entry<String, BoostState> e : boosts.entrySet()) {
            String base = "boosts." + e.getKey();
            BoostState s = e.getValue();
            y.set(base + ".unlocked", s.unlocked);
            y.set(base + ".tier", s.tier);
            y.set(base + ".level", s.level);
            y.set(base + ".cooldown-until", s.cooldownUntil);
            y.set(base + ".rolls", s.rolls);
        }
        return y;
    }

    public static PlayerData fromYaml(UUID uuid, String fallbackName, YamlConfiguration y) {
        PlayerData d = new PlayerData(uuid, y.getString("name", fallbackName));
        String active = y.getString("active-boost");
        d.activeBoost = (active == null || active.isBlank()) ? null : active;
        d.activeExpiration = y.getLong("active-expiration", 0L);
        d.rollLockUntil = y.getLong("roll-lock-until", 0L);
        ConfigurationSection sec = y.getConfigurationSection("boosts");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection b = sec.getConfigurationSection(id);
                if (b == null) continue;
                BoostState s = new BoostState();
                s.unlocked = b.getBoolean("unlocked", false);
                s.tier = Math.max(1, b.getInt("tier", 1));
                s.level = Math.max(1, b.getInt("level", 1));
                s.cooldownUntil = b.getLong("cooldown-until", 0L);
                s.rolls = b.getInt("rolls", 0);
                d.boosts.put(id, s);
            }
        }
        return d;
    }
}
