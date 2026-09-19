package id.xproulette;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

final class RouletteListener implements Listener {

    private final RouletteManager manager;

    RouletteListener(RouletteManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        manager.handleJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.handleQuit(e.getPlayer());
    }

    /** Aturan utama: mati saat efek aktif = efek hangus. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        manager.handleDeath(e.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        manager.handleRespawn(e.getPlayer());
    }
}
