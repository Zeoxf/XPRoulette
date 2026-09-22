package id.senzy.gui;

import id.senzy.SenzyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Router klik untuk semua GUI Senzy. Setiap interaksi dengan inventory Senzy SELALU dibatalkan
 * (tidak ada item yang bisa diambil, di-shift, di-hotkey, atau di-drag) sebelum diteruskan ke GUI.
 */
public final class GuiManager implements Listener {

    private final SenzyPlugin plugin;

    public GuiManager(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, SenzyGui gui) {
        player.openInventory(gui.getInventory());
    }

    /** Menutup semua GUI Senzy yang sedang terbuka (saat plugin mati). */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SenzyGui) {
                p.closeInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof SenzyGui gui)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        try {
            gui.onClick(e);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Error di GUI " + gui.getClass().getSimpleName() + ": " + ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof SenzyGui) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof SenzyGui gui) {
            gui.onClose(e);
        }
    }
}
