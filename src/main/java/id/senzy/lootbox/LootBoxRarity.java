package id.senzy.lootbox;

import java.util.Locale;

public enum LootBoxRarity {
    COMMON, RARE, EPIC, LEGENDARY;

    /** ID huruf kecil untuk config/messages: "common", "rare", ... */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LootBoxRarity fromId(String id) {
        if (id == null) return COMMON;
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return COMMON;
        }
    }
}
