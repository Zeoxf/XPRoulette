package id.senzy.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Map;

/** Lookup efek/partikel/suara berdasarkan nama di config (lewat Registry, bukan enum yang berubah tiap versi). */
public final class EffectUtil {

    /** Nama lama Bukkit -> ID vanilla, supaya config lama tetap terbaca. */
    private static final Map<String, String> POTION_ALIASES = Map.ofEntries(
            Map.entry("jump", "jump_boost"),
            Map.entry("increase_damage", "strength"),
            Map.entry("damage_resistance", "resistance"),
            Map.entry("confusion", "nausea"),
            Map.entry("fast_digging", "haste"),
            Map.entry("slow", "slowness"),
            Map.entry("slow_digging", "mining_fatigue"),
            Map.entry("heal", "instant_health"),
            Map.entry("harm", "instant_damage")
    );

    private EffectUtil() {}

    public static PotionEffectType potion(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        key = POTION_ALIASES.getOrDefault(key, key);
        return Registry.EFFECT.get(NamespacedKey.minecraft(key));
    }

    public static Particle particle(String name) {
        if (name == null || name.isBlank()) return null;
        return Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Menerima "entity.player.levelup" (format vanilla, disarankan), "minecraft:entity.player.levelup",
     * atau nama enum Bukkit lama "ENTITY_PLAYER_LEVELUP" (dikonversi paksa, tidak selalu akurat -
     * lebih baik pakai format vanilla dengan titik di config).
     */
    public static Sound sound(String name) {
        if (name == null || name.isBlank()) return null;
        String raw = name.trim().toLowerCase(Locale.ROOT);
        // Sudah berupa key vanilla (mengandung titik atau namespace) - dipakai apa adanya, JANGAN ubah underscore.
        if (raw.contains(".") || raw.contains(":")) {
            NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : NamespacedKey.minecraft(raw);
            return key == null ? null : Registry.SOUNDS.get(key);
        }
        // Tidak ada titik sama sekali: kemungkinan nama enum lama tanpa underscore, coba langsung.
        Sound direct = Registry.SOUNDS.get(NamespacedKey.minecraft(raw));
        if (direct != null) return direct;
        // Tebakan terakhir untuk gaya ENTITY_PLAYER_LEVELUP: ganti semua underscore jadi titik.
        return Registry.SOUNDS.get(NamespacedKey.minecraft(raw.replace('_', '.')));
    }
}
