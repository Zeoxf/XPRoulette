package id.senzy;

import id.senzy.command.SenzyCommand;
import id.senzy.config.ConfigManager;
import id.senzy.config.MessageManager;
import id.senzy.data.DataManager;
import id.senzy.event.SenzyEventManager;
import id.senzy.gui.GuiManager;
import id.senzy.integration.SenzyPlaceholders;
import id.senzy.listener.LootBoxListener;
import id.senzy.listener.PlayerListener;
import id.senzy.lootbox.LootBoxManager;
import id.senzy.xpr.XPRManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SenzyPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private DataManager dataManager;
    private SenzyEventManager eventManager;
    private GuiManager guiManager;
    private XPRManager xprManager;
    private LootBoxManager lootBoxManager;
    private SenzyPlaceholders placeholders;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();
        messageManager = new MessageManager(this);
        messageManager.load();

        dataManager = new DataManager(this);
        guiManager = new GuiManager(this);
        eventManager = new SenzyEventManager(this);

        xprManager = new XPRManager(this);
        lootBoxManager = new LootBoxManager(this);
        eventManager.register(xprManager);
        eventManager.register(lootBoxManager);

        Bukkit.getPluginManager().registerEvents(guiManager, this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new LootBoxListener(this), this);

        dataManager.startAutosave();
        eventManager.enableAll();

        // Muat data pemain yang sudah online (mis. setelah /senzy reload atau plugman load).
        for (Player p : Bukkit.getOnlinePlayers()) {
            dataManager.load(p.getUniqueId(), p.getName());
            xprManager.onJoin(p);
            lootBoxManager.onJoin(p);
        }

        SenzyCommand command = new SenzyCommand(this);
        var pc = getCommand("senzy");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders = new SenzyPlaceholders(this);
            placeholders.register();
            getLogger().info("Integrasi PlaceholderAPI aktif.");
        }

        getLogger().info("Senzy aktif.");
    }

    @Override
    public void onDisable() {
        if (placeholders != null) placeholders.unregister();
        if (eventManager != null) eventManager.disableAll();
        if (guiManager != null) guiManager.closeAll();
        if (dataManager != null) dataManager.shutdown();
        getLogger().info("Senzy dimatikan.");
    }

    /** Reload penuh: config -> messages -> semua modul. Tidak menggandakan scheduler/beacon/event. */
    public void reloadSenzy() {
        configManager.load();
        messageManager.load();
        eventManager.reloadAll();
        dataManager.startAutosave();
    }

    public void debug(String message) {
        if (configManager != null && configManager.debug()) {
            getLogger().log(Level.INFO, "[DEBUG] " + message);
        }
    }

    public ConfigManager config() {
        return configManager;
    }

    public MessageManager messages() {
        return messageManager;
    }

    public DataManager data() {
        return dataManager;
    }

    public SenzyEventManager events() {
        return eventManager;
    }

    public GuiManager gui() {
        return guiManager;
    }

    public XPRManager xpr() {
        return xprManager;
    }

    public LootBoxManager lootbox() {
        return lootBoxManager;
    }
}
