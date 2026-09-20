package id.xproulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Memuat messages.yml dan merender pesan MiniMessage dengan placeholder {nama}. */
public final class Messages {

    private final JavaPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private YamlConfiguration cfg = new YamlConfiguration();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        // Default dari dalam jar, supaya pesan yang hilang setelah update tetap ada.
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Gagal membaca default messages.yml: " + ex.getMessage());
        }
    }

    public boolean has(String path) {
        return cfg.contains(path);
    }

    public String raw(String path) {
        String s = cfg.getString(path);
        return s == null ? "<red>[pesan hilang: " + path + "]</red>" : s;
    }

    public List<String> list(String path) {
        return cfg.getStringList(path);
    }

    /** Ambil satu baris acak dari sebuah daftar. */
    public String pick(String path) {
        List<String> l = list(path);
        if (l.isEmpty()) return raw(path);
        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    /** Ganti {prefix} dan pasangan (kunci, nilai), lalu parse sebagai MiniMessage. */
    public Component render(String template, Object... kv) {
        String s = template.replace("{prefix}", raw("prefix"));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return mm.deserialize(s);
    }

    public Component get(String path, Object... kv) {
        return render(raw(path), kv);
    }

    public Component random(String path, Object... kv) {
        return render(pick(path), kv);
    }
}
