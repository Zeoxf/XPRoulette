package id.xproulette.lootbox;

import org.bukkit.Material;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Satu Loot Box. Identitasnya adalah {@link #id()}, bukan koordinatnya.
 * Status memakai compare-and-set sehingga dua pemain tidak bisa mengklaim box yang sama.
 */
public final class LootBox {

    public enum Status {AVAILABLE, CLAIMING, OPENED}

    private final String id;
    private final String eventId;
    private final UUID worldId;
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;
    private final Rarity rarity;
    private final Material material;
    private final long spawnTime;

    private final AtomicReference<Status> status = new AtomicReference<>(Status.AVAILABLE);
    private volatile boolean placed;
    private volatile String previousData = "minecraft:air";
    private volatile String openedBy;

    public LootBox(String id, String eventId, UUID worldId, String worldName,
                   int x, int y, int z, Rarity rarity, Material material, long spawnTime) {
        this.id = id;
        this.eventId = eventId;
        this.worldId = worldId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rarity = rarity;
        this.material = material;
        this.spawnTime = spawnTime;
    }

    public String id() {
        return id;
    }

    public String eventId() {
        return eventId;
    }

    public UUID worldId() {
        return worldId;
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

    public Rarity rarity() {
        return rarity;
    }

    public Material material() {
        return material;
    }

    public long spawnTime() {
        return spawnTime;
    }

    public BlockKey key() {
        return new BlockKey(worldId, x, y, z);
    }

    // ------------------------------------------------------------------ status

    /** Mengunci box untuk satu pemain. Hanya SATU pemanggil yang mendapat true. */
    public boolean tryLock() {
        return status.compareAndSet(Status.AVAILABLE, Status.CLAIMING);
    }

    /** Melepas kunci (dipakai jika pembuatan loot gagal sebelum ada yang diberikan). */
    public void unlock() {
        status.compareAndSet(Status.CLAIMING, Status.AVAILABLE);
    }

    public void markOpened(String by) {
        this.openedBy = by;
        status.set(Status.OPENED);
    }

    /** Dipakai saat memuat state dari disk. */
    public void restoreOpened(String by) {
        markOpened(by);
    }

    public boolean isOpened() {
        return status.get() == Status.OPENED;
    }

    public boolean isAvailable() {
        return status.get() == Status.AVAILABLE;
    }

    public String openedBy() {
        return openedBy;
    }

    // ------------------------------------------------------------------- block

    public boolean isPlaced() {
        return placed;
    }

    public void setPlaced(boolean placed) {
        this.placed = placed;
    }

    /** BlockData asli sebelum diganti box, supaya bisa dikembalikan saat cleanup. */
    public String previousData() {
        return previousData;
    }

    public void setPreviousData(String previousData) {
        this.previousData = previousData == null ? "minecraft:air" : previousData;
    }
}
