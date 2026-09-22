package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.util.RandomUtil;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Loot table berbobot per rarity (config: loot.&lt;rarity&gt;), jumlah putaran per rarity
 * (lootbox.loot-rolls.&lt;rarity&gt;: {min, max} atau angka tunggal).
 */
public final class LootTable {

    private record Entry(Material material, int min, int max, int weight) {}

    private record Rolls(int min, int max) {}

    private final SenzyPlugin plugin;
    private final Map<LootBoxRarity, List<Entry>> tables = new EnumMap<>(LootBoxRarity.class);
    private final Map<LootBoxRarity, Rolls> rolls = new EnumMap<>(LootBoxRarity.class);

    public LootTable(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        tables.clear();
        rolls.clear();
        FileConfiguration c = plugin.config().cfg();
        for (LootBoxRarity r : LootBoxRarity.values()) {
            List<Entry> entries = new ArrayList<>();
            for (Map<?, ?> map : c.getMapList("loot." + r.id())) {
                String itemName = map.get("item") == null ? "" : map.get("item").toString();
                Material m = Material.matchMaterial(itemName);
                if (m == null || m.isAir() || !m.isItem()) {
                    plugin.getLogger().warning("loot." + r.id() + ": item '" + itemName + "' tidak valid, dilewati.");
                    continue;
                }
                int fixed = intOf(map.get("amount"), 1);
                int min = Math.max(1, intOf(map.get("min"), fixed));
                int max = Math.max(min, intOf(map.get("max"), Math.max(min, fixed)));
                int weight = Math.max(0, intOf(map.get("weight"), 1));
                entries.add(new Entry(m, min, max, weight));
            }
            if (entries.isEmpty()) {
                plugin.getLogger().warning("loot." + r.id() + " kosong - LootBox rarity ini tidak akan memberi item.");
            }
            tables.put(r, entries);

            String base = "lootbox.loot-rolls." + r.id();
            int lo = 1;
            int hi = 1;
            if (c.isConfigurationSection(base)) {
                lo = Math.max(1, c.getInt(base + ".min", 1));
                hi = Math.max(lo, c.getInt(base + ".max", lo));
            } else if (c.contains(base)) {
                lo = Math.max(1, c.getInt(base, 1));
                hi = lo;
            }
            rolls.put(r, new Rolls(lo, hi));
        }
    }

    /** Mengocok isi LootBox sesuai rarity (weighted RNG per putaran). */
    public List<ItemStack> roll(LootBoxRarity rarity) {
        List<Entry> entries = tables.getOrDefault(rarity, List.of());
        Rolls r = rolls.getOrDefault(rarity, new Rolls(1, 1));
        List<ItemStack> out = new ArrayList<>();
        if (entries.isEmpty()) return out;
        int count = RandomUtil.between(r.min(), r.max());
        for (int i = 0; i < count; i++) {
            Entry e = RandomUtil.weighted(entries, Entry::weight);
            if (e == null) continue;
            int amount = RandomUtil.between(e.min(), e.max());
            int stack = Math.max(1, e.material().getMaxStackSize());
            while (amount > 0) {
                int n = Math.min(stack, amount);
                out.add(new ItemStack(e.material(), n));
                amount -= n;
            }
        }
        return out;
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // default
            }
        }
        return def;
    }
}
