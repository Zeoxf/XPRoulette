package id.xproulette;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class XPRoulette extends JavaPlugin {

    private Messages messages;
    private RouletteManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        messages = new Messages(this);
        messages.load();

        manager = new RouletteManager(this, messages);
        manager.reload();
        manager.loadData();

        getServer().getPluginManager().registerEvents(new RouletteListener(manager), this);

        PluginCommand cmd = getCommand("xproulette");
        if (cmd != null) {
            XPRCommand handler = new XPRCommand(this, manager, messages);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        manager.start();

        // Jika plugin di-load saat pemain sudah online (mis. /reload).
        for (Player p : getServer().getOnlinePlayers()) {
            manager.handleJoin(p);
        }

        getLogger().info("XP Roulette aktif - semoga beruntung!");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    /** Dipanggil oleh /xpr reload. */
    public void reloadAll() {
        reloadConfig();
        messages.load();
        manager.reload();
    }
}
