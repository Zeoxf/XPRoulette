package id.xproulette.lootbox;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Menyimpan state ke lootbox-state.yml agar aman saat restart/crash:
 * counter ID harian, sesi yang sedang berjalan (beserta semua Loot Box) dan waktu akhir cooldown.
 * Waktu memakai jam dinding (millis), jadi waktu tetap berjalan saat server mati.
 */
public final class LootBoxStore {

    /** Snapshot satu sesi. */
    public record Session(String eventId, long startMillis, long endMillis, String centerName,
                          String worldName, int centerX, int centerZ, List<LootBox> boxes) {
    }

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml;

    public LootBoxStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lootbox-state.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    // -------------------------------------------------------------------- ID

    /** EVT-20260920-001 */
    public String nextEventId() {
        return next("event", "EVT");
    }

    /** LB-20260920-001 */
    public String nextBoxId() {
        return next("box", "LB");
    }

    private String next(String kind, String prefix) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!today.equals(yaml.getString("counters.date"))) {
            yaml.set("counters.date", today);
            yaml.set("counters.event", 0);
            yaml.set("counters.box", 0);
        }
        int n = yaml.getInt("counters." + kind) + 1;
        yaml.set("counters." + kind, n);
        return String.format(Locale.ROOT, "%s-%s-%03d", prefix, today, n);
    }

    // ---------------------------------------------------------------- session

    public void saveSession(Session s) {
        yaml.set("session.event-id", s.eventId());
        yaml.set("session.start", s.startMillis());
        yaml.set("session.end", s.endMillis());
        yaml.set("session.center-name", s.centerName());
        yaml.set("session.world", s.worldName());
        yaml.set("session.center-x", s.centerX());
        yaml.set("session.center-z", s.centerZ());

        List<Map<String, Object>> list = new ArrayList<>();
        for (LootBox b : s.boxes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("event", b.eventId());
            m.put("world", b.worldName());
            m.put("world-id", b.worldId().toString());
            m.put("x", b.x());
            m.put("y", b.y());
            m.put("z", b.z());
            m.put("rarity", b.rarity().name());
            m.put("material", b.material().name());
            m.put("spawn", b.spawnTime());
            m.put("opened", b.isOpened());
            m.put("opened-by", b.openedBy() == null ? "" : b.openedBy());
            m.put("previous", b.previousData());
            list.add(m);
        }
        yaml.set("session.boxes", list);
        save();
    }

    /** @return sesi tersimpan, atau null jika tidak ada. */
    public Session loadSession() {
        if (!yaml.contains("session.event-id")) return null;

        List<LootBox> boxes = new ArrayList<>();
        for (Map<?, ?> m : yaml.getMapList("session.boxes")) {
            try {
                Rarity rarity = Rarity.valueOf(String.valueOf(m.get("rarity")));
                Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
                if (mat == null) mat = Material.CHEST;
                LootBox b = new LootBox(
                        String.valueOf(m.get("id")),
                        String.valueOf(m.get("event")),
                        UUID.fromString(String.valueOf(m.get("world-id"))),
                        String.valueOf(m.get("world")),
                        num(m.get("x")), num(m.get("y")), num(m.get("z")),
                        rarity, mat, longOf(m.get("spawn")));
                b.setPreviousData(String.valueOf(m.get("previous")));
                b.setPlaced(true);
                if (Boolean.parseBoolean(String.valueOf(m.get("opened")))) {
                    b.restoreOpened(String.valueOf(m.get("opened-by")));
                }
                boxes.add(b);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Loot Box tersimpan rusak, dilewati: " + m, ex);
            }
        }
        return new Session(
                yaml.getString("session.event-id", "?"),
                yaml.getLong("session.start"),
                yaml.getLong("session.end"),
                yaml.getString("session.center-name", "?"),
                yaml.getString("session.world", ""),
                yaml.getInt("session.center-x"),
                yaml.getInt("session.center-z"),
                boxes);
    }

    /** Menghapus sesi tersimpan dan mencatat kapan cooldown berakhir (0 = tanpa cooldown). */
    public void clearSession(long cooldownUntilMillis) {
        yaml.set("session", null);
        yaml.set("cooldown-until", cooldownUntilMillis);
        save();
    }

    public long cooldownUntil() {
        return yaml.getLong("cooldown-until", 0L);
    }

    // ------------------------------------------------------------------ file

    public void save() {
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Gagal menyimpan lootbox-state.yml", ex);
        }
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o));
    }

    private static long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }
}
