package id.senzy.data;

import id.senzy.SenzyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Persistent storage. Aturan keamanan data:
 * <ul>
 *   <li>Tulis lewat file sementara + rename atomik (crash saat menulis tidak merusak file lama).</li>
 *   <li>Satu thread IO berurutan, jadi dua penulisan ke file yang sama tidak saling menimpa.</li>
 *   <li>File yang gagal di-parse TIDAK dihapus: dipindah ke *.corrupt-&lt;waktu&gt;, lalu dicoba *.bak.</li>
 * </ul>
 */
public final class DataManager {

    private final SenzyPlugin plugin;
    private final File dataDir;
    private final File playersDir;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Senzy-IO");
        t.setDaemon(true);
        return t;
    });
    private BukkitTask autosave;

    public DataManager(SenzyPlugin plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "data");
        this.playersDir = new File(dataDir, "players");
        if (!playersDir.exists() && !playersDir.mkdirs()) {
            plugin.getLogger().warning("Tidak bisa membuat folder data: " + playersDir);
        }
    }

    public File dataDir() {
        return dataDir;
    }

    /** Menjadwalkan autosave. Dipanggil setelah config siap; aman dipanggil ulang saat reload. */
    public void startAutosave() {
        if (autosave != null) autosave.cancel();
        long ticks = Math.max(20L, plugin.config().duration("data.autosave-interval", 300_000L) / 50L);
        autosave = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, ticks, ticks);
    }

    // ------------------------------------------------------------ pemain

    public PlayerData load(UUID uuid, String name) {
        File f = playerFile(uuid);
        PlayerData d = null;
        if (f.exists()) {
            YamlConfiguration y = readYaml(f);
            if (y != null) d = PlayerData.fromYaml(uuid, name, y);
        }
        if (d == null) d = new PlayerData(uuid, name);
        d.setName(name);
        players.put(uuid, d);
        return d;
    }

    /** Data pemain yang sedang termuat (online), atau null. */
    public PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    public Collection<PlayerData> online() {
        return players.values();
    }

    public void unload(UUID uuid) {
        PlayerData d = players.remove(uuid);
        if (d != null) {
            d.markDirty();
            save(d);
        }
    }

    public void save(PlayerData d) {
        if (!d.isDirty()) return;
        d.clearDirty();
        writeAsync(playerFile(d.uuid()), d.toYaml().saveToString(), false);
    }

    public void saveAll() {
        for (PlayerData d : players.values()) save(d);
    }

    private File playerFile(UUID uuid) {
        return new File(playersDir, uuid + ".yml");
    }

    // ------------------------------------------------------------ YAML umum

    /** Membaca YAML; jika rusak, karantina file dan coba backup. Null jika tidak ada yang bisa dibaca. */
    public YamlConfiguration readYaml(File file) {
        if (!file.exists()) return null;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(file);
            return y;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "File data rusak: " + file.getName() + " (" + e.getMessage() + ")");
            quarantine(file);
        }
        File bak = new File(file.getParentFile(), file.getName() + ".bak");
        if (bak.exists()) {
            try {
                YamlConfiguration y = new YamlConfiguration();
                y.load(bak);
                plugin.getLogger().warning("Memulihkan " + file.getName() + " dari backup terakhir.");
                return y;
            } catch (IOException | InvalidConfigurationException e) {
                plugin.getLogger().log(Level.SEVERE, "Backup juga rusak: " + bak.getName());
            }
        }
        return null;
    }

    private void quarantine(File file) {
        File target = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("File rusak disimpan sebagai " + target.getName() + " (tidak dihapus).");
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Gagal mengkarantina " + file.getName(), e);
        }
    }

    public void writeAsync(File target, String content, boolean keepBackup) {
        try {
            io.execute(() -> writeAtomic(target, content, keepBackup));
        } catch (RuntimeException rejected) {
            // executor sudah ditutup (saat shutdown): tulis langsung
            writeAtomic(target, content, keepBackup);
        }
    }

    private void writeAtomic(File target, String content, boolean keepBackup) {
        try {
            Path tgt = target.toPath();
            Files.createDirectories(tgt.getParent());
            Path tmp = tgt.resolveSibling(target.getName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            if (keepBackup && Files.exists(tgt)) {
                Files.copy(tgt, tgt.resolveSibling(target.getName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, tgt, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, tgt, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Gagal menulis " + target.getName(), e);
        }
    }

    /** Menyimpan semua data pemain lalu menunggu antrian IO selesai. Dipanggil saat onDisable. */
    public void shutdown() {
        if (autosave != null) autosave.cancel();
        for (PlayerData d : players.values()) {
            d.markDirty();
            save(d);
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Antrian penyimpanan belum selesai setelah 15 detik.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
