package id.xproulette.lootbox;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Memilih pemain yang menjadi pusat area event. Modular: mode baru (highest-level,
 * lowest-level, random-participant, ...) cukup menambah implementasi + satu baris di {@link #create}.
 */
public interface PlayerSelector {

    Optional<Player> select();

    static PlayerSelector create(String mode, LootBoxSettings settings, Logger log) {
        String m = mode == null ? "" : mode.trim().toLowerCase();
        if (m.equals("random-online-player")) {
            return new RandomOnlinePlayer(settings);
        }
        log.warning("player-selection.mode '" + mode + "' belum didukung, memakai random-online-player");
        return new RandomOnlinePlayer(settings);
    }

    /** Pemain online yang valid dipilih acak dengan peluang sama. */
    final class RandomOnlinePlayer implements PlayerSelector {

        private final LootBoxSettings settings;

        RandomOnlinePlayer(LootBoxSettings settings) {
            this.settings = settings;
        }

        @Override
        public Optional<Player> select() {
            List<Player> eligible = new ArrayList<>(Bukkit.getOnlinePlayers());
            eligible.removeIf(p -> !isValid(p));
            if (eligible.isEmpty()) return Optional.empty();
            return Optional.of(eligible.get(ThreadLocalRandom.current().nextInt(eligible.size())));
        }

        private boolean isValid(Player p) {
            return p.isOnline()
                    && !p.isDead()
                    && p.getGameMode() != GameMode.SPECTATOR
                    && settings.isWorldAllowed(p.getWorld());
        }
    }
}
