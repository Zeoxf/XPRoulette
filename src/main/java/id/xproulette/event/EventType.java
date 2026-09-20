package id.xproulette.event;

/** Jenis event. Menambah event baru = tambah konstanta di sini + daftarkan GameEvent-nya. */
public enum EventType {
    /** Sistem XP Roulette yang sudah ada (berjalan per pemain, tidak dijadwalkan acak). */
    XP_ROULETTE("xp-roulette", false),
    LOOT_BOX("loot-box", true),
    SUPPLY_DROP("supply-drop", true),
    METEOR("meteor", true),
    TREASURE("treasure-hunt", true),
    BOSS("boss", true);

    private final String configKey;
    private final boolean randomlyScheduled;

    EventType(String configKey, boolean randomlyScheduled) {
        this.configKey = configKey;
        this.randomlyScheduled = randomlyScheduled;
    }

    /** Kunci di events.yml -> event-types.[configKey]. */
    public String configKey() {
        return configKey;
    }

    /** true = boleh dipilih EventScheduler lewat RNG. */
    public boolean isRandomlyScheduled() {
        return randomlyScheduled;
    }
}
