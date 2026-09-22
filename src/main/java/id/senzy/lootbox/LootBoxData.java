package id.senzy.lootbox;

import id.senzy.data.DataManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * State event LootBox yang disimpan ke disk (data/lootbox-state.yml):
 * eventId, sessionId, boxId, world, x/y/z, rarity, opened, openedBy, spawnTime, endTime,
 * serta blok asli yang harus dipulihkan.
 */
public final class LootBoxData {

    /** Counter untuk eventId berurutan (lb-0001, lb-0002, ...). */
    public long counter;
    /** Epoch millis session terakhir berakhir (0 = belum pernah). Dipakai auto-start. */
    public long lastEnd;
    public LootBoxSession session;
    /** LootBox yang masih perlu dijaga/dibersihkan. Kosong setelah session selesai dibersihkan. */
    public final Map<String, LootBox> boxes = new LinkedHashMap<>();

    private static File file(DataManager dm) {
        return new File(dm.dataDir(), "lootbox-state.yml");
    }

    public static LootBoxData load(DataManager dm) {
        LootBoxData data = new LootBoxData();
        YamlConfiguration y = dm.readYaml(file(dm));
        if (y == null) return data;

        data.counter = y.getLong("counter", 0L);
        data.lastEnd = y.getLong("last-end", 0L);

        ConfigurationSection s = y.getConfigurationSection("session");
        if (s != null) {
            LootBoxSession session = new LootBoxSession(
                    s.getString("event-id", "lb-0000"),
                    s.getString("session-id", UUID.randomUUID().toString()),
                    s.getLong("start", 0L), s.getLong("end", 0L), s.getInt("amount", 0));
            session.restoreEnded(s.getBoolean("active", false), s.getString("end-reason", ""));
            data.session = session;
        }

        ConfigurationSection bs = y.getConfigurationSection("boxes");
        if (bs != null) {
            for (String id : bs.getKeys(false)) {
                ConfigurationSection b = bs.getConfigurationSection(id);
                if (b == null) continue;
                LootBox box = new LootBox(id,
                        b.getString("event-id", ""), b.getString("session-id", ""),
                        b.getString("world", "world"), b.getInt("x"), b.getInt("y"), b.getInt("z"),
                        LootBoxRarity.fromId(b.getString("rarity")),
                        b.getLong("spawn-time", 0L), b.getLong("end-time", 0L), b.getBoolean("beam", false));
                UUID by = null;
                String byRaw = b.getString("opened-by");
                if (byRaw != null && !byRaw.isBlank()) {
                    try {
                        by = UUID.fromString(byRaw);
                    } catch (IllegalArgumentException ignored) {
                        // biarkan null
                    }
                }
                box.restoreOpened(b.getBoolean("opened", false), by, b.getString("opened-by-name"));
                List<String> originals = b.getStringList("originals");
                for (String line : originals) {
                    String[] parts = line.split(";", 4);
                    if (parts.length < 4) continue;
                    try {
                        box.originals().add(new LootBox.OriginalBlock(
                                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]), parts[3]));
                    } catch (NumberFormatException ignored) {
                        // baris rusak dilewati
                    }
                }
                data.boxes.put(id, box);
            }
        }
        return data;
    }

    public void save(DataManager dm) {
        dm.writeAsync(file(dm), toYaml().saveToString(), true);
    }

    private YamlConfiguration toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("version", 1);
        y.set("counter", counter);
        y.set("last-end", lastEnd);
        if (session != null) {
            y.set("session.event-id", session.eventId());
            y.set("session.session-id", session.sessionId());
            y.set("session.start", session.startTime());
            y.set("session.end", session.endTime());
            y.set("session.amount", session.amount());
            y.set("session.active", session.active());
            y.set("session.end-reason", session.endReason());
        }
        for (LootBox box : boxes.values()) {
            String base = "boxes." + box.id();
            y.set(base + ".event-id", box.eventId());
            y.set(base + ".session-id", box.sessionId());
            y.set(base + ".world", box.worldName());
            y.set(base + ".x", box.x());
            y.set(base + ".y", box.y());
            y.set(base + ".z", box.z());
            y.set(base + ".rarity", box.rarity().name());
            y.set(base + ".opened", box.opened());
            y.set(base + ".opened-by", box.openedBy() == null ? null : box.openedBy().toString());
            y.set(base + ".opened-by-name", box.openedByName());
            y.set(base + ".spawn-time", box.spawnTime());
            y.set(base + ".end-time", box.endTime());
            y.set(base + ".beam", box.beamActive());
            List<String> originals = box.originals().stream()
                    .map(o -> o.x() + ";" + o.y() + ";" + o.z() + ";" + o.blockData())
                    .toList();
            y.set(base + ".originals", originals);
        }
        return y;
    }
}
