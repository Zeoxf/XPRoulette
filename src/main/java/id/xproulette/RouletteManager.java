package id.xproulette;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Otak dari XP Roulette.
 *
 * Model waktu (per pemain, hanya berjalan saat online):
 *   siklus = interval detik (default 30 menit)
 *   [0 .. active)        -> efek AKTIF (default 15 menit)
 *   [active .. interval) -> COOLDOWN   (default 15 menit)
 *   Jika pemain mati saat aktif -> efek hangus, sisa siklus menjadi cooldown.
 */
final class RouletteManager {

    private final XPRoulette plugin;
    private final Messages msg;
    private final EffectRoller roller = new EffectRoller();

    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Set<UUID> pendingDeathNotice = new HashSet<>();

    private YamlConfiguration store = new YamlConfiguration();
    private File storeFile;
    private BukkitTask tickTask;
    private BukkitTask saveTask;

    // ---- pengaturan (dibaca dari config.yml)
    private int interval = 1800;
    private int activeSeconds = 900;
    private int firstDelay = 60;
    private int flavorInterval = 180;
    private int broadcastMin = 8;
    private boolean bossbarEnabled = true;
    private boolean soundsEnabled = true;
    private boolean particlesEnabled = true;
    private boolean effectParticles = true;
    private Set<Integer> warnSeconds = new HashSet<>();

    RouletteManager(XPRoulette plugin, Messages msg) {
        this.plugin = plugin;
        this.msg = msg;
    }

    // =================================================================== SETUP

    void reload() {
        FileConfiguration c = plugin.getConfig();
        interval = Math.max(20, (int) Math.round(c.getDouble("cycle.interval-minutes", 30) * 60));
        activeSeconds = Math.min(interval,
                Math.max(5, (int) Math.round(c.getDouble("cycle.active-minutes", 15) * 60)));
        firstDelay = Math.max(1, c.getInt("cycle.first-join-delay-seconds", 60));

        bossbarEnabled = c.getBoolean("display.bossbar", true);
        soundsEnabled = c.getBoolean("display.sounds", true);
        particlesEnabled = c.getBoolean("display.particles", true);
        effectParticles = c.getBoolean("display.effect-particles", true);
        flavorInterval = c.getInt("display.flavor-interval-seconds", 180);
        broadcastMin = c.getInt("display.broadcast-min-multiplier", 8);
        warnSeconds = new HashSet<>(c.getIntegerList("display.warn-seconds"));

        roller.load(c, plugin.getLogger());
        if (!bossbarEnabled) hideAllBars();
    }

    void loadData() {
        storeFile = new File(plugin.getDataFolder(), "data.yml");
        store = YamlConfiguration.loadConfiguration(storeFile);
    }

    void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 20L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, 6000L, 6000L);
    }

    void shutdown() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        hideAllBars();
        saveAll();
    }

    // ============================================================ JOIN / QUIT

    void handleJoin(Player p) {
        UUID id = p.getUniqueId();
        PlayerData d = readData(id);
        boolean first = d == null;
        if (first) {
            d = new PlayerData();
            d.cycleElapsed = Math.max(0, interval - firstDelay);
        }
        players.put(id, d);

        // Config berubah / data basi -> pastikan status konsisten.
        if (d.active && d.cycleElapsed >= activeSeconds) {
            removeEffects(p, d);
            d.active = false;
            d.effects.clear();
        }
        if (d.active) {
            applyAll(p, d);
        }
        updateBar(p, d);

        final PlayerData data = d;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (first) {
                for (String line : msg.list("join.first")) {
                    p.sendMessage(msg.render(line, "interval", formatTime(interval),
                            "active", formatTime(activeSeconds)));
                }
            } else if (data.active) {
                p.sendMessage(msg.get("join.back-active", "time", formatTime(activeSeconds - data.cycleElapsed)));
            } else if (data.died) {
                p.sendMessage(msg.get("join.back-dead", "time", formatTime(interval - data.cycleElapsed)));
            } else {
                p.sendMessage(msg.get("join.back-cooldown", "time", formatTime(interval - data.cycleElapsed)));
            }
        }, 40L);
    }

    void handleQuit(Player p) {
        UUID id = p.getUniqueId();
        PlayerData d = players.remove(id);
        if (d != null) {
            writeData(id, d);
            saveFile();
        }
        BossBar bar = bars.remove(id);
        if (bar != null) p.hideBossBar(bar);
        pendingDeathNotice.remove(id);
    }

    // ================================================================== TICK

    private void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = players.get(p.getUniqueId());
            if (d == null || p.isDead()) continue;
            tick(p, d);
        }
    }

    private void tick(Player p, PlayerData d) {
        d.cycleElapsed++;

        // Siklus baru -> roda berputar lagi.
        if (d.cycleElapsed >= interval) {
            d.cycleElapsed = 0;
            d.died = false;
            if (d.active) {
                removeEffects(p, d);
                d.active = false;
                d.effects.clear();
            }
            activate(p, d);
            updateBar(p, d);
            return;
        }

        if (d.active) {
            if (d.cycleElapsed >= activeSeconds) {
                expire(p, d);
            } else {
                ensureEffects(p, d);
                int left = activeSeconds - d.cycleElapsed;
                if (warnSeconds.contains(left)) {
                    p.sendMessage(msg.get("warn-end", "time", formatTime(left)));
                    sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.8f);
                }
                if (flavorInterval > 0 && d.cycleElapsed % flavorInterval == 0) {
                    p.sendActionBar(msg.random(d.hasRisk() ? "flavor.risk" : "flavor.good"));
                }
            }
        } else {
            int left = interval - d.cycleElapsed;
            if (warnSeconds.contains(left)) {
                p.sendMessage(msg.get("warn-start", "time", formatTime(left)));
            }
            if (left <= 5) {
                p.showTitle(Title.title(
                        msg.get("countdown.title", "seconds", left),
                        msg.get("countdown.subtitle"),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ZERO)));
                sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.6f + (5 - left) * 0.2f);
            }
        }
        updateBar(p, d);
    }

    // ========================================================= AKTIVASI / END

    void activate(Player p, PlayerData d) {
        int level = p.getLevel();
        EffectRoller.Roll roll = roller.roll(level);

        d.active = true;
        d.died = false;
        d.rolledLevel = level;
        d.multiplier = roll.multiplier();
        d.effects = new ArrayList<>(roll.effects());

        applyAll(p, d);
        announceActivation(p, d);
    }

    private void announceActivation(Player p, PlayerData d) {
        String tierName = tierName(roller.tierOf(d.rolledLevel));
        boolean hasRisk = d.hasRisk();

        p.showTitle(Title.title(
                msg.get("activation.title"),
                msg.get("activation.subtitle", "level", d.rolledLevel, "multiplier", d.multiplier, "tier", tierName),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800))));

        p.sendMessage(msg.get("activation.header"));
        p.sendMessage(msg.random("activation.intros"));
        p.sendMessage(msg.get("activation.info", "level", d.rolledLevel, "tier", tierName,
                "multiplier", d.multiplier));
        for (RolledEffect e : d.effects) {
            p.sendMessage(msg.get(e.risk() ? "activation.risk-line" : "activation.good-line",
                    "effect", displayName(e), "flavor", flavorFor(e)));
        }
        if (hasRisk) {
            p.sendMessage(msg.random("activation.risk-warnings"));
        } else {
            p.sendMessage(msg.get("activation.no-risk"));
        }
        p.sendMessage(msg.get("activation.footer", "duration", formatTime(activeSeconds)));

        sound(p, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
        sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f);
        if (hasRisk) sound(p, Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.7f);

        if (particlesEnabled) {
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0),
                    60, 0.5, 0.8, 0.5, 0.3);
            if (hasRisk) {
                p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0),
                        25, 0.4, 0.8, 0.4, 0.05);
            }
        }

        if (broadcastMin > 0 && d.multiplier >= broadcastMin) {
            Bukkit.broadcast(msg.get("broadcast.high", "player", p.getName(), "multiplier", d.multiplier));
        }
    }

    private void expire(Player p, PlayerData d) {
        removeEffects(p, d);
        d.active = false;
        d.effects.clear();

        p.showTitle(Title.title(
                msg.get("expire.title"),
                msg.get("expire.subtitle"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
        p.sendMessage(msg.get("expire.chat", "time", formatTime(interval - d.cycleElapsed)));
        sound(p, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f);
    }

    // ============================================================ DEATH RULE

    /** Mati saat efek aktif = efek hangus. Sisa siklus jadi cooldown. */
    void handleDeath(Player p) {
        PlayerData d = players.get(p.getUniqueId());
        if (d == null || !d.active) return;

        int mult = d.multiplier;
        removeEffects(p, d);
        d.active = false;
        d.died = true;
        d.effects.clear();
        pendingDeathNotice.add(p.getUniqueId());

        if (broadcastMin > 0 && mult >= broadcastMin) {
            Bukkit.broadcast(msg.get("broadcast.death", "player", p.getName(), "multiplier", mult));
        }
    }

    void handleRespawn(Player p) {
        if (!pendingDeathNotice.remove(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlayerData d = players.get(p.getUniqueId());
            if (!p.isOnline() || d == null) return;
            p.showTitle(Title.title(
                    msg.get("death.title"),
                    msg.get("death.subtitle"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            p.sendMessage(msg.get("death.chat", "time", formatTime(interval - d.cycleElapsed)));
            p.sendMessage(msg.random("death.quips"));
            sound(p, Sound.ENTITY_WITHER_AMBIENT, 0.7f, 0.6f);
            updateBar(p, d);
        }, 20L);
    }

    // ================================================================ EFFECTS

    private PotionEffect build(PotionEffectType type, int amplifier, int ticks) {
        return new PotionEffect(type, ticks, amplifier, false, effectParticles, true);
    }

    private void applyAll(Player p, PlayerData d) {
        int ticks = Math.max(40, (activeSeconds - d.cycleElapsed) * 20 + 20);
        for (RolledEffect e : d.effects) {
            PotionEffectType type = EffectRoller.typeOf(e.key());
            if (type == null) continue;
            p.addPotionEffect(build(type, e.level() - 1, ticks));
        }
    }

    /** Dipanggil tiap detik: pulihkan efek yang hilang (minum susu, totem, dll). */
    private void ensureEffects(Player p, PlayerData d) {
        int ticks = (activeSeconds - d.cycleElapsed) * 20 + 20;
        for (RolledEffect e : d.effects) {
            PotionEffectType type = EffectRoller.typeOf(e.key());
            if (type == null) continue;
            int amp = e.level() - 1;
            PotionEffect cur = p.getPotionEffect(type);
            boolean missing = cur == null || cur.getAmplifier() < amp;
            boolean expiring = cur != null && cur.getAmplifier() == amp
                    && cur.getDuration() >= 0 && cur.getDuration() < 40 && ticks > 60;
            if (missing || expiring) {
                p.addPotionEffect(build(type, amp, ticks));
            }
        }
    }

    private void removeEffects(Player p, PlayerData d) {
        for (RolledEffect e : d.effects) {
            PotionEffectType type = EffectRoller.typeOf(e.key());
            if (type == null) continue;
            PotionEffect cur = p.getPotionEffect(type);
            // Jangan hapus efek vanilla yang lebih kuat milik pemain.
            if (cur != null && cur.getAmplifier() <= e.level() - 1) {
                p.removePotionEffect(type);
            }
        }
    }

    // ================================================================ BOSSBAR

    private void updateBar(Player p, PlayerData d) {
        if (!bossbarEnabled) return;
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        }

        if (d.active) {
            int left = Math.max(0, activeSeconds - d.cycleElapsed);
            boolean risk = d.hasRisk();
            bar.name(msg.get(risk ? "bossbar.active-risk" : "bossbar.active",
                    "multiplier", d.multiplier, "time", formatTime(left)));
            bar.color(risk ? BossBar.Color.RED : BossBar.Color.GREEN);
            bar.progress(clamp(left / (float) activeSeconds));
        } else {
            int left = Math.max(0, interval - d.cycleElapsed);
            bar.name(msg.get(d.died ? "bossbar.dead" : "bossbar.cooldown", "time", formatTime(left)));
            bar.color(d.died ? BossBar.Color.WHITE : BossBar.Color.BLUE);
            bar.progress(clamp(d.cycleElapsed / (float) interval));
        }
    }

    private void hideAllBars() {
        for (Map.Entry<UUID, BossBar> en : bars.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) p.hideBossBar(en.getValue());
        }
        bars.clear();
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ============================================================= PERINTAH

    void sendStatus(Player p) {
        PlayerData d = players.get(p.getUniqueId());
        if (d == null) return;

        int level = p.getLevel();
        int tier = roller.tierOf(level);

        p.sendMessage(msg.get("status.header"));
        p.sendMessage(msg.get("status.level", "level", level, "tier", tierName(tier),
                "multiplier", roller.multiplierFor(tier)));

        if (d.active) {
            p.sendMessage(msg.get("status.active", "time", formatTime(activeSeconds - d.cycleElapsed),
                    "multiplier", d.multiplier, "level", d.rolledLevel));
            for (RolledEffect e : d.effects) {
                p.sendMessage(msg.get(e.risk() ? "status.risk-line" : "status.good-line",
                        "effect", displayName(e)));
            }
        } else {
            p.sendMessage(msg.get(d.died ? "status.dead" : "status.cooldown",
                    "time", formatTime(interval - d.cycleElapsed)));
        }
        p.sendMessage(msg.get("status.next", "levels", roller.levelsToNextTier(level),
                "multiplier", roller.multiplierFor(tier + 1)));
    }

    void sendRules(CommandSender s) {
        for (String line : msg.list("rules.lines")) {
            s.sendMessage(msg.render(line,
                    "interval", formatTime(interval),
                    "active", formatTime(activeSeconds),
                    "cooldown", formatTime(interval - activeSeconds),
                    "per_tier", roller.levelsPerTier(),
                    "first_end", roller.levelsPerTier() - 1,
                    "ladder", roller.ladder(6)));
        }
    }

    void sendPreview(CommandSender s, Integer onlyLevel) {
        s.sendMessage(msg.get("preview.header"));
        List<Integer> levels = new ArrayList<>();
        if (onlyLevel != null) {
            levels.add(onlyLevel);
        } else {
            int step = roller.levelsPerTier();
            for (int i = 0; i < 7; i++) levels.add(i * step);
        }
        for (int lvl : levels) {
            int tier = roller.tierOf(lvl);
            s.sendMessage(msg.get("preview.line",
                    "level", lvl,
                    "multiplier", roller.multiplierFor(tier),
                    "good", roller.goodCountFor(tier),
                    "chance", (int) Math.round(roller.riskChanceFor(tier) * 100)));
        }
    }

    void forceActivate(Player p) {
        PlayerData d = players.get(p.getUniqueId());
        if (d == null) return;
        if (d.active) removeEffects(p, d);
        d.cycleElapsed = 0;
        d.died = false;
        activate(p, d);
        updateBar(p, d);
    }

    void resetPlayer(Player p) {
        PlayerData old = players.get(p.getUniqueId());
        if (old != null) removeEffects(p, old);
        PlayerData d = new PlayerData();
        d.cycleElapsed = Math.max(0, interval - firstDelay);
        players.put(p.getUniqueId(), d);
        updateBar(p, d);
    }

    // ============================================================ PERSISTENSI

    private PlayerData readData(UUID id) {
        String base = "players." + id;
        if (!store.contains(base)) return null;
        PlayerData d = new PlayerData();
        d.cycleElapsed = store.getInt(base + ".cycle");
        d.active = store.getBoolean(base + ".active");
        d.died = store.getBoolean(base + ".died");
        d.rolledLevel = store.getInt(base + ".rolled-level");
        d.multiplier = Math.max(1, store.getInt(base + ".multiplier", 1));
        for (String s : store.getStringList(base + ".effects")) {
            String[] parts = s.split(":");
            if (parts.length != 3) continue;
            try {
                d.effects.add(new RolledEffect(parts[0], Integer.parseInt(parts[1]), Boolean.parseBoolean(parts[2])));
            } catch (NumberFormatException ignored) {
                // baris rusak dilewati
            }
        }
        return d;
    }

    private void writeData(UUID id, PlayerData d) {
        String base = "players." + id;
        store.set(base + ".cycle", d.cycleElapsed);
        store.set(base + ".active", d.active);
        store.set(base + ".died", d.died);
        store.set(base + ".rolled-level", d.rolledLevel);
        store.set(base + ".multiplier", d.multiplier);
        List<String> list = new ArrayList<>();
        for (RolledEffect e : d.effects) {
            list.add(e.key() + ":" + e.level() + ":" + e.risk());
        }
        store.set(base + ".effects", list);
    }

    void saveAll() {
        for (Map.Entry<UUID, PlayerData> en : players.entrySet()) {
            writeData(en.getKey(), en.getValue());
        }
        saveFile();
    }

    private void saveFile() {
        if (storeFile == null) return;
        try {
            store.save(storeFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Gagal menyimpan data.yml: " + ex.getMessage());
        }
    }

    // ================================================================ HELPER

    private void sound(Player p, Sound s, float volume, float pitch) {
        if (soundsEnabled) p.playSound(p.getLocation(), s, volume, pitch);
    }

    private String tierName(int tier) {
        List<String> names = msg.list("tier-names");
        if (names.isEmpty()) return "Tier " + tier;
        return names.get(Math.min(tier, names.size() - 1));
    }

    private String flavorFor(RolledEffect e) {
        String path = "effect-flavor." + e.key();
        if (msg.has(path)) return msg.raw(path);
        return msg.pick(e.risk() ? "effect-flavor-default.risk" : "effect-flavor-default.good");
    }

    static String displayName(RolledEffect e) {
        StringBuilder sb = new StringBuilder();
        for (String part : e.key().split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.append(' ').append(roman(e.level())).toString();
    }

    private static String roman(int n) {
        if (n <= 0 || n > 10) return String.valueOf(n);
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return r[n];
    }

    static String formatTime(int seconds) {
        seconds = Math.max(0, seconds);
        int m = seconds / 60;
        int s = seconds % 60;
        if (m == 0) return s + "s";
        if (s == 0) return m + "m";
        return m + "m " + s + "s";
    }
}
