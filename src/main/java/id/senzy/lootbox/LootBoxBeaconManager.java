package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.util.EffectUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Penanda visual LootBox. Beacon di sini MURNI visual: tidak ada efek beacon (Speed/Haste/dll) yang diberikan
 * karena beacon baru tidak punya primary effect, dan event efek beacon untuk blok ini dibatalkan listener.
 *
 * <p>Struktur sementara (semua blok asli dicatat SEBELUM diubah dan dipulihkan saat box dibuka/session berakhir):
 * <pre>
 *   y+1   kaca berwarna (mewarnai beam sesuai rarity)
 *   y     beacon (x)  +  LootBox (x+1)
 *   y-1   alas 3x3 (piramida minimal agar beacon aktif)
 * </pre>
 * Jika area di atas tertutup, tidak ada beacon: hanya LootBox + hologram + particle marker.
 */
public final class LootBoxBeaconManager {

    public record Placement(int x, int y, int z, Material material) {}

    private final SenzyPlugin plugin;
    private final Map<String, List<ArmorStand>> holograms = new HashMap<>();
    private BukkitTask markerTask;

    private Material baseMaterial = Material.IRON_BLOCK;
    private final Map<LootBoxRarity, DyeColor> colors = new EnumMap<>(LootBoxRarity.class);
    private final Map<LootBoxRarity, Material> appearance = new EnumMap<>(LootBoxRarity.class);
    private boolean hologramEnabled = true;
    private double hologramHeight = 1.4;
    private boolean particlesEnabled = true;
    private int particleInterval = 10;
    private double viewDistance = 64;
    private int ringPoints = 10;
    private Particle sparkle = Particle.END_ROD;

    public LootBoxBeaconManager(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        FileConfiguration c = plugin.config().cfg();
        Material base = Material.matchMaterial(c.getString("lootbox.beacon.base-material", "IRON_BLOCK"));
        baseMaterial = (base != null && base.isBlock()) ? base : Material.IRON_BLOCK;

        String[] defaultColors = {"WHITE", "BLUE", "PURPLE", "YELLOW"};
        Material[] defaultBlocks = {Material.BARREL, Material.CHEST, Material.ENDER_CHEST, Material.SHULKER_BOX};
        for (LootBoxRarity r : LootBoxRarity.values()) {
            DyeColor color;
            try {
                color = DyeColor.valueOf(c.getString("lootbox.beacon.colors." + r.id(), defaultColors[r.ordinal()])
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                color = DyeColor.WHITE;
            }
            colors.put(r, color);
            Material m = Material.matchMaterial(c.getString("lootbox.appearance." + r.id(), defaultBlocks[r.ordinal()].name()));
            appearance.put(r, (m != null && m.isBlock() && !m.isAir()) ? m : defaultBlocks[r.ordinal()]);
        }

        hologramEnabled = c.getBoolean("lootbox.hologram.enabled", true);
        hologramHeight = c.getDouble("lootbox.hologram.height", 1.4);
        particlesEnabled = c.getBoolean("lootbox.beacon.particles.enabled", true);
        particleInterval = Math.max(2, c.getInt("lootbox.beacon.particles.interval", 10));
        viewDistance = Math.max(8, c.getDouble("lootbox.beacon.particles.view-distance", 64));
        ringPoints = Math.max(3, Math.min(32, c.getInt("lootbox.beacon.particles.ring-points", 10)));
        Particle p = EffectUtil.particle(c.getString("lootbox.beacon.particles.type", "END_ROD"));
        sparkle = p == null ? Particle.END_ROD : p;
    }

    // ------------------------------------------------------------ struktur

    /** Rencana blok yang akan diubah untuk sebuah LootBox. */
    public List<Placement> plan(LootBox box) {
        List<Placement> list = new ArrayList<>();
        int bx = box.beaconX();
        int y = box.y();
        int z = box.z();
        if (box.beamActive()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    list.add(new Placement(bx + dx, y - 1, z + dz, baseMaterial));
                }
            }
            list.add(new Placement(bx, y, z, Material.BEACON));
            list.add(new Placement(bx, y + 1, z, glassFor(box.rarity())));
        }
        list.add(new Placement(box.x(), y, z, appearance.get(box.rarity())));
        return list;
    }

    private Material glassFor(LootBoxRarity rarity) {
        Material m = Material.matchMaterial(colors.get(rarity).name() + "_STAINED_GLASS");
        return m == null ? Material.GLASS : m;
    }

    /** Mencatat blok asli lalu membangun struktur, hologram. Dipanggil sekali saat LootBox dibuat. */
    public void build(LootBox box, World world) {
        List<Placement> plan = plan(box);
        box.originals().clear();
        for (Placement p : plan) {
            Block b = world.getBlockAt(p.x(), p.y(), p.z());
            box.originals().add(new LootBox.OriginalBlock(p.x(), p.y(), p.z(), b.getBlockData().getAsString()));
        }
        place(plan, world);
        spawnHologram(box, world);
        plugin.debug("Beacon build: box=" + box.id() + " rarity=" + box.rarity() + " beam=" + box.beamActive()
                + " at " + world.getName() + ":" + box.x() + "," + box.y() + "," + box.z());
    }

    /** Memastikan struktur ada (setelah restart / chunk regenerasi). Tidak mengubah catatan blok asli. */
    public boolean ensure(LootBox box) {
        World world = Bukkit.getWorld(box.worldName());
        if (world == null) return false;
        place(plan(box), world);
        spawnHologram(box, world);
        return true;
    }

    private void place(List<Placement> plan, World world) {
        for (Placement p : plan) {
            Block b = world.getBlockAt(p.x(), p.y(), p.z());
            if (b.getType() != p.material()) b.setType(p.material(), false);
        }
    }

    /** Menghapus hologram dan memulihkan blok asli (urutan terbalik). False jika world belum termuat. */
    public boolean cleanup(LootBox box) {
        removeHologram(box.id());
        World world = Bukkit.getWorld(box.worldName());
        if (world == null) return false;
        List<LootBox.OriginalBlock> list = box.originals();
        for (int i = list.size() - 1; i >= 0; i--) {
            LootBox.OriginalBlock o = list.get(i);
            Block b = world.getBlockAt(o.x(), o.y(), o.z());
            try {
                b.setBlockData(Bukkit.createBlockData(o.blockData()), false);
            } catch (IllegalArgumentException e) {
                b.setType(Material.AIR, false);
            }
        }
        plugin.debug("Beacon cleanup: box=" + box.id() + " restored " + list.size() + " block(s)");
        return true;
    }

    // ------------------------------------------------------------ hologram

    private void spawnHologram(LootBox box, World world) {
        removeHologram(box.id());
        if (!hologramEnabled || !world.isChunkLoaded(box.x() >> 4, box.z() >> 4)) return;
        List<Component> lines = plugin.messages().list("lootbox.hologram.lines",
                "rarity", plugin.messages().raw("rarity." + box.rarity().id()));
        List<ArmorStand> stands = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final Component line = lines.get(i);
            Location at = new Location(world, box.x() + 0.5, box.y() + hologramHeight - i * 0.28, box.z() + 0.5);
            ArmorStand stand = world.spawn(at, ArmorStand.class, a -> {
                a.setInvisible(true);
                a.setMarker(true);
                a.setGravity(false);
                a.setSmall(true);
                a.setInvulnerable(true);
                a.setSilent(true);
                a.setBasePlate(false);
                a.setPersistent(false); // tidak disimpan ke disk; dibuat ulang dari state saat perlu
                a.customName(line);
                a.setCustomNameVisible(true);
            });
            stands.add(stand);
        }
        holograms.put(box.id(), stands);
    }

    public void removeHologram(String boxId) {
        List<ArmorStand> stands = holograms.remove(boxId);
        if (stands == null) return;
        for (ArmorStand s : stands) {
            if (s != null) s.remove();
        }
    }

    // ------------------------------------------------------------ marker (particle + kesehatan hologram)

    /** Satu task berkala untuk semua LootBox (bukan per tick, bukan per box). Aman dipanggil ulang. */
    public void startMarkers(Supplier<Collection<LootBox>> activeBoxes) {
        stopMarkers();
        markerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickMarkers(activeBoxes.get()),
                particleInterval, particleInterval);
    }

    public void stopMarkers() {
        if (markerTask != null) {
            markerTask.cancel();
            markerTask = null;
        }
    }

    /** Menghentikan task dan menghapus semua hologram (mati / reload). Blok di dunia tidak disentuh. */
    public void shutdown() {
        stopMarkers();
        for (String id : new ArrayList<>(holograms.keySet())) removeHologram(id);
    }

    private void tickMarkers(Collection<LootBox> boxes) {
        for (LootBox box : boxes) {
            if (box.opened()) continue;
            World w = Bukkit.getWorld(box.worldName());
            if (w == null || !w.isChunkLoaded(box.x() >> 4, box.z() >> 4)) continue;

            if (hologramEnabled) {
                List<ArmorStand> stands = holograms.get(box.id());
                if (stands == null || stands.isEmpty() || stands.stream().anyMatch(a -> !a.isValid())) {
                    spawnHologram(box, w);
                }
            }
            if (!particlesEnabled) continue;
            Location center = new Location(w, box.x() + 0.5, box.y() + 0.5, box.z() + 0.5);
            if (w.getNearbyPlayers(center, viewDistance).isEmpty()) continue;
            drawMarker(w, box, center);
        }
    }

    private void drawMarker(World w, LootBox box, Location center) {
        Color color = colors.get(box.rarity()).getColor();
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.4f);
        double phase = (System.currentTimeMillis() % 4000L) / 4000.0 * Math.PI * 2;
        for (int i = 0; i < ringPoints; i++) {
            double a = phase + Math.PI * 2 * i / ringPoints;
            w.spawnParticle(Particle.DUST, center.getX() + Math.cos(a) * 0.9, center.getY(),
                    center.getZ() + Math.sin(a) * 0.9, 1, 0, 0, 0, 0, dust);
        }
        w.spawnParticle(sparkle, center.getX(), center.getY() + 0.6, center.getZ(), 2, 0.15, 0.7, 0.15, 0.01);
    }

    public Color colorOf(LootBoxRarity rarity) {
        return colors.getOrDefault(rarity, DyeColor.WHITE).getColor();
    }
}
