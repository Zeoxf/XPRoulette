package id.senzy.lootbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Satu LootBox dalam sebuah session.
 * Koordinat (x, y, z) adalah blok LootBox; beacon ada satu blok di barat (x - 1).
 */
public final class LootBox {

    /** Blok asli sebelum diubah, disimpan supaya terrain bisa dipulihkan persis. */
    public record OriginalBlock(int x, int y, int z, String blockData) {}

    private final String id;
    private final String eventId;
    private final String sessionId;
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;
    private final LootBoxRarity rarity;
    private final long spawnTime;
    private final long endTime;
    private final boolean beamActive;

    private boolean opened;
    private UUID openedBy;
    private String openedByName;
    private final List<OriginalBlock> originals = new ArrayList<>();

    public LootBox(String id, String eventId, String sessionId, String worldName, int x, int y, int z,
                   LootBoxRarity rarity, long spawnTime, long endTime, boolean beamActive) {
        this.id = id;
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rarity = rarity;
        this.spawnTime = spawnTime;
        this.endTime = endTime;
        this.beamActive = beamActive;
    }

    public String id() {
        return id;
    }

    public String eventId() {
        return eventId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String worldName() {
        return worldName;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /** Posisi beacon (satu blok di barat LootBox). */
    public int beaconX() {
        return x - 1;
    }

    public LootBoxRarity rarity() {
        return rarity;
    }

    public long spawnTime() {
        return spawnTime;
    }

    public long endTime() {
        return endTime;
    }

    /** True jika struktur beacon (beam) dibangun; false = hanya particle marker. */
    public boolean beamActive() {
        return beamActive;
    }

    public boolean opened() {
        return opened;
    }

    public UUID openedBy() {
        return openedBy;
    }

    public String openedByName() {
        return openedByName;
    }

    public void markOpened(UUID by, String name) {
        this.opened = true;
        this.openedBy = by;
        this.openedByName = name;
    }

    void restoreOpened(boolean opened, UUID by, String name) {
        this.opened = opened;
        this.openedBy = by;
        this.openedByName = name;
    }

    public List<OriginalBlock> originals() {
        return originals;
    }
}
