package id.xproulette.lootbox;

import id.xproulette.util.WeightedRandom;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sistem Loot, terpisah dari Loot Box:
 * rarity RNG -> loot table -> jumlah item -> item berbobot -> jumlah min..max.
 */
public final class LootGenerator {

    private final LootBoxSettings settings;

    public LootGenerator(LootBoxSettings settings) {
        this.settings = settings;
    }

    /** Weighted RNG rarity berdasarkan rarity.*.weight. Jika semua bobot 0 -> COMMON. */
    public Rarity rollRarity() {
        Rarity r = WeightedRandom.chooseWeighted(List.of(Rarity.values()), settings::rarityWeight);
        return r == null ? Rarity.COMMON : r;
    }

    /** Membuat isi loot untuk satu Loot Box. Tiap ItemStack tidak melebihi max stack. */
    public List<ItemStack> generate(Rarity rarity) {
        LootTable table = settings.table(rarity);
        if (table == null || table.entries().isEmpty()) return List.of();

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int min = table.minItems() > 0 ? table.minItems() : settings.minItems;
        int max = table.maxItems() > 0 ? table.maxItems() : settings.maxItems;
        min = Math.max(1, min);
        max = Math.max(min, max);
        int itemCount = WeightedRandom.nextIntInclusive(rnd, min, max);

        List<LootEntry> picks;
        if (settings.allowDuplicates) {
            picks = new ArrayList<>();
            for (int i = 0; i < itemCount; i++) {
                LootEntry e = WeightedRandom.chooseWeighted(table.entries(), LootEntry::weight, rnd);
                if (e != null) picks.add(e);
            }
        } else {
            picks = WeightedRandom.chooseDistinct(table.entries(), LootEntry::weight, itemCount, rnd);
        }

        List<ItemStack> out = new ArrayList<>();
        for (LootEntry e : picks) {
            int amount = WeightedRandom.nextIntInclusive(rnd, e.min(), e.max());
            int maxStack = Math.max(1, e.material().getMaxStackSize());
            while (amount > 0) {
                int n = Math.min(amount, maxStack);
                out.add(new ItemStack(e.material(), n));
                amount -= n;
            }
        }
        return out;
    }

    /** ["Diamond x5", "Golden Apple x1"] - jumlah per jenis item digabung. */
    public static List<String> describe(List<ItemStack> items) {
        Map<Material, Integer> totals = new LinkedHashMap<>();
        for (ItemStack s : items) {
            totals.merge(s.getType(), s.getAmount(), Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : totals.entrySet()) {
            lines.add(prettyName(e.getKey()) + " x" + e.getValue());
        }
        return lines;
    }

    public static String prettyName(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String part : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
