package id.xproulette.lootbox;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Mencari lokasi Loot Box yang AMAN dengan alur:
 *
 * <pre>RANDOM X/Z -> VALIDATE -> RETRY -> FALLBACK -> SUCCESS   (tidak pernah: RANDOM -> SPAWN PAKSA)</pre>
 *
 * <ul>
 *   <li>Y tidak pernah di-random: diambil dari permukaan terrain (HeightMap).</li>
 *   <li>Pemuatan chunk dilakukan asinkron (getChunkAtAsync) dan dibatasi anggaran {@code max-chunk-loads}.</li>
 *   <li>Semua loop dibatasi jumlah percobaan; maksimal {@link #PER_TICK} kandidat diproses per tick.</li>
 *   <li>Aturan kritis (dunia, border, zona terlarang, void, terrain aman, solid, cairan) tidak pernah
 *       dilonggarkan oleh fallback. Yang boleh dilonggarkan: jarak antar loot, jarak dari pemain, radius.</li>
 * </ul>
 *
 * Semua callback berjalan di main thread.
 */
public final class LocationFinder {

    /** Kandidat yang diproses per tick sebelum menyerahkan giliran ke tick berikutnya. */
    private static final int PER_TICK = 150;

    public record SpawnPoint(int x, int y, int z) {
    }

    /**
     * @param points     lokasi valid (Y = posisi block Loot Box)
     * @param requested  jumlah yang diminta
     * @param failed     jumlah Loot Box yang gagal mendapat lokasi
     * @param rejections statistik alasan penolakan
     */
    public record Result(List<SpawnPoint> points, int requested, int failed, Map<String, Integer> rejections) {
    }

    private enum Mode {RANDOM, NEAREST}

    private record Phase(String name, Mode mode, double radiusMult, double lootMult, double playerMult, int attempts) {
    }

    private record Cand(int x, int z, Phase phase) {
    }

    private final JavaPlugin plugin;
    private final LootBoxSettings s;

    public LocationFinder(JavaPlugin plugin, LootBoxSettings settings) {
        this.plugin = plugin;
        this.s = settings;
    }

    /** Titik pusat = koordinat pemain SAAT sesi dimulai (bukan posisi live). */
    public CompletableFuture<Result> find(World world, int centerX, int centerZ) {
        Search search = new Search(world, centerX, centerZ);
        search.run();
        return search.future;
    }

    // ================================================================= pencarian

    private final class Search {

        private final World world;
        private final int cx;
        private final int cz;
        private final CompletableFuture<Result> future = new CompletableFuture<>();
        private final List<SpawnPoint> accepted = new ArrayList<>();
        private final Map<String, Integer> rejections = new LinkedHashMap<>();
        private final List<Phase> phases;
        private final int target = s.count;

        private int failed;
        private int phaseIndex;
        private int attempt;
        private List<int[]> nearest;
        private int nearestPos;
        private int chunkBudget = s.maxChunkLoads;

        Search(World world, int cx, int cz) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.phases = buildPhases();
        }

        void run() {
            try {
                loop();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Error saat mencari lokasi Loot Box", t);
                finish();
            }
        }

        private void loop() {
            int processed = 0;
            while (!future.isDone()) {
                if (accepted.size() + failed >= target) {
                    finish();
                    return;
                }
                // Jangan menghabiskan satu tick penuh: lanjutkan di tick berikutnya.
                if (++processed > PER_TICK) {
                    plugin.getServer().getScheduler().runTask(plugin, this::run);
                    return;
                }

                Cand c = nextCandidate();
                if (c == null) {
                    failed++;
                    plugin.getLogger().warning("Failed to find valid location for Loot Box #"
                            + (accepted.size() + failed) + " (semua percobaan & fallback habis).");
                    resetForNextBox();
                    continue;
                }

                String reject = preChunkChecks(c);          // langkah 2-5 (murah)
                if (reject != null) {
                    reject(c, reject);
                    continue;
                }

                int chunkX = c.x() >> 4;                     // langkah 6: chunk
                int chunkZ = c.z() >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    if (chunkBudget <= 0) {
                        reject(c, "chunk-budget");
                        continue;
                    }
                    chunkBudget--;
                    world.getChunkAtAsync(chunkX, chunkZ, s.generateChunks).whenComplete((chunk, ex) ->
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                if (future.isDone()) return;
                                if (ex != null || chunk == null) {
                                    reject(c, "chunk");
                                } else {
                                    evaluateTerrain(c);
                                }
                                run();
                            }));
                    return; // dilanjutkan oleh callback
                }
                evaluateTerrain(c);
            }
        }

        private void finish() {
            if (!future.isDone()) {
                future.complete(new Result(List.copyOf(accepted), target, failed, rejections));
            }
        }

        private void resetForNextBox() {
            phaseIndex = 0;
            attempt = 0;
            nearest = null;
            nearestPos = 0;
        }

        // ------------------------------------------------------------ kandidat

        /** null = semua fase untuk box ini sudah habis. */
        private Cand nextCandidate() {
            while (phaseIndex < phases.size()) {
                Phase p = phases.get(phaseIndex);
                if (p.mode() == Mode.RANDOM) {
                    if (attempt < p.attempts()) {
                        attempt++;
                        return randomCandidate(p);
                    }
                } else {
                    if (nearest == null) nearest = buildNearest();
                    if (nearestPos < nearest.size()) {
                        int[] xz = nearest.get(nearestPos++);
                        return new Cand(xz[0], xz[1], p);
                    }
                }
                phaseIndex++;
                attempt = 0;
                nearest = null;
                nearestPos = 0;
            }
            return null;
        }

        private Cand randomCandidate(Phase p) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double rx = s.radiusX * p.radiusMult();
            double rz = s.radiusZ * p.radiusMult();
            double ox;
            double oz;
            if (s.circle) {
                double radius = Math.max(rx, rz);
                double angle = r.nextDouble() * Math.PI * 2.0;
                double dist = radius * Math.sqrt(r.nextDouble()); // seragam di dalam lingkaran
                ox = Math.cos(angle) * dist;
                oz = Math.sin(angle) * dist;
            } else {
                ox = (r.nextDouble() * 2.0 - 1.0) * rx;
                oz = (r.nextDouble() * 2.0 - 1.0) * rz;
            }
            return new Cand(cx + (int) Math.round(ox), cz + (int) Math.round(oz), p);
        }

        /** Titik-titik berlapis dari pusat ke luar (cincin persegi berjarak nearest-step). */
        private List<int[]> buildNearest() {
            int step = s.nearestStep;
            int rings = s.nearestRadius / step;
            List<int[]> all = new ArrayList<>();
            all.add(new int[]{cx, cz});
            for (int k = 1; k <= rings; k++) {
                List<int[]> ring = new ArrayList<>();
                int r = k * step;
                for (int i = -k; i <= k; i++) {
                    ring.add(new int[]{cx - r, cz + i * step});
                    ring.add(new int[]{cx + r, cz + i * step});
                }
                for (int i = -k + 1; i <= k - 1; i++) {
                    ring.add(new int[]{cx + i * step, cz - r});
                    ring.add(new int[]{cx + i * step, cz + r});
                }
                Collections.shuffle(ring, ThreadLocalRandom.current());
                all.addAll(ring);
            }
            if (s.circle) {
                long max2 = (long) s.nearestRadius * s.nearestRadius;
                all.removeIf(xz -> {
                    long dx = xz[0] - cx;
                    long dz = xz[1] - cz;
                    return dx * dx + dz * dz > max2;
                });
            }
            return all;
        }

        // ----------------------------------------------------------- validasi

        /** Langkah 2-5: dunia, world border, jarak dari pemain, zona terlarang. */
        private String preChunkChecks(Cand c) {
            Phase p = c.phase();
            if (!s.isWorldAllowed(world)) return "world-not-allowed";

            Location probe = new Location(world, c.x() + 0.5, world.getMinHeight() + 1, c.z() + 0.5);
            if (!world.getWorldBorder().isInside(probe)) return "outside-world-border";

            long dx = c.x() - cx;
            long dz = c.z() - cz;
            long d2 = dx * dx + dz * dz;
            double minP = s.minDistFromPlayer * p.playerMult();
            if (minP > 0 && d2 < minP * minP) return "too-close-to-player";
            if (s.maxDistFromPlayer > 0) {
                double maxP = s.maxDistFromPlayer * p.radiusMult();
                if (d2 > maxP * maxP) return "too-far-from-player";
            }
            if (s.isProtected(world.getName(), c.x(), c.z())) return "protected-zone";
            return null;
        }

        /** Langkah 7-13, dipanggil saat chunk sudah termuat. */
        private void evaluateTerrain(Cand c) {
            int[] outY = new int[1];
            String reason = checkTerrain(c.x(), c.z(), outY);
            if (reason == null) reason = checkLootDistance(c);
            if (reason != null) {
                reject(c, reason);
                return;
            }
            SpawnPoint point = new SpawnPoint(c.x(), outY[0], c.z());
            accepted.add(point);
            if (s.debug) {
                plugin.getLogger().info("Candidate accepted: world=" + world.getName() + " x=" + point.x()
                        + " y=" + point.y() + " z=" + point.z() + " (fase: " + c.phase().name() + ")");
            }
            resetForNextBox();
        }

        private String checkTerrain(int x, int z, int[] outY) {
            int minH = world.getMinHeight();
            int maxH = world.getMaxHeight();

            // 7. permukaan: Y berasal dari terrain, tidak pernah random
            int groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (groundY <= minH + s.voidMargin) return "near-void";

            // 8. rentang Y
            int boxY = groundY + 1;
            if (boxY < s.minY || boxY > s.maxY) return "y-out-of-range";
            if (boxY + 2 >= maxH) return "no-clearance";

            // 9-10. block tanah dan cairan
            Block ground = world.getBlockAt(x, groundY, z);
            Material gt = ground.getType();
            if (gt.isAir()) return "ground-is-air";
            if (gt == Material.LAVA) return "lava";
            if (gt == Material.WATER || isWaterlogged(ground) || ground.isLiquid()) return "water";
            if (!gt.isSolid()) return "ground-not-solid";
            if (Tag.LEAVES.isTagged(gt) || Tag.LOGS.isTagged(gt) || s.isUnsafe(gt)) return "unsafe-ground";

            // 11. ruang: block Loot Box + 1 block di atasnya harus kosong, bukan cairan
            Block box = ground.getRelative(BlockFace.UP);
            Block above = box.getRelative(BlockFace.UP);
            if (!isFree(box) || !isFree(above)) return "no-clearance";

            // tidak di bawah pohon / atap (MOTION_BLOCKING menghitung daun, yang di atas tidak)
            if (world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) > groundY) return "under-canopy";

            // tidak di celah sempit, dan tidak menempel lava
            int solidSides = 0;
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                Block side = box.getRelative(face);
                if (side.getType() == Material.LAVA || side.getRelative(BlockFace.DOWN).getType() == Material.LAVA) {
                    return "lava";
                }
                if (!side.isPassable()) solidSides++;
            }
            if (solidSides >= 3) return "cramped";

            outY[0] = boxY;
            return null;
        }

        /** 12. jarak antar Loot Box (distanceSquared, hanya bidang XZ). */
        private String checkLootDistance(Cand c) {
            double minLoot = s.minDistBetweenLoot * c.phase().lootMult();
            if (minLoot < 1) minLoot = 1; // dua Loot Box tidak boleh di kolom yang sama
            double min2 = minLoot * minLoot;
            for (int i = 0; i < accepted.size(); i++) {
                SpawnPoint o = accepted.get(i);
                long dx = c.x() - o.x();
                long dz = c.z() - o.z();
                if (dx * dx + dz * dz < min2) return "too-close-to-loot#" + (i + 1);
            }
            return null;
        }

        private boolean isFree(Block b) {
            return b.isPassable() && !b.isLiquid() && !isWaterlogged(b) && !s.isUnsafe(b.getType());
        }

        private boolean isWaterlogged(Block b) {
            return b.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
        }

        private void reject(Cand c, String reason) {
            int hash = reason.indexOf('#');
            String key = hash >= 0 ? reason.substring(0, hash) : reason;
            rejections.merge(key, 1, Integer::sum);
            if (s.debug) {
                plugin.getLogger().info("Candidate rejected: " + reason.replace('-', ' ')
                        + " (x=" + c.x() + ", z=" + c.z() + ", fase=" + c.phase().name() + ")");
            }
        }

        // -------------------------------------------------------------- fase

        /**
         * 1) normal (semua aturan)  2) perlebar radius  3) longgarkan jarak antar loot
         * 4) longgarkan jarak dari pemain  5) lokasi valid terdekat.
         */
        private List<Phase> buildPhases() {
            List<Phase> list = new ArrayList<>();
            list.add(new Phase("normal", Mode.RANDOM, 1.0, 1.0, 1.0, s.maxAttempts));
            if (!s.fallbackEnabled) return list;

            double base = Math.max(s.radiusX, s.radiusZ);
            double lastMult = 1.0;
            if (s.expandSearch) {
                for (double factor : s.expansionFactors) {
                    double mult = factor;
                    if (s.fallbackMaxRadius > 0) mult = Math.min(mult, s.fallbackMaxRadius / base);
                    if (mult <= lastMult + 1e-9) continue; // sudah tercapai / melewati batas max-radius
                    list.add(new Phase(String.format(Locale.ROOT, "expand-x%.2f", mult),
                            Mode.RANDOM, mult, 1.0, 1.0, s.attemptsPerStage));
                    lastMult = mult;
                }
            }
            if (s.relaxRules) {
                list.add(new Phase("relax-loot-distance", Mode.RANDOM, lastMult, 0.5, 1.0, s.attemptsPerStage));
                list.add(new Phase("relax-player-distance", Mode.RANDOM, lastMult, 0.5, 0.0, s.attemptsPerStage));
            }
            if (s.nearestRadius > 0) {
                list.add(new Phase("nearest", Mode.NEAREST, 1.0,
                        s.relaxRules ? 0.5 : 1.0, s.relaxRules ? 0.0 : 1.0, 0));
            }
            return list;
        }
    }
}
