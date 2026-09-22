package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.data.DataManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Area spawn LootBox. Lokasi TIDAK acak buta: admin menentukan world, titik pusat (anchor),
 * radius / region. Perubahan lewat command disimpan di data/lootbox-area.yml (menimpa config.yml)
 * sehingga config.yml dan komentarnya tidak pernah ditulis ulang oleh plugin.
 */
public final class LootBoxLocationManager {

    public enum Mode { POINT, RADIUS, REGION }

    /** Snapshot area yang tidak berubah (immutable dari luar; setter membuat salinan). */
    public static final class Area {
        private Mode mode = Mode.RADIUS;
        private String world = "world";
        private int cx;
        private int cy = 64;
        private int cz;
        private int radius = 500;
        private int adjustRadius = 10;
        private int yOffset;
        private int minX = -500;
        private int maxX = 500;
        private int minZ = -500;
        private int maxZ = 500;

        public Mode mode() {
            return mode;
        }

        public String world() {
            return world;
        }

        public int centerX() {
            return mode == Mode.REGION ? (minX + maxX) / 2 : cx;
        }

        public int centerY() {
            return cy;
        }

        public int centerZ() {
            return mode == Mode.REGION ? (minZ + maxZ) / 2 : cz;
        }

        /** Radius area RADIUS (blok). */
        public int radius() {
            return radius;
        }

        /** Radius sebaran di sekitar anchor pada mode POINT (bukan offset Y). */
        public int adjustRadius() {
            return adjustRadius;
        }

        /** Penyesuaian Y terhadap anchor (referensi tinggi mode POINT). Pengaturan terpisah dari radius. */
        public int yOffset() {
            return yOffset;
        }

        public int minX() {
            return minX;
        }

        public int maxX() {
            return maxX;
        }

        public int minZ() {
            return minZ;
        }

        public int maxZ() {
            return maxZ;
        }

        /** Radius efektif untuk tampilan (GUI/status). */
        public int effectiveRadius() {
            return switch (mode) {
                case POINT -> adjustRadius;
                case RADIUS -> radius;
                case REGION -> Math.max(maxX - minX, maxZ - minZ) / 2;
            };
        }

        public boolean contains(int x, int z) {
            return switch (mode) {
                case REGION -> x >= minX && x <= maxX && z >= minZ && z <= maxZ;
                case POINT -> dist2(x, z) <= (long) adjustRadius * adjustRadius;
                case RADIUS -> dist2(x, z) <= (long) radius * radius;
            };
        }

        private long dist2(int x, int z) {
            long dx = x - cx;
            long dz = z - cz;
            return dx * dx + dz * dz;
        }

        /** Titik acak seragam di dalam area (x, z). */
        public int[] randomPoint() {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (mode == Mode.REGION) {
                return new int[]{r.nextInt(minX, maxX + 1), r.nextInt(minZ, maxZ + 1)};
            }
            double rad = mode == Mode.POINT ? adjustRadius : radius;
            double angle = r.nextDouble() * Math.PI * 2;
            double d = rad * Math.sqrt(r.nextDouble());
            return new int[]{(int) Math.round(cx + Math.cos(angle) * d), (int) Math.round(cz + Math.sin(angle) * d)};
        }

        public Area copy() {
            Area a = new Area();
            a.mode = mode;
            a.world = world;
            a.cx = cx;
            a.cy = cy;
            a.cz = cz;
            a.radius = radius;
            a.adjustRadius = adjustRadius;
            a.yOffset = yOffset;
            a.minX = minX;
            a.maxX = maxX;
            a.minZ = minZ;
            a.maxZ = maxZ;
            return a;
        }
    }

    private final SenzyPlugin plugin;
    private volatile Area area = new Area();

    public LootBoxLocationManager(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    public Area area() {
        return area;
    }

    private File overrideFile() {
        return new File(plugin.data().dataDir(), "lootbox-area.yml");
    }

    /** Membaca config.yml, lalu menimpa dengan override admin (jika ada). */
    public void load() {
        Area a = new Area();
        FileConfiguration c = plugin.config().cfg();
        readInto(a, c.getConfigurationSection("lootbox.spawn-area"), c.getConfigurationSection("lootbox.adjust"));
        DataManager dm = plugin.data();
        YamlConfiguration override = dm.readYaml(overrideFile());
        if (override != null) {
            readInto(a, override.getConfigurationSection("spawn-area"), override.getConfigurationSection("adjust"));
        }
        this.area = a;
    }

    private void readInto(Area a, ConfigurationSection sa, ConfigurationSection adj) {
        if (sa != null) {
            try {
                a.mode = Mode.valueOf(sa.getString("mode", a.mode.name()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("lootbox.spawn-area.mode tidak valid, dipakai " + a.mode);
            }
            a.world = sa.getString("world", a.world);
            ConfigurationSection center = sa.getConfigurationSection("center");
            if (center != null) {
                a.cx = center.getInt("x", a.cx);
                a.cy = center.getInt("y", a.cy);
                a.cz = center.getInt("z", a.cz);
            }
            a.radius = Math.max(1, sa.getInt("radius", a.radius));
            ConfigurationSection region = sa.getConfigurationSection("region");
            if (region != null) {
                int x1 = region.getInt("min-x", a.minX);
                int x2 = region.getInt("max-x", a.maxX);
                int z1 = region.getInt("min-z", a.minZ);
                int z2 = region.getInt("max-z", a.maxZ);
                a.minX = Math.min(x1, x2);
                a.maxX = Math.max(x1, x2);
                a.minZ = Math.min(z1, z2);
                a.maxZ = Math.max(z1, z2);
            }
        }
        if (adj != null) {
            a.adjustRadius = Math.max(1, adj.getInt("radius", a.adjustRadius));
            a.yOffset = adj.getInt("y-offset", a.yOffset);
        }
    }

    // ------------------------------------------------------------ perubahan admin

    /** Menyimpan anchor baru dan beralih ke mode POINT. */
    public void setAnchor(String world, int x, int y, int z, boolean switchToPoint) {
        Area a = area.copy();
        a.world = world;
        a.cx = x;
        a.cy = y;
        a.cz = z;
        if (switchToPoint) a.mode = Mode.POINT;
        commit(a);
    }

    public void setRadius(int radius) {
        Area a = area.copy();
        a.radius = Math.max(1, radius);
        a.mode = Mode.RADIUS;
        commit(a);
    }

    public void setAdjustRadius(int radius) {
        Area a = area.copy();
        a.adjustRadius = Math.max(1, radius);
        commit(a);
    }

    public void setYOffset(int offset) {
        Area a = area.copy();
        a.yOffset = offset;
        commit(a);
    }

    public void setMode(Mode mode) {
        Area a = area.copy();
        a.mode = mode;
        commit(a);
    }

    /** Sudut region (1 atau 2). Sudut lain dipertahankan; mode menjadi REGION. */
    public void setRegionCorner(int corner, int x, int z) {
        Area a = area.copy();
        if (corner == 1) {
            a.minX = x;
            a.minZ = z;
        } else {
            a.maxX = x;
            a.maxZ = z;
        }
        int x1 = Math.min(a.minX, a.maxX);
        int x2 = Math.max(a.minX, a.maxX);
        int z1 = Math.min(a.minZ, a.maxZ);
        int z2 = Math.max(a.minZ, a.maxZ);
        a.minX = x1;
        a.maxX = x2;
        a.minZ = z1;
        a.maxZ = z2;
        a.mode = Mode.REGION;
        commit(a);
    }

    /** Menghapus semua override admin; kembali ke nilai config.yml. */
    public void resetToConfig() {
        File f = overrideFile();
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("Tidak bisa menghapus " + f.getName());
        }
        load();
    }

    private void commit(Area a) {
        this.area = a;
        YamlConfiguration y = new YamlConfiguration();
        y.set("spawn-area.mode", a.mode.name());
        y.set("spawn-area.world", a.world);
        y.set("spawn-area.center.x", a.cx);
        y.set("spawn-area.center.y", a.cy);
        y.set("spawn-area.center.z", a.cz);
        y.set("spawn-area.radius", a.radius);
        y.set("spawn-area.region.min-x", a.minX);
        y.set("spawn-area.region.max-x", a.maxX);
        y.set("spawn-area.region.min-z", a.minZ);
        y.set("spawn-area.region.max-z", a.maxZ);
        y.set("adjust.radius", a.adjustRadius);
        y.set("adjust.y-offset", a.yOffset);
        plugin.data().writeAsync(overrideFile(), y.saveToString(), true);
    }
}
