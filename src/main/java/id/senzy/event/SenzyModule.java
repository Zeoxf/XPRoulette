package id.senzy.event;

/**
 * Satu "event/sistem" di dalam ekosistem Senzy (XPR, LootBox, dan yang akan datang).
 * Modul tidak saling memanggil; koordinasi hanya lewat {@link SenzyEventManager}.
 */
public interface SenzyModule {

    /** ID unik, huruf kecil (contoh: "xpr", "lootbox"). */
    String id();

    /** Dipanggil sekali saat plugin menyala (setelah config, pesan, dan data siap). */
    void enable();

    /** Dipanggil saat plugin mati. Harus membatalkan semua task milik modul ini. */
    void disable();

    /**
     * Dipanggil setelah config/messages dibaca ulang. Harus idempoten: tidak boleh menggandakan
     * scheduler, beacon, atau event yang sedang berjalan.
     */
    void reload();
}
