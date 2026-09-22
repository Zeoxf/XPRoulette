package id.senzy.config;

import id.senzy.SenzyPlugin;
import id.senzy.util.TimeUtil;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Pembungkus config.yml. Kunci yang hilang di file server otomatis jatuh ke nilai default
 * dari config.yml di dalam jar, jadi update plugin tidak merusak config lama.
 */
public final class ConfigManager {

    private final SenzyPlugin plugin;
    private FileConfiguration cfg;

    public ConfigManager(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();
    }

    public FileConfiguration cfg() {
        return cfg;
    }

    /** Membaca durasi ("10m", "1h30m") dalam milidetik. */
    public long duration(String path, long defaultMillis) {
        return TimeUtil.parse(cfg.getString(path), defaultMillis);
    }

    public boolean debug() {
        return cfg != null && cfg.getBoolean("debug.enabled", false);
    }
}
