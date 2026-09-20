package id.xproulette.lootbox;

import java.util.List;

/**
 * Loot table satu rarity.
 *
 * @param minItems override jumlah item minimal (0 = pakai loot.min-items global)
 * @param maxItems override jumlah item maksimal (0 = pakai loot.max-items global)
 */
public record LootTable(int minItems, int maxItems, List<LootEntry> entries) {
}
