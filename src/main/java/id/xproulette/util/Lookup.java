package id.xproulette.util;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Mencari konstanta statis berdasarkan nama (mis. Particle.END_ROD, Sound.BLOCK_BEACON_ACTIVATE).
 * Memakai refleksi pada field publik statis, jadi tetap jalan baik kelasnya enum
 * maupun registry-backed (perubahan API Paper 1.21.x).
 */
public final class Lookup {

    private static final Map<String, Optional<Object>> CACHE = new HashMap<>();

    private Lookup() {
    }

    /** @return konstanta, atau null jika namanya tidak ada. */
    @SuppressWarnings("unchecked")
    public static <T> T constant(Class<T> type, String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim().toUpperCase(Locale.ROOT);
        Optional<Object> hit = CACHE.computeIfAbsent(type.getName() + "#" + n, k -> {
            try {
                Field f = type.getField(n);
                Object v = f.get(null);
                return type.isInstance(v) ? Optional.of(v) : Optional.empty();
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return Optional.empty();
            }
        });
        return (T) hit.orElse(null);
    }
}
