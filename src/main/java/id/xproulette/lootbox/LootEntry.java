package id.xproulette.lootbox;

import org.bukkit.Material;

/** Satu kemungkinan item di loot table: jumlah acak min..max dengan bobot tertentu. */
public record LootEntry(Material material, int min, int max, int weight) {
}
