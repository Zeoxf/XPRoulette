package id.xproulette.lootbox;

import org.bukkit.block.Block;

import java.util.UUID;

/** Kunci pencarian Loot Box berdasarkan posisi block. */
public record BlockKey(UUID world, int x, int y, int z) {

    public static BlockKey of(Block b) {
        return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }
}
