package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.config.MessageManager;
import id.senzy.gui.GuiItems;
import id.senzy.gui.SenzyGui;
import id.senzy.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;

/**
 * GUI "Senzy LootBox": status event, sisa waktu, jumlah LootBox, area spawn, rarity rate.
 * Tombol Locations/Start/Stop hanya tampil untuk admin (senzy.lootbox.admin).
 */
public final class LootBoxGui implements SenzyGui {

    private static final int STATUS_SLOT = 11;
    private static final int AREA_SLOT = 13;
    private static final int RATES_SLOT = 15;
    private static final int LOCATIONS_SLOT = 28;
    private static final int START_SLOT = 30;
    private static final int STOP_SLOT = 32;
    private static final int RULES_SLOT = 34;
    private static final int CLOSE_SLOT = 49;

    private final SenzyPlugin plugin;
    private final Player viewer;
    private final boolean admin;
    private final Inventory inventory;

    public LootBoxGui(SenzyPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.admin = viewer.hasPermission("senzy.lootbox.admin");
        this.inventory = Bukkit.createInventory(this, 45, plugin.messages().get("gui.lootbox.title"));
        render();
    }

    private void render() {
        inventory.clear();
        MessageManager msg = plugin.messages();
        LootBoxManager lb = plugin.lootbox();
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, GuiItems.filler(Material.BLUE_STAINED_GLASS_PANE));

        boolean active = lb.isActive();
        Object[] statusPh = {
                "status", active ? msg.raw("gui.lootbox.status.active") : msg.raw("gui.lootbox.status.inactive"),
                "time", active ? TimeUtil.format(lb.remainingMillis()) : "-",
                "opened", lb.openedCount(),
                "total", active ? lb.totalCount() : lb.configuredAmount(),
                "left", lb.unopenedCount()
        };
        inventory.setItem(STATUS_SLOT, GuiItems.of(active ? Material.CLOCK : Material.BARRIER,
                msg.get("gui.lootbox.status.name", statusPh), msg.list("gui.lootbox.status.lore", statusPh), active));

        var area = lb.locations().area();
        Object[] areaPh = {
                "world", area.world(), "mode", area.mode().name(),
                "x", area.centerX(), "y", area.centerY(), "z", area.centerZ(),
                "radius", area.effectiveRadius()
        };
        inventory.setItem(AREA_SLOT, GuiItems.of(Material.MAP,
                msg.get("gui.lootbox.area.name", areaPh), msg.list("gui.lootbox.area.lore", areaPh), false));

        Object[] ratePh = {
                "common", fmt(lb.rarityPercent(LootBoxRarity.COMMON)),
                "rare", fmt(lb.rarityPercent(LootBoxRarity.RARE)),
                "epic", fmt(lb.rarityPercent(LootBoxRarity.EPIC)),
                "legendary", fmt(lb.rarityPercent(LootBoxRarity.LEGENDARY))
        };
        inventory.setItem(RATES_SLOT, GuiItems.of(Material.GOLD_NUGGET,
                msg.get("gui.lootbox.rates.name"), msg.list("gui.lootbox.rates.lore", ratePh), false));

        inventory.setItem(RULES_SLOT, GuiItems.of(Material.BOOK, msg.get("gui.lootbox.rules.name"),
                msg.list("gui.lootbox.rules.lore"), false));

        if (admin) {
            inventory.setItem(LOCATIONS_SLOT, GuiItems.of(Material.COMPASS,
                    msg.get("gui.lootbox.locations.name"), msg.list("gui.lootbox.locations.lore"), false));
            inventory.setItem(START_SLOT, GuiItems.of(active ? Material.GRAY_DYE : Material.LIME_DYE,
                    msg.get("gui.lootbox.start.name"), msg.list("gui.lootbox.start.lore"), false));
            inventory.setItem(STOP_SLOT, GuiItems.of(active ? Material.RED_DYE : Material.GRAY_DYE,
                    msg.get("gui.lootbox.stop.name"), msg.list("gui.lootbox.stop.lore"), false));
        }
        inventory.setItem(CLOSE_SLOT, GuiItems.of(Material.BARRIER, msg.get("gui.xpr.close.name"), List.of(), false));
    }

    private String fmt(double v) {
        return (v == Math.floor(v)) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        LootBoxManager lb = plugin.lootbox();
        if (slot == CLOSE_SLOT) {
            viewer.closeInventory();
            return;
        }
        if (!admin) return;
        if (slot == LOCATIONS_SLOT) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) plugin.gui().open(viewer, new LootBoxLocationsGui(plugin, viewer));
            });
        } else if (slot == START_SLOT && !lb.isActive()) {
            lb.start(viewer);
            refreshLater();
        } else if (slot == STOP_SLOT && lb.isActive()) {
            lb.stop(viewer);
            refreshLater();
        }
    }

    private void refreshLater() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (viewer.isOnline() && viewer.getOpenInventory().getTopInventory().getHolder() == this) render();
        }, 5L);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
