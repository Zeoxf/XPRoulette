package id.senzy.config;

import id.senzy.SenzyPlugin;
import id.senzy.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Semua teks yang dilihat pemain berasal dari messages.yml (MiniMessage).
 * Placeholder dipakai sebagai tag: {@code <player>}, {@code <time>}, dst. Tag {@code <prefix>} selalu tersedia.
 * Kunci yang hilang di file server jatuh ke default di dalam jar.
 */
public final class MessageManager {

    private final SenzyPlugin plugin;
    private YamlConfiguration messages = new YamlConfiguration();
    private YamlConfiguration defaults = new YamlConfiguration();

    public MessageManager(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Gagal membaca default messages.yml: " + e.getMessage());
        }
    }

    /** Teks mentah (MiniMessage belum diproses). */
    public String raw(String key) {
        String v = messages.getString(key);
        if (v == null) v = defaults.getString(key);
        return v == null ? "<red>[missing message: " + key + "]</red>" : v;
    }

    public List<String> rawList(String key) {
        List<String> v = messages.isList(key) ? messages.getStringList(key) : null;
        if (v == null) v = defaults.isList(key) ? defaults.getStringList(key) : null;
        if (v == null) {
            // Boleh juga berupa satu string.
            String single = messages.getString(key, defaults.getString(key));
            return single == null ? List.of() : List.of(single);
        }
        return v;
    }

    /** Pasangan placeholder: nama1, nilai1, nama2, nilai2, ... Nilai Component diteruskan apa adanya. */
    public Component get(String key, Object... pairs) {
        return TextUtil.mm().deserialize(raw(key), resolvers(pairs));
    }

    public List<Component> list(String key, Object... pairs) {
        TagResolver[] resolvers = resolvers(pairs);
        List<Component> out = new ArrayList<>();
        for (String line : rawList(key)) {
            out.add(TextUtil.mm().deserialize(line, resolvers));
        }
        return out;
    }

    /** Versi teks polos (tanpa warna) - untuk log/debug. */
    public String plain(String key, Object... pairs) {
        return TextUtil.plain(get(key, pairs));
    }

    public void send(CommandSender to, String key, Object... pairs) {
        to.sendMessage(get(key, pairs));
    }

    public void sendList(CommandSender to, String key, Object... pairs) {
        for (Component c : list(key, pairs)) to.sendMessage(c);
    }

    public void broadcast(String key, Object... pairs) {
        Bukkit.broadcast(get(key, pairs));
    }

    private TagResolver[] resolvers(Object... pairs) {
        List<TagResolver> out = new ArrayList<>();
        out.add(Placeholder.parsed("prefix", raw("prefix")));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value instanceof Component c) {
                out.add(Placeholder.component(name, c));
            } else {
                out.add(Placeholder.parsed(name, String.valueOf(value)));
            }
        }
        return out.toArray(new TagResolver[0]);
    }
}
