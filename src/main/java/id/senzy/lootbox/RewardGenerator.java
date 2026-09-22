package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.util.EffectUtil;
import id.senzy.util.RandomUtil;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reward LootBox = Rarity + Random Reward + EXP Bottle wajib (10-30, tidak pernah absen).
 *
 * <p>COMMON/RARE memakai daftar material berbobot ({@link LootTable}); RARE punya tambahan peluang
 * satu tool sederhana (1-2 enchant, level rendah). EPIC/LEGENDARY selalu menghasilkan tepat satu
 * tool/armor gacha: material tetap (Epic = IRON, Legendary = DIAMOND, TIDAK PERNAH Netherite),
 * jumlah enchant di-roll (Epic 2-5, Legendary 2-7), setiap level di-roll terpisah (Epic maks 5,
 * Legendary maks 10) - dua RNG independen supaya jumlah banyak tidak otomatis berarti level tinggi.
 * Legendary juga melakukan roll terpisah "Legendary Template" (default 1/1000) sebagai bonus.
 */
public final class RewardGenerator {

    private enum ToolKind {
        PICKAXE, AXE, SHOVEL, HOE, SWORD, HELMET, CHESTPLATE, LEGGINGS, BOOTS
    }

    /** Keluarga material tool - penanda internal, BUKAN Material asli (tidak ada Material.IRON polos). */
    private enum Family { IRON, GOLDEN, DIAMOND }

    private final SenzyPlugin plugin;
    private final LootTable materials;
    private final Map<ToolKind, List<Enchantment>> pools = new EnumMap<>(ToolKind.class);

    // guaranteed EXP bottle
    private boolean expBottleEnabled = true;
    private int expBottleMin = 10;
    private int expBottleMax = 30;

    // rare bonus tool
    private double rareToolChance = 0.35;
    private int rareMinEnchants = 1;
    private int rareMaxEnchants = 2;
    private int rareMaxLevel = 3;

    // epic
    private int epicMinEnchants = 2;
    private int epicMaxEnchants = 5;
    private int epicMaxLevel = 5;

    // legendary
    private int legendaryMinEnchants = 2;
    private int legendaryMaxEnchants = 7;
    private int legendaryMaxLevel = 10;

    // legendary template
    private boolean templateEnabled = true;
    private int templateDenominator = 1000;
    private Material templateMaterial = Material.SMITHING_TEMPLATE;

    private boolean allowIncompatible = true;

    public RewardGenerator(SenzyPlugin plugin, LootTable materials) {
        this.plugin = plugin;
        this.materials = materials;
        buildPools();
    }

    private void buildPools() {
        pools.put(ToolKind.PICKAXE, enchants("efficiency", "fortune", "silk_touch", "unbreaking", "mending"));
        pools.put(ToolKind.AXE, enchants("efficiency", "fortune", "silk_touch", "unbreaking", "mending", "sharpness"));
        pools.put(ToolKind.SHOVEL, enchants("efficiency", "fortune", "silk_touch", "unbreaking", "mending"));
        pools.put(ToolKind.HOE, enchants("efficiency", "fortune", "silk_touch", "unbreaking", "mending"));
        pools.put(ToolKind.SWORD, enchants("sharpness", "looting", "fire_aspect", "knockback", "unbreaking", "mending", "sweeping_edge"));
        pools.put(ToolKind.HELMET, enchants("protection", "respiration", "aqua_affinity", "unbreaking", "mending"));
        pools.put(ToolKind.CHESTPLATE, enchants("protection", "thorns", "unbreaking", "mending"));
        pools.put(ToolKind.LEGGINGS, enchants("protection", "thorns", "unbreaking", "mending"));
        pools.put(ToolKind.BOOTS, enchants("protection", "feather_falling", "depth_strider", "frost_walker",
                "soul_speed", "unbreaking", "mending"));
    }

    private List<Enchantment> enchants(String... ids) {
        List<Enchantment> out = new ArrayList<>();
        for (String id : ids) {
            Enchantment e = EffectUtil.enchant(id);
            if (e != null) out.add(e);
            else plugin.getLogger().warning("Enchantment '" + id + "' tidak dikenal di versi server ini, dilewati dari pool gacha.");
        }
        return out;
    }

    public void load() {
        FileConfiguration c = plugin.config().cfg();
        expBottleEnabled = c.getBoolean("guaranteed-reward.experience-bottle.enabled", true);
        expBottleMin = Math.max(0, c.getInt("guaranteed-reward.experience-bottle.min", 10));
        expBottleMax = Math.max(expBottleMin, c.getInt("guaranteed-reward.experience-bottle.max", 30));

        rareToolChance = clamp01(c.getDouble("rare.tool.chance", 0.35));
        rareMinEnchants = Math.max(1, c.getInt("rare.tool.min-enchants", 1));
        rareMaxEnchants = Math.max(rareMinEnchants, c.getInt("rare.tool.max-enchants", 2));
        rareMaxLevel = Math.max(1, c.getInt("rare.tool.max-enchantment-level", 3));

        epicMinEnchants = Math.max(1, c.getInt("epic.tool.min-enchants", 2));
        epicMaxEnchants = Math.max(epicMinEnchants, c.getInt("epic.tool.max-enchants", 5));
        epicMaxLevel = Math.max(1, c.getInt("epic.tool.max-enchantment-level", 5));

        legendaryMinEnchants = Math.max(1, c.getInt("legendary.tool.min-enchants", 2));
        legendaryMaxEnchants = Math.max(legendaryMinEnchants, c.getInt("legendary.tool.max-enchants", 7));
        legendaryMaxLevel = Math.max(1, c.getInt("legendary.tool.max-enchantment-level", 10));

        templateEnabled = c.getBoolean("legendary.template.enabled", true);
        int denom = c.getInt("legendary.template.denominator", 0);
        if (denom <= 0) {
            double chance = c.getDouble("legendary.template.chance", 0.1); // persen
            denom = chance > 0 ? Math.max(1, (int) Math.round(100.0 / chance)) : 0;
        }
        templateDenominator = denom;
        Material tm = Material.matchMaterial(c.getString("legendary.template.material", "SMITHING_TEMPLATE"));
        templateMaterial = (tm != null && tm.isItem()) ? tm : Material.SMITHING_TEMPLATE;

        allowIncompatible = c.getBoolean("enchantment.allow-incompatible", true);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ------------------------------------------------------------ API

    /** Reward lengkap untuk satu LootBox: material/tool sesuai rarity + EXP Bottle wajib. */
    public List<ItemStack> generate(LootBoxRarity rarity) {
        List<ItemStack> out = new ArrayList<>();
        switch (rarity) {
            case COMMON -> out.addAll(materials.roll(LootBoxRarity.COMMON));
            case RARE -> {
                out.addAll(materials.roll(LootBoxRarity.RARE));
                if (ThreadLocalRandom.current().nextDouble() < rareToolChance) {
                    Family family = ThreadLocalRandom.current().nextBoolean() ? Family.IRON : Family.GOLDEN;
                    ItemStack tool = rollEnchantedTool(family, rareMinEnchants, rareMaxEnchants, rareMaxLevel);
                    if (tool != null) out.add(tool);
                }
            }
            case EPIC -> {
                ItemStack tool = rollEnchantedTool(Family.IRON, epicMinEnchants, epicMaxEnchants, epicMaxLevel);
                if (tool != null) out.add(tool);
                else out.addAll(materials.roll(LootBoxRarity.EPIC)); // jaga-jaga jika semua enchant tidak dikenal
            }
            case LEGENDARY -> {
                ItemStack tool = rollEnchantedTool(Family.DIAMOND, legendaryMinEnchants, legendaryMaxEnchants, legendaryMaxLevel);
                if (tool != null) out.add(tool);
                else out.addAll(materials.roll(LootBoxRarity.LEGENDARY));
                if (rollLegendaryTemplate()) {
                    out.add(new ItemStack(templateMaterial, 1));
                    plugin.debug("LootBox legendary template berhasil (1/" + templateDenominator + ")");
                }
            }
        }
        appendExpBottle(out);
        return out;
    }

    /** True jika roll template berhasil. Tidak ada pity, tidak ada counter - murni RNG tiap kali. */
    private boolean rollLegendaryTemplate() {
        if (!templateEnabled || templateDenominator <= 0) return false;
        return ThreadLocalRandom.current().nextInt(templateDenominator) == 0;
    }

    private void appendExpBottle(List<ItemStack> out) {
        if (!expBottleEnabled) return;
        int amount = RandomUtil.between(expBottleMin, expBottleMax);
        while (amount > 0) {
            int n = Math.min(64, amount);
            out.add(new ItemStack(Material.EXPERIENCE_BOTTLE, n));
            amount -= n;
        }
    }

    // ------------------------------------------------------------ tool gacha

    /**
     * Membuat satu tool/armor gacha (tipe dipilih acak dari 9 jenis). Material keluarga
     * (IRON/GOLDEN/DIAMOND) dipetakan ke nama Material lewat prefix - tidak pernah NETHERITE
     * karena Family di sini memang tidak punya varian netherite sama sekali.
     */
    private ItemStack rollEnchantedTool(Family family, int minEnchants, int maxEnchants, int maxLevel) {
        ToolKind kind = ToolKind.values()[ThreadLocalRandom.current().nextInt(ToolKind.values().length)];
        List<Enchantment> pool = pools.getOrDefault(kind, List.of());
        if (pool.isEmpty()) return null;

        Material material = resolveMaterial(family, kind);
        if (material == null) return null;
        ItemStack item = new ItemStack(material);

        int count = Math.min(pool.size(), RandomUtil.between(minEnchants, maxEnchants));
        List<Enchantment> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());

        Set<Enchantment> chosen = new LinkedHashSet<>();
        for (Enchantment candidate : shuffled) {
            if (chosen.size() >= count) break;
            if (!allowIncompatible && conflictsWithAny(candidate, chosen)) continue;
            chosen.add(candidate);
        }

        ItemMeta meta = item.getItemMeta();
        for (Enchantment ench : chosen) {
            int level = RandomUtil.between(1, maxLevel); // RNG level terpisah dari RNG jumlah enchant
            meta.addEnchant(ench, level, true); // ignoreLevelRestriction: level custom (bisa > batas vanilla)
        }
        item.setItemMeta(meta);
        plugin.debug("LootBox tool gacha: material=" + material + " enchants=" + chosen.size() + "/" + count);
        return item;
    }

    private boolean conflictsWithAny(Enchantment candidate, Set<Enchantment> already) {
        for (Enchantment e : already) {
            if (candidate.conflictsWith(e) || e.conflictsWith(candidate)) return true;
        }
        return false;
    }

    private Material resolveMaterial(Family family, ToolKind kind) {
        String prefix = switch (family) {
            case IRON -> "IRON";
            case GOLDEN -> "GOLDEN";
            case DIAMOND -> "DIAMOND";
        };
        String suffix = switch (kind) {
            case PICKAXE -> "_PICKAXE";
            case AXE -> "_AXE";
            case SHOVEL -> "_SHOVEL";
            case HOE -> "_HOE";
            case SWORD -> "_SWORD";
            case HELMET -> "_HELMET";
            case CHESTPLATE -> "_CHESTPLATE";
            case LEGGINGS -> "_LEGGINGS";
            case BOOTS -> "_BOOTS";
        };
        Material m = Material.matchMaterial(prefix + suffix);
        return (m != null && m.isItem()) ? m : null;
    }
}
