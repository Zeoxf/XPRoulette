package id.senzy.xpr;

import id.senzy.SenzyPlugin;
import id.senzy.data.PlayerData;
import id.senzy.gui.GuiItems;
import id.senzy.gui.SenzyGui;
import id.senzy.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;

/** Konfirmasi membuka tier berikutnya saat boost mencapai Level max (auto-advance = false). */
public final class TierConfirmGui implements SenzyGui {

    private static final int UNLOCK_SLOT = 11;
    private static final int INFO_SLOT = 13;
    private static final int CANCEL_SLOT = 15;

    private final SenzyPlugin plugin;
    private final String boostId;
    private final Inventory inventory;

    public TierConfirmGui(SenzyPlugin plugin, Player viewer, String boostId) {
        this.plugin = plugin;
        this.boostId = boostId;
        Boost b = plugin.xpr().boosts().get(boostId);
        String name = b == null ? boostId : b.displayName();
        PlayerData d = plugin.xpr().dataOf(viewer);
        PlayerData.BoostState st = d == null ? null : d.state(boostId);
        int tier = st == null ? 1 : st.tier;
        this.inventory = Bukkit.createInventory(this, 27, plugin.messages().get("gui.confirm.title", "boost", name));

        Object[] ph = {"boost", name, "tier", TextUtil.roman(tier), "next", TextUtil.roman(tier + 1),
                "max", plugin.xpr().boosts().maxLevel()};
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, GuiItems.filler(Material.GRAY_STAINED_GLASS_PANE));
        inventory.setItem(UNLOCK_SLOT, GuiItems.of(Material.LIME_CONCRETE,
                plugin.messages().get("gui.confirm.unlock.name", ph), plugin.messages().list("gui.confirm.unlock.lore", ph), true));
        inventory.setItem(INFO_SLOT, GuiItems.of(b == null ? Material.PAPER : b.icon(),
                plugin.messages().get("gui.confirm.info.name", ph), plugin.messages().list("gui.confirm.info.lore", ph), false));
        inventory.setItem(CANCEL_SLOT, GuiItems.of(Material.RED_CONCRETE,
                plugin.messages().get("gui.confirm.cancel.name", ph), List.of(), false));
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        if (slot == UNLOCK_SLOT) {
            plugin.xpr().advance(p, boostId);
            reopenMain(p);
        } else if (slot == CANCEL_SLOT) {
            reopenMain(p);
        }
    }

    private void reopenMain(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.gui().open(p, new XPRGui(plugin, p));
        });
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
