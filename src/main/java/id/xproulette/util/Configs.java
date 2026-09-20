package id.xproulette.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Memuat file YAML tambahan (lootbox.yml, events.yml) dengan default dari dalam jar. */
public final class Configs {

    private Configs() {
    }

    public static YamlConfiguration load(JavaPlugin plugin, String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        // Kunci yang hilang (mis. setelah update plugin) memakai nilai default dari jar.
        try (InputStream in = plugin.getResource(fileName)) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Gagal membaca default " + fileName + ": " + ex.getMessage());
        }
        return cfg;
    }
}
