package id.xproulette.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;

/**
 * RNG berbobot yang dipakai seluruh sistem baru (rarity, loot, item, tipe event).
 *
 * Cara kerja (kumulatif): tiap item mendapat rentang sesuai bobotnya, urut sesuai iterasi.
 * Contoh bobot 50/24/25/1 (total 100), roll 73 -> 0-49 Common, 50-73 Rare, 74-98 Epic, 99 Legendary.
 */
public final class WeightedRandom {

    private WeightedRandom() {
    }

    public static <T> T chooseWeighted(Collection<? extends T> items, ToIntFunction<? super T> weightOf) {
        return chooseWeighted(items, weightOf, ThreadLocalRandom.current());
    }

    /** @return item terpilih, atau null jika daftar kosong / total bobot 0. */
    public static <T> T chooseWeighted(Collection<? extends T> items, ToIntFunction<? super T> weightOf,
                                       RandomGenerator rnd) {
        long total = 0;
        for (T item : items) {
            total += Math.max(0, weightOf.applyAsInt(item));
        }
        if (total <= 0) return null;

        long roll = rnd.nextLong(total); // 0 .. total-1
        for (T item : items) {
            int w = Math.max(0, weightOf.applyAsInt(item));
            if (roll < w) return item;
            roll -= w;
        }
        return null; // tidak akan tercapai
    }

    /** Versi Map: kunci = hasil, nilai = bobot. */
    public static <K> K chooseWeighted(Map<K, Integer> weights) {
        Map.Entry<K, Integer> e = chooseWeighted(weights.entrySet(), en -> en.getValue() == null ? 0 : en.getValue());
        return e == null ? null : e.getKey();
    }

    /** Undian berbobot TANPA pengembalian (tidak ada item yang sama dua kali). */
    public static <T> List<T> chooseDistinct(Collection<? extends T> items, ToIntFunction<? super T> weightOf,
                                             int count, RandomGenerator rnd) {
        List<T> pool = new ArrayList<>(items);
        List<T> out = new ArrayList<>();
        while (out.size() < count && !pool.isEmpty()) {
            T pick = chooseWeighted(pool, weightOf, rnd);
            if (pick == null) break; // sisa bobot semuanya 0
            out.add(pick);
            pool.remove(pick);
        }
        return out;
    }

    /** Bilangan bulat acak min..max (inklusif). Aman: tidak pernah keluar dari batas, min > max dianggap min. */
    public static int nextIntInclusive(RandomGenerator rnd, int min, int max) {
        if (max <= min) return min;
        if (max == Integer.MAX_VALUE) return rnd.nextInt(min, max);
        return rnd.nextInt(min, max + 1);
    }
}
