package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.lootbox.LootBoxLocationManager.Area;
import id.senzy.util.LocationUtil;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Mencari lokasi LootBox yang VALID. Tidak pernah memindai dunia: setiap pencarian dibatasi
 * (max-attempts per LootBox, budget chunk load) dan chunk dimuat async satu per satu.
 *
 * <p>Tahap fallback bila kandidat acak gagal:
 * RETRY (acak di area) -> NEAREST (titik terdekat dari pusat area) -> ALTERNATIVE (acak lagi dengan jarak antar-box dilonggarkan).
 * Aturan kritis (world, border, forbidden zone, liquid, void, tabrakan blok padat) TIDAK PERNAH dilonggarkan.
 * Jika semua tahap gagal, LootBox itu tidak di-spawn (tidak ada lokasi palsu).
 *
 * <p>Titik (x, z) yang dikembalikan adalah posisi BEACON; LootBox berada satu blok di timurnya.
 * Struktur: alas 3x3 di y-1 (x-1..x+1, z-1..z+1), beacon di (x, y, z), kaca berwarna di (x, y+1, z), LootBox di (x+1, y, z).
 */
public final class LootBoxSpawner {

    public record SpawnPoint(World world, int x, int y, int z, boolean beam) {}

    public enum Rejection {
        WORLD, ENVIRONMENT, OUTSIDE_AREA, BORDER, FORBIDDEN_ZONE, CHUNK_NOT_LOADED, CHUNK_BUDGET, Y_RANGE, VOID,
        LIQUID, NOT_SOLID, UNSAFE_GROUND, TILE_ENTITY, NO_SPACE, TOO_CLOSE, BEAM_BLOCKED
    }

    private record Verdict(Rejection rejection, int y, boolean beam) {
        static Verdict reject(Rejection r, int y) {
            return new Verdict(r, y, false);
        }

        static Verdict ok(int y, boolean beam) {
            return new Verdict(null, y, beam);
        }
    }

    private record Zone(String world, int minX, int maxX, int minZ, int maxZ) {}

    private enum Stage { RETRY, NEAREST, ALTERNATIVE }

    /** Nilai config yang dibaca sekali per pencarian. */
    private static final class Settings {
        int maxAttempts;
        int headroom;
        int minY;
        int maxY;
        int voidMargin;
        int borderMargin;
        int maxChunkLoads;
        int yRange;
        double minDistance;
        double pointMinDistance;
        boolean generate;
        boolean requireBeam;
        boolean clearance;
        boolean beamEnabled;
        boolean fallback;
        int nearestRadius;
        int nearestStep;
        int nearestAttempts;
        int alternativeAttempts;
        double relaxFactor;
        Set<World.Environment> environments = EnumSet.noneOf(World.Environment.class);
        Set<Material> unsafe = EnumSet.noneOf(Material.class);
        List<Zone> zones = new ArrayList<>();
    }

    private final class Search {
        final World world;
        final Area area;
        final Settings cfg;
        final int amount;
        final List<SpawnPoint> existing;
        final List<SpawnPoint> points = new ArrayList<>();
        final CompletableFuture<List<SpawnPoint>> result = new CompletableFuture<>();
        final int generation;
        int box;
        Stage stage = Stage.RETRY;
        int left;
        int chunkLoads;
        List<int[]> nearest;
        int nearestIdx;

        Search(World world, Area area, Settings cfg, int amount, List<SpawnPoint> existing, int generation) {
            this.world = world;
            this.area = area;
            this.cfg = cfg;
            this.amount = amount;
            this.existing = existing;
            this.generation = generation;
            this.left = cfg.maxAttempts;
        }

        boolean done() {
            return box >= amount;
        }

        boolean relaxed() {
            return stage == Stage.ALTERNATIVE;
        }

        /** Kandidat berikutnya untuk LootBox saat ini, atau null jika semua tahap habis. */
        int[] next() {
            while (true) {
                switch (stage) {
                    case RETRY -> {
                        if (left > 0) {
                            left--;
                            return clampToChunk(area.randomPoint());
                        }
                    }
                    case NEAREST -> {
                        if (nearest != null && nearestIdx < nearest.size()) return nearest.get(nearestIdx++);
                    }
                    case ALTERNATIVE -> {
                        if (left > 0) {
                            left--;
                            return clampToChunk(area.randomPoint());
                        }
                    }
                }
                if (!advanceStage()) return null;
            }
        }

        private boolean advanceStage() {
            if (!cfg.fallback) return false;
            if (stage == Stage.RETRY) {
                stage = Stage.NEAREST;
                nearest = buildNearest(this);
                nearestIdx = 0;
                return true;
            }
            if (stage == Stage.NEAREST) {
                stage = Stage.ALTERNATIVE;
                left = cfg.alternativeAttempts;
                return true;
            }
            return false;
        }

        void accept(SpawnPoint p) {
            points.add(p);
            box++;
            resetStage();
        }

        void failBox() {
            box++;
            resetStage();
        }

        private void resetStage() {
            stage = Stage.RETRY;
            left = cfg.maxAttempts;
            nearest = null;
            nearestIdx = 0;
        }
    }

    private final SenzyPlugin plugin;
    private volatile int generation;

    public LootBoxSpawner(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Membatalkan semua pencarian yang sedang berjalan (saat plugin mati / reset). */
    public void cancelAll() {
        generation++;
    }

    // ------------------------------------------------------------ API

    /**
     * Mencari sampai {@code amount} lokasi valid. Future selesai di main thread.
     * {@code existing} = titik yang sudah ada (dipakai hanya untuk cek jarak).
     */
    public CompletableFuture<List<SpawnPoint>> findLocations(World world, Area area, int amount, List<SpawnPoint> existing) {
        Search s = new Search(world, area, readSettings(), Math.max(0, amount), existing, generation);
        step(s);
        return s.result;
    }

    /** Validasi ulang tepat sebelum membangun (chunk sudah dimuat). Null jika tidak valid lagi. */
    public SpawnPoint revalidate(World world, Area area, int x, int z, List<SpawnPoint> chosen) {
        Settings cfg = readSettings();
        Verdict v = validate(world, area, cfg, x, z, chosen, 1.0);
        if (v.rejection() != null) {
            plugin.debug("LootBox spawn rejected (revalidate): reason=" + v.rejection() + " location="
                    + world.getName() + ":" + x + "," + v.y() + "," + z);
            return null;
        }
        return new SpawnPoint(world, x, v.y(), z, v.beam());
    }

    // ------------------------------------------------------------ pencarian

    private void step(Search s) {
        int guard = 0;
        while (!s.done()) {
            if (s.generation != generation) {
                s.result.complete(s.points);
                return;
            }
            if (++guard > 40) { // jangan menahan tick terlalu lama
                Bukkit.getScheduler().runTask(plugin, () -> step(s));
                return;
            }
            int[] c = s.next();
            if (c == null) {
                plugin.debug("LootBox #" + (s.box + 1) + ": semua tahap pencarian habis, tidak di-spawn.");
                s.failBox();
                continue;
            }
            int cx = c[0] >> 4;
            int cz = c[1] >> 4;
            if (s.world.isChunkLoaded(cx, cz)) {
                evaluate(s, c);
                continue;
            }
            if (s.chunkLoads >= s.cfg.maxChunkLoads) {
                logReject(s, c, Rejection.CHUNK_BUDGET, Integer.MIN_VALUE);
                continue;
            }
            s.chunkLoads++;
            s.world.getChunkAtAsync(cx, cz, s.cfg.generate).whenComplete((chunk, err) -> runMain(() -> {
                if (s.generation != generation) {
                    s.result.complete(s.points);
                    return;
                }
                if (chunk == null || err != null) {
                    logReject(s, c, Rejection.CHUNK_NOT_LOADED, Integer.MIN_VALUE);
                } else {
                    evaluate(s, c);
                }
                step(s);
            }));
            return;
        }
        s.result.complete(s.points);
    }

    private void evaluate(Search s, int[] c) {
        List<SpawnPoint> chosen = new ArrayList<>(s.existing);
        chosen.addAll(s.points);
        Verdict v = validate(s.world, s.area, s.cfg, c[0], c[1], chosen, s.relaxed() ? s.cfg.relaxFactor : 1.0);
        if (v.rejection() != null) {
            logReject(s, c, v.rejection(), v.y());
            return;
        }
        plugin.debug("LootBox spawn accepted: location=" + s.world.getName() + ":" + c[0] + "," + v.y() + "," + c[1]
                + " stage=" + s.stage + " beam=" + v.beam());
        s.accept(new SpawnPoint(s.world, c[0], v.y(), c[1], v.beam()));
    }

    private void logReject(Search s, int[] c, Rejection r, int y) {
        plugin.debug("LootBox spawn rejected: reason=" + r + " location=" + s.world.getName() + ":" + c[0] + ","
                + (y == Integer.MIN_VALUE ? "?" : String.valueOf(y)) + "," + c[1] + " stage=" + s.stage);
    }

    private void runMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, r);
            } catch (RuntimeException e) {
                // plugin sudah dimatikan
            }
        }
    }

    /** Menjaga seluruh alas 3x3 berada di satu chunk (x-1..x+1 dan z-1..z+1) agar tidak memicu chunk load liar. */
    private static int[] clampToChunk(int[] p) {
        int lx = Math.max(1, Math.min(14, p[0] & 15));
        int lz = Math.max(1, Math.min(14, p[1] & 15));
        return new int[]{(p[0] & ~15) + lx, (p[1] & ~15) + lz};
    }

    private List<int[]> buildNearest(Search s) {
        int cx = s.area.centerX();
        int cz = s.area.centerZ();
        int step = Math.max(2, s.cfg.nearestStep);
        int radius = Math.max(step, s.cfg.nearestRadius);
        List<int[]> all = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int[] p = clampToChunk(new int[]{cx + dx, cz + dz});
                if (s.area.contains(p[0], p[1])) all.add(p);
            }
        }
        all.sort(Comparator.comparingLong(p -> {
            long ddx = p[0] - cx;
            long ddz = p[1] - cz;
            return ddx * ddx + ddz * ddz;
        }));
        int limit = Math.max(0, s.cfg.nearestAttempts);
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    // ------------------------------------------------------------ validasi

    private Verdict validate(World w, Area area, Settings cfg, int bx, int bz, List<SpawnPoint> chosen, double distanceFactor) {
        if (w == null) return Verdict.reject(Rejection.WORLD, 0);
        if (!cfg.environments.contains(w.getEnvironment())) return Verdict.reject(Rejection.ENVIRONMENT, 0);
        if (!area.contains(bx, bz)) return Verdict.reject(Rejection.OUTSIDE_AREA, 0);
        if (!insideBorder(w, bx - 1, bz - 1, cfg.borderMargin) || !insideBorder(w, bx + 1, bz + 1, cfg.borderMargin)) {
            return Verdict.reject(Rejection.BORDER, 0);
        }
        for (Zone z : cfg.zones) {
            if (z.world().equalsIgnoreCase(w.getName())
                    && bx + 1 >= z.minX() && bx - 1 <= z.maxX() && bz + 1 >= z.minZ() && bz - 1 <= z.maxZ()) {
                return Verdict.reject(Rejection.FORBIDDEN_ZONE, 0);
            }
        }
        // Alas 3x3 harus berada dalam satu chunk yang sudah dimuat.
        if (((bx - 1) >> 4) != ((bx + 1) >> 4) || ((bz - 1) >> 4) != ((bz + 1) >> 4)
                || !w.isChunkLoaded(bx >> 4, bz >> 4)) {
            return Verdict.reject(Rejection.CHUNK_NOT_LOADED, 0);
        }

        int surface = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int y = surface + 1;
        if (surface <= w.getMinHeight() + cfg.voidMargin) return Verdict.reject(Rejection.VOID, y);
        if (y < cfg.minY || y > cfg.maxY) return Verdict.reject(Rejection.Y_RANGE, y);
        if (area.mode() == LootBoxLocationManager.Mode.POINT && cfg.yRange > 0) {
            int ref = area.centerY() + area.yOffset();
            if (Math.abs(surface - ref) > cfg.yRange) return Verdict.reject(Rejection.Y_RANGE, y);
        }
        if (y + cfg.headroom + 2 >= w.getMaxHeight()) return Verdict.reject(Rejection.NO_SPACE, y);

        // Alas 3x3: padat, bukan liquid, bukan blok berbahaya / tile entity.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Rejection r = checkGround(w.getBlockAt(bx + dx, surface, bz + dz), cfg);
                if (r != null) return Verdict.reject(r, y);
            }
        }
        // Ruang untuk beacon (bx) dan LootBox (bx+1): kosong / bisa diganti, tanpa liquid.
        for (int x = bx; x <= bx + 1; x++) {
            for (int h = 0; h <= cfg.headroom; h++) {
                Block b = w.getBlockAt(x, y + h, bz);
                if (b.isLiquid()) return Verdict.reject(Rejection.LIQUID, y);
                if (!(b.getType().isAir() || b.isReplaceable())) return Verdict.reject(Rejection.NO_SPACE, y);
            }
        }
        // Jarak antar LootBox.
        double minDist = (area.mode() == LootBoxLocationManager.Mode.POINT ? cfg.pointMinDistance : cfg.minDistance) * distanceFactor;
        for (SpawnPoint p : chosen) {
            if (p.world().getUID().equals(w.getUID())
                    && LocationUtil.distance2D(p.x(), p.z(), bx, bz) < minDist) {
                return Verdict.reject(Rejection.TOO_CLOSE, y);
            }
        }
        // Ketinggian bebas untuk beam beacon (blok opak di atas menghalangi beam).
        boolean beam = cfg.beamEnabled;
        if (beam && cfg.clearance) {
            for (int yy = y + cfg.headroom + 1; yy < w.getMaxHeight(); yy++) {
                Material m = w.getBlockAt(bx, yy, bz).getType();
                if (!m.isAir() && m.isOccluding() && m != Material.BEDROCK) {
                    beam = false;
                    break;
                }
            }
            if (!beam && cfg.requireBeam) return Verdict.reject(Rejection.BEAM_BLOCKED, y);
        }
        return Verdict.ok(y, beam);
    }

    private Rejection checkGround(Block g, Settings cfg) {
        Material m = g.getType();
        if (g.isLiquid() || m == Material.WATER || m == Material.LAVA) return Rejection.LIQUID;
        if (!m.isSolid()) return Rejection.NOT_SOLID;
        if (cfg.unsafe.contains(m) || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m)) return Rejection.UNSAFE_GROUND;
        if (g.getState() instanceof TileState) return Rejection.TILE_ENTITY;
        return null;
    }

    private boolean insideBorder(World w, int x, int z, int margin) {
        WorldBorder border = w.getWorldBorder();
        Location c = border.getCenter();
        double half = border.getSize() / 2.0 - margin;
        return Math.abs(x + 0.5 - c.getX()) <= half && Math.abs(z + 0.5 - c.getZ()) <= half;
    }

    // ------------------------------------------------------------ config

    private Settings readSettings() {
        FileConfiguration c = plugin.config().cfg();
        Settings s = new Settings();
        s.maxAttempts = Math.max(1, c.getInt("lootbox.spawn.max-attempts", 50));
        s.headroom = Math.max(1, c.getInt("lootbox.spawn.headroom", 2));
        s.minY = c.getInt("lootbox.spawn.min-y", 40);
        s.maxY = c.getInt("lootbox.spawn.max-y", 300);
        s.voidMargin = Math.max(0, c.getInt("lootbox.spawn.void-margin", 8));
        s.borderMargin = Math.max(0, c.getInt("lootbox.spawn.border-margin", 16));
        s.maxChunkLoads = Math.max(1, c.getInt("lootbox.spawn.max-chunk-loads", 150));
        s.yRange = Math.max(0, c.getInt("lootbox.adjust.y-range", 16));
        s.minDistance = Math.max(0, c.getDouble("lootbox.spawn.minimum-distance-between", 75));
        s.pointMinDistance = Math.max(0, c.getDouble("lootbox.spawn.point-minimum-distance", 4));
        s.generate = c.getBoolean("lootbox.spawn.generate-chunks", true);
        s.requireBeam = c.getBoolean("lootbox.beacon.require-beam", false);
        s.clearance = c.getBoolean("lootbox.beacon.height-clearance.check", true);
        s.beamEnabled = c.getBoolean("lootbox.beacon.enabled", true) && c.getBoolean("lootbox.beacon.beam.enabled", true);
        s.fallback = c.getBoolean("lootbox.fallback.enabled", true);
        s.nearestRadius = c.getInt("lootbox.fallback.nearest-radius", 200);
        s.nearestStep = c.getInt("lootbox.fallback.nearest-step", 16);
        s.nearestAttempts = c.getInt("lootbox.fallback.nearest-attempts", 30);
        s.alternativeAttempts = Math.max(0, c.getInt("lootbox.fallback.alternative-attempts", 25));
        s.relaxFactor = Math.max(0.0, Math.min(1.0, c.getDouble("lootbox.fallback.relax-distance-factor", 0.5)));

        for (String name : c.getStringList("lootbox.spawn.allowed-environments")) {
            try {
                s.environments.add(World.Environment.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("lootbox.spawn.allowed-environments: '" + name + "' tidak dikenal.");
            }
        }
        if (s.environments.isEmpty()) s.environments.add(World.Environment.NORMAL);

        for (String name : c.getStringList("lootbox.spawn.unsafe-blocks")) {
            Material m = Material.matchMaterial(name);
            if (m != null) s.unsafe.add(m);
        }
        for (Map<?, ?> map : c.getMapList("lootbox.spawn.forbidden-zones")) {
            Object world = map.get("world");
            if (world == null) continue;
            int x1 = num(map.get("min-x"));
            int x2 = num(map.get("max-x"));
            int z1 = num(map.get("min-z"));
            int z2 = num(map.get("max-z"));
            s.zones.add(new Zone(world.toString(), Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2)));
        }
        return s;
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
