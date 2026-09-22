package id.senzy.xpr;

import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Definisi generik sebuah boost. Boost baru cukup ditambah di config.yml; tidak perlu class baru.
 */
public final class Boost {

    private final String id;
    private final String displayName;
    private final Material icon;
    private final int weight;
    private final PotionEffectType effect;
    private final List<BoostTier> tiers;
    private final BoostSideEffect sideEffect;

    public Boost(String id, String displayName, Material icon, int weight, PotionEffectType effect,
                 List<BoostTier> tiers, BoostSideEffect sideEffect) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.weight = weight;
        this.effect = effect;
        this.tiers = List.copyOf(tiers);
        this.sideEffect = sideEffect;
    }

    public String id() {
        return id;
    }

    /** String MiniMessage, contoh "&lt;green&gt;Mining". */
    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    public int weight() {
        return weight;
    }

    public PotionEffectType effect() {
        return effect;
    }

    public int tierCount() {
        return tiers.size();
    }

    /** Tier 1-based; nilai di luar rentang dijepit. */
    public BoostTier tier(int tier) {
        int idx = Math.max(1, Math.min(tiers.size(), tier)) - 1;
        return tiers.get(idx);
    }

    public BoostSideEffect sideEffect() {
        return sideEffect;
    }
}
