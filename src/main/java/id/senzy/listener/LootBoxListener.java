package id.senzy.listener;

import id.senzy.SenzyPlugin;
import id.senzy.lootbox.LootBox;
import id.senzy.lootbox.LootBoxManager;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Melindungi struktur LootBox/beacon dari perusakan pemain, ledakan, dan piston, dan menangani
 * klik-kanan LootBox sebagai "buka". Beacon TIDAK pernah memberi efek buff: potion effect dari
 * beacon vanilla tidak pernah dipasang karena beacon di sini tidak diberi primary effect lewat GUI beacon
 * bawaan (blok hanya dipasang lewat kode, bukan diaktifkan pemain), tapi untuk berjaga-jaga interaksi
 * langsung dengan beacon (klik kanan) tetap dibatalkan di bawah.
 */
public final class LootBoxListener implements Listener {

    private final SenzyPlugin plugin;

    public LootBoxListener(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    private LootBoxManager m() {
        return plugin.lootbox();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block clicked = e.getClickedBlock();
        LootBox box = m().protectedAt(clicked);
        if (box == null) return;
        e.setCancelled(true);
        if (!m().isBoxBlock(box, clicked)) return; // klik pada beacon/alas: hanya batalkan, tidak membuka
        Player p = e.getPlayer();
        m().open(p, box);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (m().protectedAt(e.getBlock()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> m().protectedAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> m().protectedAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (m().protectedAt(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
        if (m().protectedAt(e.getBlock().getRelative(e.getDirection())) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (m().protectedAt(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        m().onWorldLoad(e.getWorld());
    }
}
