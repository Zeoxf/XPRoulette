package id.xproulette.event;

/**
 * Kontrak semua event (Loot Box, Supply Drop, Meteor, Boss, ...).
 * Seluruh komunikasi antar-event lewat {@link EventManager}; event tidak saling mengakses internal.
 */
public interface GameEvent {

    /** ID unik, mis. "loot-box". */
    String getId();

    EventType getType();

    EventState getState();

    /**
     * true = event eksklusif: tidak boleh berjalan (maupun cooldown) bersamaan dengan
     * event eksklusif lain. false = event latar (mis. XP Roulette) yang hidup berdampingan.
     */
    boolean isExclusive();

    /** Diaktifkan di konfigurasi event itu sendiri. */
    boolean isEnabled();

    /** Syarat internal event terpenuhi (mis. state IDLE). */
    boolean canStart();

    /**
     * Memulai event. Boleh selesai asinkron (state STARTING dulu).
     *
     * @return true jika proses start berhasil dijalankan
     */
    boolean start();

    /** Menghentikan event lebih awal (aman dipanggil di state apa pun). */
    void stop();

    /** Dipanggil EventScheduler tiap 1 detik. Harus ringan. */
    void tick();

    /** Dipanggil saat plugin dimatikan. Harus sinkron. */
    void shutdown();
}
