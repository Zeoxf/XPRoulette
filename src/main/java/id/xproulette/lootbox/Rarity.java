package id.xproulette.lootbox;

import java.util.Locale;

/** Urutan enum = urutan iterasi weighted RNG (Common -> Legendary) dan urutan tingkat rarity. */
public enum Rarity {
    COMMON,
    RARE,
    EPIC,
    LEGENDARY;

    /** Kunci di lootbox.yml, mis. "common". */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
