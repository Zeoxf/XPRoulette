package id.xproulette.lootbox;

import id.xproulette.util.TimeParser;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Seluruh pengaturan Loot Box, dibaca dari lootbox.yml. Tidak ada angka yang di-hardcode di logika;
 * nilai default di sini hanya jaring pengaman bila config rusak.
 */
public final class LootBoxSettings {

    /** Daftar pengaman bawaan bila key spawn.unsafe-blocks tidak ada sama sekali di config. */
    private static final List<String> DEFAULT_UNSAFE_BLOCKS = List.of(
            "CACTUS", "MAGMA_BLOCK", "CAMPFIRE", "SOUL_CAMPFIRE", "FIRE", "SOUL_FIRE", "POWDER_SNOW",
            "SWEET_BERRY_BUSH", "WITHER_ROSE", "COBWEB", "HONEY_BLOCK", "POINTED_DRIPSTONE", "BEDROCK");

    /** Area terlarang (spawn server, arena, hub, dll). */
    public record Zone(String world, int minX, int maxX, int minZ, int maxZ) {
    }

    /** Penampilan & bobot satu rarity. */
    public record RarityInfo(int weight, String displayName, Material block) {
    }

    // ---- event
    public final boolean enabled;
    public final long durationSeconds;
    public final long cooldownSeconds;
    public final boolean persistence;
    public final boolean endWhenAllOpened;

    // ---- spawn
    public final int count;
    public final int minimumSuccessful;
    public final boolean circle;
    public final int radiusX;
    public final int radiusZ;
    public final int minDistFromPlayer;
    public final int maxDistFromPlayer;
    public final int minDistBetweenLoot;
    public final int maxAttempts;
    public final int minY;
    public final int maxY;
    public final int voidMargin;
    public final boolean generateChunks;
    public final int maxChunkLoads;
    public final Set<String> allowedWorlds;
    public final List<Zone> zones;
    public final Set<Material> unsafeBlocks;

    // ---- fallback
    public final boolean fallbackEnabled;
    public final boolean expandSearch;
    public final List<Double> expansionFactors;
    public final int fallbackMaxRadius;
    public final int attemptsPerStage;
    public final boolean relaxRules;
    public final int nearestRadius;
    public final int nearestStep;

    // ---- pemain, rarity, loot
    public final String selectionMode;
    private final Map<Rarity, RarityInfo> rarities = new EnumMap<>(Rarity.class);
    private final Map<Rarity, LootTable> tables = new EnumMap<>(Rarity.class);
    private final Map<Rarity, Boolean> announce = new EnumMap<>(Rarity.class);
    public final int minItems;
    public final int maxItems;
    public final boolean allowDuplicates;

    // ---- efek, bossbar, dll
    public final EffectSpec spawnFx;
    public final EffectSpec openFx;
    public final EffectSpec legendaryFx;
    public final EffectSpec ambientFx;
    public final boolean ambientEnabled;
    public final int ambientInterval;
    public final boolean bossbarEnabled;
    public final BossBar.Color bossbarColor;
    public final BossBar.Overlay bossbarOverlay;
    public final boolean revealCoordinates;
    public final boolean debug;

    public LootBoxSettings(ConfigurationSection c, Logger log) {
        // ------------------------------------------------------------- event
        enabled = c.getBoolean("event.enabled", true);
        durationSeconds = Math.max(10, TimeParser.parseSeconds(c.getString("event.duration", "1h"), 3600));
        cooldownSeconds = Math.max(0, TimeParser.parseSeconds(c.getString("event.cooldown", "30m"), 1800));
        persistence = c.getBoolean("event.persistence", true);
        endWhenAllOpened = c.getBoolean("event.end-when-all-opened", true);

        // ------------------------------------------------------------- spawn
        count = Math.max(1, Math.min(50, c.getInt("spawn.count", 5)));
        minimumSuccessful = Math.max(1, Math.min(count, c.getInt("spawn.minimum-successful-spawns", 3)));
        circle = "circle".equalsIgnoreCase(c.getString("spawn.shape", "square"));
        radiusX = Math.max(1, c.getInt("spawn.radius-x", 500));
        radiusZ = Math.max(1, c.getInt("spawn.radius-z", 500));
        minDistFromPlayer = Math.max(0, c.getInt("spawn.min-distance-from-player", 50));
        maxDistFromPlayer = Math.max(0, c.getInt("spawn.max-distance-from-player", 0));
        minDistBetweenLoot = Math.max(0, c.getInt("spawn.minimum-distance-between-loot", 75));
        maxAttempts = Math.max(1, Math.min(1000, c.getInt("spawn.max-location-attempts", 50)));
        minY = c.getInt("spawn.min-y", 50);
        maxY = c.getInt("spawn.max-y", 320);
        voidMargin = Math.max(0, c.getInt("spawn.void-margin", 8));
        generateChunks = c.getBoolean("spawn.generate-chunks", true);
        maxChunkLoads = Math.max(0, c.getInt("spawn.max-chunk-loads", 150));

        if (c.getBoolean("spawn.allow-water", false)) {
            log.warning("spawn.allow-water: true belum didukung (spawn di air belum ada aturannya). Diabaikan, air tetap ditolak.");
        }
        if (c.getBoolean("spawn.allow-lava", false)) {
            log.warning("spawn.allow-lava: true diabaikan. Lava adalah aturan keamanan kritis dan tidak pernah dilonggarkan.");
        }

        Set<String> worlds = new HashSet<>();
        for (String w : c.getStringList("spawn.allowed-worlds")) {
            worlds.add(w.toLowerCase(Locale.ROOT));
        }
        allowedWorlds = Collections.unmodifiableSet(worlds);

        List<Zone> zoneList = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("spawn.protected-zones")) {
            Object w = m.get("world");
            if (w == null) continue;
            int x1 = intOf(m.get("min-x"), 0);
            int x2 = intOf(m.get("max-x"), 0);
            int z1 = intOf(m.get("min-z"), 0);
            int z2 = intOf(m.get("max-z"), 0);
            zoneList.add(new Zone(String.valueOf(w).toLowerCase(Locale.ROOT),
                    Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2)));
        }
        zones = Collections.unmodifiableList(zoneList);

        Set<Material> unsafe = new HashSet<>();
        // Key tidak ada -> pakai daftar bawaan. "unsafe-blocks: []" (sengaja kosong) tetap dihormati.
        List<String> unsafeNames = c.contains("spawn.unsafe-blocks")
                ? c.getStringList("spawn.unsafe-blocks") : DEFAULT_UNSAFE_BLOCKS;
        for (String name : unsafeNames) {
            Material m = Material.matchMaterial(name);
            if (m == null) {
                log.warning("spawn.unsafe-blocks: material tidak dikenal '" + name + "'");
            } else {
                unsafe.add(m);
            }
        }
        unsafeBlocks = Collections.unmodifiableSet(unsafe);

        // ---------------------------------------------------------- fallback
        fallbackEnabled = c.getBoolean("fallback.enabled", true);
        expandSearch = c.getBoolean("fallback.expand-search", true);
        TreeSet<Double> factors = new TreeSet<>();
        ConfigurationSection exp = c.getConfigurationSection("fallback.expansion");
        if (exp != null) {
            for (String k : exp.getKeys(false)) {
                double f = exp.getDouble(k, 1.0);
                if (f > 1.0) factors.add(f);
            }
        }
        expansionFactors = List.copyOf(factors);
        fallbackMaxRadius = Math.max(0, c.getInt("fallback.max-radius", 750));
        attemptsPerStage = Math.max(1, Math.min(1000, c.getInt("fallback.attempts-per-stage", 25)));
        relaxRules = c.getBoolean("fallback.relax-rules", true);
        nearestRadius = Math.max(0, c.getInt("fallback.nearest-search-radius", 500));
        nearestStep = Math.max(8, c.getInt("fallback.nearest-step", 50));

        // ------------------------------------------------ pemain, rarity, loot
        selectionMode = c.getString("player-selection.mode", "random-online-player");

        for (Rarity r : Rarity.values()) {
            String base = "rarity." + r.key();
            int weight = Math.max(0, c.getInt(base + ".weight", 0));
            String name = c.getString(base + ".name", r.name());
            Material block = Material.matchMaterial(c.getString(base + ".block", "CHEST"));
            if (block == null || !block.isBlock() || block.isAir()) {
                log.warning(base + ".block tidak valid, memakai CHEST");
                block = Material.CHEST;
            }
            rarities.put(r, new RarityInfo(weight, name, block));
            announce.put(r, c.getBoolean("announcement." + r.key(), false));
        }

        minItems = Math.max(1, c.getInt("loot.min-items", 1));
        maxItems = Math.max(minItems, c.getInt("loot.max-items", 4));
        allowDuplicates = c.getBoolean("loot.allow-duplicate-items", false);

        for (Rarity r : Rarity.values()) {
            tables.put(r, readTable(c, r, log));
        }

        // ------------------------------------------------------------- lain-lain
        spawnFx = EffectSpec.read(c.getConfigurationSection("effects.spawn"), log, "spawn");
        openFx = EffectSpec.read(c.getConfigurationSection("effects.open"), log, "open");
        legendaryFx = EffectSpec.read(c.getConfigurationSection("effects.legendary"), log, "legendary");
        ambientFx = EffectSpec.read(c.getConfigurationSection("effects.ambient"), log, "ambient");
        ambientEnabled = c.getBoolean("effects.ambient.enabled", true);
        ambientInterval = Math.max(1, c.getInt("effects.ambient.interval-seconds", 3));

        bossbarEnabled = c.getBoolean("bossbar.enabled", true);
        bossbarColor = enumOf(BossBar.Color.class, c.getString("bossbar.color", "YELLOW"), BossBar.Color.YELLOW);
        bossbarOverlay = enumOf(BossBar.Overlay.class, c.getString("bossbar.overlay", "PROGRESS"), BossBar.Overlay.PROGRESS);

        revealCoordinates = c.getBoolean("announcement.reveal-coordinates", false);
        debug = c.getBoolean("debug", false);
    }

    private LootTable readTable(ConfigurationSection c, Rarity r, Logger log) {
        String base = "loot-tables." + r.key();
        List<LootEntry> entries = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList(base + ".items")) {
            Object itemName = m.get("item");
            if (itemName == null) continue;
            Material mat = Material.matchMaterial(String.valueOf(itemName));
            if (mat == null || mat.isAir() || !mat.isItem()) {
                log.warning(base + ": item tidak valid '" + itemName + "', dilewati");
                continue;
            }
            int min = Math.max(1, intOf(m.get("min"), 1));
            int max = Math.max(min, intOf(m.get("max"), min));
            int weight = intOf(m.get("weight"), 10);
            if (weight <= 0) continue;
            entries.add(new LootEntry(mat, min, max, weight));
        }
        if (entries.isEmpty()) {
            log.warning(base + " kosong: Loot Box rarity " + r.name() + " tidak akan memberi apa pun.");
        }
        return new LootTable(Math.max(0, c.getInt(base + ".min-items", 0)),
                Math.max(0, c.getInt(base + ".max-items", 0)), List.copyOf(entries));
    }

    // ------------------------------------------------------------------ query

    public RarityInfo rarity(Rarity r) {
        return rarities.get(r);
    }

    public int rarityWeight(Rarity r) {
        return rarities.get(r).weight();
    }

    public String rarityName(Rarity r) {
        return rarities.get(r).displayName();
    }

    public Material blockFor(Rarity r) {
        return rarities.get(r).block();
    }

    public LootTable table(Rarity r) {
        return tables.get(r);
    }

    public boolean shouldAnnounce(Rarity r) {
        return announce.getOrDefault(r, false);
    }

    /** Dunia harus ada di allowed-worlds (kosong = semua) dan bukan Nether (deteksi permukaan tidak berlaku). */
    public boolean isWorldAllowed(World w) {
        if (w == null) return false;
        if (w.getEnvironment() == World.Environment.NETHER) return false;
        return allowedWorlds.isEmpty() || allowedWorlds.contains(w.getName().toLowerCase(Locale.ROOT));
    }

    public boolean isProtected(String worldName, int x, int z) {
        String w = worldName.toLowerCase(Locale.ROOT);
        for (Zone zone : zones) {
            if (zone.world().equals(w) && x >= zone.minX() && x <= zone.maxX()
                    && z >= zone.minZ() && z <= zone.maxZ()) {
                return true;
            }
        }
        return false;
    }

    public boolean isUnsafe(Material m) {
        return unsafeBlocks.contains(m);
    }

    // ---------------------------------------------------------------- helper

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
                // pakai default
            }
        }
        return def;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E def) {
        if (name == null) return def;
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }
}
