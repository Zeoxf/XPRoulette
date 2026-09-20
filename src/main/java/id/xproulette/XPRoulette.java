package id.xproulette;

import id.xproulette.event.EventManager;
import id.xproulette.event.XpRouletteEvent;
import id.xproulette.lootbox.LootBoxCommand;
import id.xproulette.lootbox.LootBoxEvent;
import id.xproulette.lootbox.LootBoxListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class XPRoulette extends JavaPlugin {

    private Messages messages;
    private RouletteManager manager;
    private EventManager eventManager;
    private LootBoxEvent lootBoxEvent;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        messages = new Messages(this);
        messages.load();

        manager = new RouletteManager(this, messages);
        manager.reload();
        manager.loadData();

        getServer().getPluginManager().registerEvents(new RouletteListener(manager), this);

        // Event system: XP Roulette (latar) + Loot Box, dikoordinasi lewat EventManager.
        eventManager = new EventManager(this);
        eventManager.registerEvent(new XpRouletteEvent());
        lootBoxEvent = new LootBoxEvent(this, messages);
        eventManager.registerEvent(lootBoxEvent);
        getServer().getPluginManager().registerEvents(new LootBoxListener(lootBoxEvent, messages), this);

        PluginCommand cmd = getCommand("xproulette");
        if (cmd != null) {
            XPRCommand handler = new XPRCommand(this, manager, messages,
                    new LootBoxCommand(eventManager, lootBoxEvent, messages));
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        manager.start();
        eventManager.start();
        // Pulihkan sesi Loot Box tersimpan setelah server & dunia siap.
        getServer().getScheduler().runTask(this, lootBoxEvent::restore);

        // Jika plugin di-load saat pemain sudah online (mis. /reload).
        for (Player p : getServer().getOnlinePlayers()) {
            manager.handleJoin(p);
        }

        getLogger().info("XP Roulette aktif - semoga beruntung!");
    }

    @Override
    public void onDisable() {
        if (eventManager != null) {
            eventManager.shutdown();
        }
        if (manager != null) {
            manager.shutdown();
        }
    }

    /** Dipanggil oleh /xpr reload. */
    public void reloadAll() {
        reloadConfig();
        messages.load();
        manager.reload();
        if (eventManager != null) eventManager.reload();
        if (lootBoxEvent != null) lootBoxEvent.reload();
    }
}
