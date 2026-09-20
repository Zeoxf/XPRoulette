package id.xproulette.event;

import id.xproulette.util.Configs;
import id.xproulette.util.TimeParser;
import id.xproulette.util.WeightedRandom;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Pusat pengelolaan event.
 *
 * <ul>
 *   <li>Menyimpan semua {@link GameEvent} yang terdaftar (XP Roulette, Loot Box, ...).</li>
 *   <li>Mencegah tabrakan: hanya SATU event eksklusif yang boleh non-IDLE (termasuk COOLDOWN).</li>
 *   <li>EventScheduler: satu timer 1 detik yang men-tick semua event dan memilih event
 *       berikutnya lewat weighted RNG (events.yml -> event-types).</li>
 * </ul>
 */
public final class EventManager {

    private final JavaPlugin plugin;
    private final Map<String, GameEvent> events = new LinkedHashMap<>();
    private final Map<EventType, Boolean> typeEnabled = new EnumMap<>(EventType.class);
    private final Map<EventType, Integer> typeWeight = new EnumMap<>(EventType.class);

    private BukkitTask task;
    private boolean autoStart = true;
    private long firstDelayMillis = 600_000L;
    private long retryMillis = 300_000L;
    private long nextAttemptAt;
    private boolean debug;

    public EventManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
        nextAttemptAt = System.currentTimeMillis() + firstDelayMillis;
    }

    // ------------------------------------------------------------------ config

    public void reload() {
        YamlConfiguration c = Configs.load(plugin, "events.yml");
        autoStart = c.getBoolean("scheduler.auto-start", true);
        firstDelayMillis = TimeParser.parseSeconds(c.getString("scheduler.first-delay", "10m"), 600) * 1000L;
        retryMillis = Math.max(10, TimeParser.parseSeconds(c.getString("scheduler.retry-interval", "5m"), 300)) * 1000L;
        debug = c.getBoolean("debug", false);

        typeEnabled.clear();
        typeWeight.clear();
        for (EventType type : EventType.values()) {
            String base = "event-types." + type.configKey();
            typeEnabled.put(type, c.getBoolean(base + ".enabled", false));
            typeWeight.put(type, Math.max(0, c.getInt(base + ".weight", 0)));
        }
    }

    public boolean isTypeEnabled(EventType type) {
        return typeEnabled.getOrDefault(type, false);
    }

    public int getTypeWeight(EventType type) {
        return typeWeight.getOrDefault(type, 0);
    }

    // ---------------------------------------------------------------- registry

    public void registerEvent(GameEvent event) {
        GameEvent old = events.put(event.getId(), event);
        if (old != null && old != event) {
            plugin.getLogger().warning("Event '" + event.getId() + "' didaftarkan ulang, yang lama diganti.");
        }
    }

    /** Menghentikan event bila masih berjalan lalu melepasnya dari registry. */
    public boolean unregisterEvent(String id) {
        GameEvent e = events.remove(id);
        if (e == null) return false;
        if (e.getState().isRunning()) e.stop();
        return true;
    }

    public GameEvent getEvent(String id) {
        return events.get(id);
    }

    public boolean isEventActive(String id) {
        GameEvent e = events.get(id);
        return e != null && e.getState() == EventState.ACTIVE;
    }

    public List<GameEvent> getActiveEvents() {
        List<GameEvent> out = new ArrayList<>();
        for (GameEvent e : events.values()) {
            if (e.getState() == EventState.ACTIVE) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------ start / stop

    public StartCheck checkStart(String id) {
        GameEvent e = events.get(id);
        if (e == null) return StartCheck.NOT_REGISTERED;
        if (!e.isEnabled()) return StartCheck.DISABLED;

        EventState st = e.getState();
        if (st == EventState.COOLDOWN) return StartCheck.COOLDOWN;
        if (st != EventState.IDLE) return StartCheck.ALREADY_RUNNING;

        if (e.isExclusive()) {
            for (GameEvent other : events.values()) {
                if (other != e && other.isExclusive() && other.getState() != EventState.IDLE) {
                    return StartCheck.BLOCKED_BY_OTHER_EVENT;
                }
            }
        }
        return e.canStart() ? StartCheck.OK : StartCheck.NOT_READY;
    }

    public boolean canStartEvent(String id) {
        return checkStart(id) == StartCheck.OK;
    }

    public boolean startEvent(String id) {
        if (!canStartEvent(id)) return false;
        return events.get(id).start();
    }

    /** @return true jika ada event berjalan yang dihentikan. */
    public boolean stopEvent(String id) {
        GameEvent e = events.get(id);
        if (e == null) return false;
        EventState st = e.getState();
        if (st == EventState.IDLE || st == EventState.COOLDOWN) return false;
        e.stop();
        return true;
    }

    // --------------------------------------------------------------- scheduler

    public void start() {
        if (task != null) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (GameEvent e : new ArrayList<>(events.values())) {
            try {
                e.shutdown();
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Gagal shutdown event " + e.getId(), ex);
            }
        }
    }

    /** Detik sampai percobaan auto-start berikutnya (0 = sekarang / tidak dijadwalkan). */
    public long secondsUntilNextAttempt() {
        return Math.max(0, (nextAttemptAt - System.currentTimeMillis()) / 1000L);
    }

    private void tick() {
        for (GameEvent e : new ArrayList<>(events.values())) {
            try {
                e.tick();
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Error saat tick event " + e.getId(), ex);
            }
        }
        if (autoStart) {
            try {
                scheduleNext();
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Error pada EventScheduler", ex);
            }
        }
    }

    /** Memilih event berikutnya dengan weighted RNG, hanya dari event yang enabled dan lolos aturan tabrakan. */
    private void scheduleNext() {
        long now = System.currentTimeMillis();
        if (now < nextAttemptAt) return;

        List<GameEvent> candidates = new ArrayList<>();
        for (GameEvent e : events.values()) {
            EventType type = e.getType();
            if (!type.isRandomlyScheduled() || !isTypeEnabled(type) || getTypeWeight(type) <= 0) continue;
            if (checkStart(e.getId()) == StartCheck.OK) candidates.add(e);
        }

        if (candidates.isEmpty()) {
            // Tidak ada yang boleh jalan (mis. sedang ada event eksklusif / cooldown): cek lagi sebentar.
            nextAttemptAt = now + 5_000L;
            return;
        }

        GameEvent chosen = WeightedRandom.chooseWeighted(candidates, e -> getTypeWeight(e.getType()));
        if (chosen == null) {
            nextAttemptAt = now + retryMillis;
            return;
        }
        if (debug) plugin.getLogger().info("EventScheduler memilih event: " + chosen.getId());

        if (!startEvent(chosen.getId())) {
            nextAttemptAt = now + retryMillis;
            if (debug) {
                plugin.getLogger().info("Event " + chosen.getId() + " tidak bisa dimulai, coba lagi dalam "
                        + TimeParser.format(retryMillis / 1000L));
            }
        }
    }
}
