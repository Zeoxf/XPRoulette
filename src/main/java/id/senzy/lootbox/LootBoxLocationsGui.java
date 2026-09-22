package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.config.MessageManager;
import id.senzy.gui.GuiItems;
import id.senzy.gui.SenzyGui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

/**
 * GUI admin "Senzy LootBox - Locations": World, Center X/Y/Z, Radius, Mode, jumlah LootBox aktif.
 * Klik kiri "Set Anchor" = pakai posisi pemain saat ini (mode POINT). Klik kanan Radius = -10, kiri = +10.
 */
public final class LootBoxLocationsGui implements SenzyGui {

    private static final int WORLD_SLOT = 10;
    private static final int CENTER_SLOT = 12;
    private static final int RADIUS_SLOT = 14;
    private static final int MODE_SLOT = 16;
    private static final int ACTIVE_SLOT = 22;
    private static final int SET_ANCHOR_SLOT = 30;
    private static final int RESET_SLOT = 32;
    private static final int BACK_SLOT = 49;

    private final SenzyPlugin plugin;
    private final Player viewer;
    private final Inventory inventory;

    public LootBoxLocationsGui(SenzyPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 45, plugin.messages().get("gui.locations.title"));
        render();
    }

    private void render() {
        inventory.clear();
        MessageManager msg = plugin.messages();
        LootBoxManager lb = plugin.lootbox();
        var area = lb.locations().area();
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, GuiItems.filler(Material.CYAN_STAINED_GLASS_PANE));

        inventory.setItem(WORLD_SLOT, GuiItems.of(Material.GRASS_BLOCK, msg.get("gui.locations.world.name"),
                msg.list("gui.locations.world.lore", "world", area.world()), false));
        inventory.setItem(CENTER_SLOT, GuiItems.of(Material.COMPASS, msg.get("gui.locations.center.name"),
                msg.list("gui.locations.center.lore", "x", area.centerX(), "y", area.centerY(), "z", area.centerZ()), false));
        inventory.setItem(RADIUS_SLOT, GuiItems.of(Material.STICK, msg.get("gui.locations.radius.name"),
                msg.list("gui.locations.radius.lore", "radius", area.effectiveRadius()), false));
        inventory.setItem(MODE_SLOT, GuiItems.of(Material.LEVER, msg.get("gui.locations.mode.name"),
                msg.list("gui.locations.mode.lore", "mode", area.mode().name()), false));
        inventory.setItem(ACTIVE_SLOT, GuiItems.of(Material.CHEST, msg.get("gui.locations.active.name"),
                msg.list("gui.locations.active.lore", "count", lb.unopenedCount(), "total", lb.totalCount()), false));

        inventory.setItem(SET_ANCHOR_SLOT, GuiItems.of(Material.LIME_CONCRETE, msg.get("gui.locations.set.name"),
                msg.list("gui.locations.set.lore"), true));
        inventory.setItem(RESET_SLOT, GuiItems.of(Material.RED_CONCRETE, msg.get("gui.locations.reset.name"),
                msg.list("gui.locations.reset.lore"), false));
        inventory.setItem(BACK_SLOT, GuiItems.of(Material.ARROW, msg.get("gui.locations.back.name"), java.util.List.of(), false));
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        LootBoxManager lb = plugin.lootbox();

        if (slot == BACK_SLOT) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) plugin.gui().open(viewer, new LootBoxGui(plugin, viewer));
            });
            return;
        }
        if (slot == SET_ANCHOR_SLOT) {
            Location at = viewer.getLocation();
            lb.locations().setAnchor(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), true);
            plugin.messages().send(viewer, "lootbox.admin.anchor-set",
                    "world", at.getWorld().getName(), "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ());
            render();
            return;
        }
        if (slot == RADIUS_SLOT) {
            var area = lb.locations().area();
            int delta = e.getClick() == ClickType.RIGHT ? -10 : 10;
            int next = Math.max(1, area.effectiveRadius() + delta);
            if (area.mode() == LootBoxLocationManager.Mode.POINT) {
                lb.locations().setAdjustRadius(next);
            } else {
                lb.locations().setRadius(next);
            }
            render();
            return;
        }
        if (slot == MODE_SLOT) {
            var order = LootBoxLocationManager.Mode.values();
            var cur = lb.locations().area().mode();
            var next = order[(cur.ordinal() + 1) % order.length];
            lb.locations().setMode(next);
            render();
            return;
        }
        if (slot == RESET_SLOT) {
            lb.locations().resetToConfig();
            plugin.messages().send(viewer, "lootbox.admin.area-reset");
            render();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
