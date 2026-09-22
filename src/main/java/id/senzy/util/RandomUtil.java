package id.senzy.util;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

/** Weighted random selection (deterministik terhadap total bobot, bukan "chance sederhana"). */
public final class RandomUtil {

    private RandomUtil() {}

    /** Memilih satu item berdasarkan bobot. Bobot <= 0 tidak pernah terpilih. Null jika total bobot 0. */
    public static <T> T weighted(Collection<T> items, ToIntFunction<T> weight) {
        long total = 0;
        for (T t : items) total += Math.max(0, weight.applyAsInt(t));
        if (total <= 0) return null;
        long roll = ThreadLocalRandom.current().nextLong(total);
        for (T t : items) {
            int w = Math.max(0, weight.applyAsInt(t));
            if (roll < w) return t;
            roll -= w;
        }
        return null;
    }

    /** Inklusif min..max. */
    public static int between(int min, int max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
