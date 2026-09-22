package id.senzy.lootbox;

import id.senzy.SenzyPlugin;
import id.senzy.config.MessageManager;
import id.senzy.event.SenzyModule;
import id.senzy.lootbox.LootBoxLocationManager.Area;
import id.senzy.lootbox.LootBoxSpawner.SpawnPoint;
import id.senzy.util.EffectUtil;
import id.senzy.util.LocationUtil.BlockPos;
import id.senzy.util.RandomUtil;
import id.senzy.util.TextUtil;
import id.senzy.util.TimeUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Event LootBox: sesi 1 jam berisi N LootBox berbobot rarity, ditandai beacon, bertahan saat restart.
 * Semua method dipanggil dari main thread.
 */
public final class LootBoxManager implements SenzyModule {

    public enum EndReason { TIMEOUT, STOPPED, ALL_OPENED, RESET }

    private final SenzyPlugin plugin;
    private final LootBoxLocationManager locations;
    private final LootBoxBeaconManager beacons;
    private final LootBoxSpawner spawner;
    private final LootTable loot;

    private LootBoxData state = new LootBoxData();
    private final Map<BlockPos, LootBox> protectedBlocks = new HashMap<>();
    private BossBar bar;
    private BukkitTask tickTask;
    private boolean starting;
    private int startToken;
    private long bootTime;

    // cache config
    private boolean enabled = true;
    private int amount = 5;
    private long sessionMillis = 3_600_000L;
    private int minimumSpawns = 3;
    private int extraRounds = 1;
    private boolean endWhenAllOpened = true;
    private boolean revealCoordinates;
    private final Map<LootBoxRarity, Integer> weights = new EnumMap<>(LootBoxRarity.class);
    private final Map<LootBoxRarity, Boolean> announce = new EnumMap<>(LootBoxRarity.class);
    private boolean bossbarEnabled = true;
    private BossBar.Color barColor = BossBar.Color.YELLOW;
    private BossBar.Overlay barOverlay = BossBar.Overlay.PROGRESS;
    private boolean autoStart;
    private long autoInterval = 10_800_000L;
    private int autoMinPlayers = 1;

    public LootBoxManager(SenzyPlugin plugin) {
        this.plugin = plugin;
        this.locations = new LootBoxLocationManager(plugin);
        this.beacons = new LootBoxBeaconManager(plugin);
        this.spawner = new LootBoxSpawner(plugin);
        this.loot = new LootTable(plugin);
    }

    @Override
    public String id() {
        return "lootbox";
    }

    // ------------------------------------------------------------ daur hidup

    @Override
    public void enable() {
        bootTime = System.currentTimeMillis();
        loadSettings();
        state = LootBoxData.load(plugin.data());
        recoverAfterRestart();
        startRuntime(true);
    }

    @Override
    public void disable() {
        stopRuntime();
        // Struktur sengaja DIBIARKAN di dunia; state di disk cukup untuk memulihkannya setelah restart.
        state.save(plugin.data());
    }

    /** Reload tidak menggandakan task/hologram dan tidak mengganggu session yang sedang berjalan. */
    @Override
    public void reload() {
        stopRuntime();
        loadSettings();
        startRuntime(false);
    }

    private void loadSettings() {
        FileConfiguration c = plugin.config().cfg();
        enabled = c.getBoolean("lootbox.enabled", true);
        amount = Math.max(1, c.getInt("lootbox.amount", 5));
        sessionMillis = plugin.config().duration("lootbox.session-duration", 3_600_000L);
        minimumSpawns = Math.max(1, Math.min(amount, c.getInt("lootbox.spawn.minimum-successful-spawns", 3)));
        extraRounds = Math.max(0, c.getInt("lootbox.spawn.extra-rounds", 1));
        endWhenAllOpened = c.getBoolean("lootbox.end-when-all-opened", true);
        revealCoordinates = c.getBoolean("lootbox.reveal-coordinates", false);

        int[] defaultWeights = {50, 24, 25, 1};
        boolean[] defaultAnnounce = {false, false, true, true};
        int sum = 0;
        for (LootBoxRarity r : LootBoxRarity.values()) {
            int w = Math.max(0, c.getInt("lootbox.rarity." + r.id(), defaultWeights[r.ordinal()]));
            weights.put(r, w);
            sum += w;
            announce.put(r, c.getBoolean("lootbox.announcements." + r.id(), defaultAnnounce[r.ordinal()]));
        }
        if (sum != 100) {
            plugin.getLogger().warning("lootbox.rarity: total bobot = " + sum + " (disarankan 100). Tetap dipakai sebagai bobot relatif.");
        }

        bossbarEnabled = c.getBoolean("lootbox.bossbar.enabled", true);
        barColor = enumOf(BossBar.Color.class, c.getString("lootbox.bossbar.color", "YELLOW"), BossBar.Color.YELLOW);
        barOverlay = enumOf(BossBar.Overlay.class, c.getString("lootbox.bossbar.overlay", "PROGRESS"), BossBar.Overlay.PROGRESS);
        autoStart = c.getBoolean("lootbox.auto-start.enabled", false);
        autoInterval = plugin.config().duration("lootbox.auto-start.interval", 10_800_000L);
        autoMinPlayers = Math.max(0, c.getInt("lootbox.auto-start.min-players", 1));

        locations.load();
        loot.load();
        beacons.reload();
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback;
        }
    }

    /** Session yang sudah lewat waktunya saat server mati harus dibersihkan (terrain dipulihkan). */
    private void recoverAfterRestart() {
        LootBoxSession s = state.session;
        if (s != null && s.active() && System.currentTimeMillis() >= s.endTime()) {
            plugin.getLogger().info("LootBox session " + s.eventId() + " sudah berakhir saat server mati; membersihkan.");
            finishSession(EndReason.TIMEOUT, false);
        }
    }

    private void startRuntime(boolean ensureStructures) {
        for (LootBox box : new ArrayList<>(state.boxes.values())) {
            registerProtection(box);
            if (ensureStructures && isActive() && !box.opened()) beacons.ensure(box);
        }
        if (!isActive()) retryPendingCleanup();
        beacons.startMarkers(this::activeBoxes);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        if (isActive()) showBar();
        if (isActive()) {
            plugin.getLogger().info("LootBox session " + state.session.eventId() + " dipulihkan: "
                    + unopenedCount() + "/" + state.session.amount() + " LootBox tersisa, sisa waktu "
                    + TimeUtil.format(remainingMillis()) + ".");
        }
    }

    private void stopRuntime() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        spawner.cancelAll();
        beacons.shutdown();
        hideBar();
        protectedBlocks.clear();
    }

    // ------------------------------------------------------------ status

    public boolean enabled() {
        return enabled;
    }

    public boolean isActive() {
        return state.session != null && state.session.active();
    }

    public boolean isStarting() {
        return starting;
    }

    public LootBoxSession session() {
        return state.session;
    }

    public LootBoxLocationManager locations() {
        return locations;
    }

    public int configuredAmount() {
        return amount;
    }

    public long remainingMillis() {
        return isActive() ? state.session.remaining(System.currentTimeMillis()) : 0L;
    }

    public int totalCount() {
        return isActive() ? state.session.amount() : 0;
    }

    public int openedCount() {
        int n = 0;
        for (LootBox b : state.boxes.values()) if (b.opened()) n++;
        return n;
    }

    public int unopenedCount() {
        int n = 0;
        for (LootBox b : state.boxes.values()) if (!b.opened()) n++;
        return n;
    }

    public boolean revealCoordinates() {
        return revealCoordinates;
    }

    /** Persentase peluang rarity (bobot / total * 100). */
    public double rarityPercent(LootBoxRarity r) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        return total <= 0 ? 0 : weights.get(r) * 100.0 / total;
    }

    /** LootBox yang aktif (belum dibuka) - kosong jika tidak ada session berjalan. */
    public Collection<LootBox> activeBoxes() {
        if (!isActive()) return List.of();
        List<LootBox> out = new ArrayList<>();
        for (LootBox b : state.boxes.values()) if (!b.opened()) out.add(b);
        return out;
    }

    /** LootBox aktif terdekat dari sebuah lokasi (dunia yang sama), atau null. */
    public LootBox nearest(Location from) {
        LootBox best = null;
        double bestDist = Double.MAX_VALUE;
        for (LootBox b : activeBoxes()) {
            if (from.getWorld() == null || !b.worldName().equals(from.getWorld().getName())) continue;
            double dx = b.x() + 0.5 - from.getX();
            double dy = b.y() - from.getY();
            double dz = b.z() + 0.5 - from.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = b;
            }
        }
        return best;
    }

    /** LootBox yang memiliki blok ini (struktur atau box itu sendiri), atau null. */
    public LootBox protectedAt(Block block) {
        return protectedBlocks.get(BlockPos.of(block));
    }

    public boolean isBoxBlock(LootBox box, Block block) {
        return box.x() == block.getX() && box.y() == block.getY() && box.z() == block.getZ()
                && box.worldName().equals(block.getWorld().getName());
    }

    // ------------------------------------------------------------ start / stop / reset

    public void start(CommandSender who) {
        MessageManager msg = plugin.messages();
        if (!enabled) {
            msg.send(who, "lootbox.disabled");
            return;
        }
        if (isActive()) {
            msg.send(who, "lootbox.admin.already-active");
            return;
        }
        if (starting) {
            msg.send(who, "lootbox.admin.already-starting");
            return;
        }
        Area area = locations.area();
        World world = Bukkit.getWorld(area.world());
        if (world == null) {
            msg.send(who, "lootbox.admin.world-missing", "world", area.world());
            return;
        }
        starting = true;
        int token = ++startToken;
        msg.send(who, "lootbox.admin.searching", "amount", amount);
        searchRound(who, world, area, new ArrayList<>(), extraRounds + 1, token);
    }

    private void searchRound(CommandSender who, World world, Area area, List<SpawnPoint> found, int roundsLeft, int token) {
        int need = amount - found.size();
        spawner.findLocations(world, area, need, new ArrayList<>(found)).whenComplete((points, err) -> runMain(() -> {
            if (token != startToken) return; // dibatalkan (reset/reload)
            if (err != null) {
                plugin.getLogger().warning("Pencarian lokasi LootBox gagal: " + err);
            } else if (points != null) {
                found.addAll(points);
            }
            if (err == null && found.size() < amount && roundsLeft > 1) {
                plugin.debug("LootBox: baru " + found.size() + "/" + amount + " lokasi, mencari lagi (sisa ronde " + (roundsLeft - 1) + ")");
                searchRound(who, world, area, found, roundsLeft - 1, token);
                return;
            }
            finishStart(who, world, area, found);
        }));
    }

    private void finishStart(CommandSender who, World world, Area area, List<SpawnPoint> found) {
        starting = false;
        MessageManager msg = plugin.messages();
        long now = System.currentTimeMillis();
        String eventId = String.format(Locale.ROOT, "lb-%04d", ++state.counter);
        String sessionId = UUID.randomUUID().toString();
        long end = now + sessionMillis;

        List<LootBox> built = new ArrayList<>();
        List<SpawnPoint> accepted = new ArrayList<>();
        int index = 0;
        for (SpawnPoint p : found) {
            world.getChunkAt(p.x() >> 4, p.z() >> 4); // sudah pernah dimuat/generate; pastikan termuat
            SpawnPoint ok = spawner.revalidate(world, area, p.x(), p.z(), accepted);
            if (ok == null) continue;
            accepted.add(ok);
            LootBoxRarity rarity = rollRarity();
            LootBox box = new LootBox(eventId + "-b" + (++index), eventId, sessionId, world.getName(),
                    ok.x() + 1, ok.y(), ok.z(), rarity, now, end, ok.beam());
            beacons.build(box, world);
            built.add(box);
            plugin.debug("LootBox selected rarity: box=" + box.id() + " rarity=" + rarity);
        }

        if (built.size() < minimumSpawns) {
            for (LootBox b : built) beacons.cleanup(b);
            msg.send(who, "lootbox.admin.start-failed", "found", built.size(), "minimum", minimumSpawns);
            state.save(plugin.data());
            return;
        }

        state.session = new LootBoxSession(eventId, sessionId, now, end, built.size());
        state.boxes.clear();
        for (LootBox b : built) {
            state.boxes.put(b.id(), b);
            registerProtection(b);
        }
        state.save(plugin.data());
        showBar();

        msg.send(who, "lootbox.admin.started", "count", built.size(), "amount", amount);
        msg.broadcast("lootbox.broadcast.start", "count", built.size(), "duration", TimeUtil.format(sessionMillis));
        if (revealCoordinates) {
            for (LootBox b : built) {
                msg.broadcast("lootbox.broadcast.coords", "rarity", msg.raw("rarity." + b.rarity().id()),
                        "world", b.worldName(), "x", b.x(), "y", b.y(), "z", b.z());
            }
        } else {
            msg.broadcast("lootbox.broadcast.hint");
        }
        playEffectsAll("start");
    }

    public void stop(CommandSender who) {
        if (!isActive()) {
            plugin.messages().send(who, "lootbox.admin.not-active");
            return;
        }
        finishSession(EndReason.STOPPED, true);
        plugin.messages().send(who, "lootbox.admin.stopped");
    }

    /** Menghentikan pencarian, membersihkan semua LootBox/beacon, dan menghapus state event. */
    public void reset(CommandSender who) {
        startToken++;
        starting = false;
        spawner.cancelAll();
        if (isActive()) finishSession(EndReason.RESET, true);
        retryPendingCleanup();
        state.session = null;
        state.lastEnd = 0L;
        state.save(plugin.data());
        plugin.messages().send(who, "lootbox.admin.reset");
    }

    private void finishSession(EndReason reason, boolean announceEnd) {
        LootBoxSession s = state.session;
        if (s == null || !s.active()) return;
        int opened = openedCount();
        int total = s.amount();
        s.end(reason.name());
        for (LootBox b : new ArrayList<>(state.boxes.values())) cleanupBox(b);
        state.boxes.values().removeIf(b -> b.originals().isEmpty()); // sisanya = world belum termuat, dicoba lagi nanti
        state.lastEnd = System.currentTimeMillis();
        hideBar();
        state.save(plugin.data());
        plugin.debug("LootBox session ended: " + s.eventId() + " reason=" + reason + " opened=" + opened + "/" + total);
        if (announceEnd) {
            String key = switch (reason) {
                case TIMEOUT -> "lootbox.ended.timeout";
                case ALL_OPENED -> "lootbox.ended.all-opened";
                case STOPPED -> "lootbox.ended.stopped";
                case RESET -> "lootbox.ended.reset";
            };
            plugin.messages().broadcast(key, "opened", opened, "total", total);
        }
    }

    // ------------------------------------------------------------ membuka LootBox

    public void open(Player p, LootBox box) {
        MessageManager msg = plugin.messages();
        LootBoxSession s = state.session;
        if (s == null || !s.active() || !s.sessionId().equals(box.sessionId())) {
            msg.send(p, "lootbox.open.inactive");
            return;
        }
        if (box.opened()) { // LootBox tidak pernah bisa dibuka dua kali
            msg.send(p, "lootbox.open.already");
            return;
        }
        if (!p.hasPermission("senzy.lootbox")) {
            msg.send(p, "lootbox.open.no-permission");
            return;
        }

        // Tandai dulu, baru beri hadiah: tidak ada celah double-open.
        box.markOpened(p.getUniqueId(), p.getName());
        List<ItemStack> rewards = loot.roll(box.rarity());
        Location at = new Location(p.getWorld(), box.x() + 0.5, box.y() + 0.5, box.z() + 0.5);
        Map<Integer, ItemStack> overflow = p.getInventory().addItem(rewards.toArray(new ItemStack[0]));
        for (ItemStack left : overflow.values()) p.getWorld().dropItemNaturally(p.getLocation(), left);

        playEffectsAt(box.rarity() == LootBoxRarity.LEGENDARY ? "legendary" : "open", at);
        cleanupBox(box); // box + beacon hilang, terrain kembali seperti semula
        state.save(plugin.data());

        String rarityName = msg.raw("rarity." + box.rarity().id());
        msg.send(p, "lootbox.open.success", "rarity", rarityName);
        msg.send(p, "lootbox.open.rewards", "items", describe(rewards));
        if (announce.getOrDefault(box.rarity(), false)) {
            msg.broadcast("lootbox.announce." + box.rarity().id(), "player", p.getName(), "rarity", rarityName);
        }
        plugin.debug("LootBox opened: box=" + box.id() + " rarity=" + box.rarity() + " by=" + p.getName()
                + " items=" + rewards.size());

        if (endWhenAllOpened && unopenedCount() == 0) finishSession(EndReason.ALL_OPENED, true);
    }

    private String describe(List<ItemStack> items) {
        if (items.isEmpty()) return plugin.messages().raw("lootbox.open.nothing");
        Map<Material, Integer> merged = new LinkedHashMap<>();
        for (ItemStack it : items) merged.merge(it.getType(), it.getAmount(), Integer::sum);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : merged.entrySet()) {
            parts.add(e.getValue() + "x " + TextUtil.title(e.getKey().name()));
        }
        return String.join(", ", parts);
    }

    /** Weighted rarity (bobot total, bukan chance sederhana per rarity). */
    public LootBoxRarity rollRarity() {
        LootBoxRarity r = RandomUtil.weighted(Arrays.asList(LootBoxRarity.values()), weights::get);
        return r == null ? LootBoxRarity.COMMON : r;
    }

    // ------------------------------------------------------------ pembersihan & proteksi

    /** Memulihkan blok asli sebuah LootBox. True jika bersih (atau memang tidak ada yang perlu dipulihkan). */
    private boolean cleanupBox(LootBox box) {
        if (box.originals().isEmpty()) {
            beacons.removeHologram(box.id());
            return true;
        }
        World w = Bukkit.getWorld(box.worldName());
        if (w == null) return false;
        List<BlockPos> positions = new ArrayList<>();
        for (LootBox.OriginalBlock o : box.originals()) positions.add(BlockPos.of(w, o.x(), o.y(), o.z()));
        if (!beacons.cleanup(box)) return false;
        for (BlockPos pos : positions) protectedBlocks.remove(pos);
        box.originals().clear();
        return true;
    }

    private void retryPendingCleanup() {
        boolean changed = false;
        for (LootBox b : new ArrayList<>(state.boxes.values())) {
            if (cleanupBox(b)) {
                state.boxes.remove(b.id());
                changed = true;
            }
        }
        if (changed) state.save(plugin.data());
    }

    private void registerProtection(LootBox box) {
        World w = Bukkit.getWorld(box.worldName());
        if (w == null) return;
        for (LootBox.OriginalBlock o : box.originals()) {
            protectedBlocks.put(BlockPos.of(w, o.x(), o.y(), o.z()), box);
        }
    }

    /** World yang dimuat belakangan (mis. Multiverse): pulihkan proteksi/struktur atau lanjutkan pembersihan. */
    public void onWorldLoad(World w) {
        boolean changed = false;
        for (LootBox b : new ArrayList<>(state.boxes.values())) {
            if (!b.worldName().equals(w.getName())) continue;
            if (isActive()) {
                registerProtection(b);
                if (!b.opened()) beacons.ensure(b);
            } else if (cleanupBox(b)) {
                state.boxes.remove(b.id());
                changed = true;
            }
        }
        if (changed) state.save(plugin.data());
    }

    // ------------------------------------------------------------ tick, bossbar, auto-start

    private void tick() {
        LootBoxSession s = state.session;
        long now = System.currentTimeMillis();
        if (s != null && s.active()) {
            long remaining = s.remaining(now);
            if (remaining <= 0) {
                finishSession(EndReason.TIMEOUT, true);
            } else {
                updateBar(remaining);
            }
            return;
        }
        if (autoStart && enabled && !starting && Bukkit.getOnlinePlayers().size() >= autoMinPlayers) {
            long base = Math.max(state.lastEnd, bootTime);
            if (now - base >= autoInterval) {
                plugin.debug("LootBox auto-start");
                start(Bukkit.getConsoleSender());
            }
        }
    }

    private void showBar() {
        if (!bossbarEnabled || !isActive()) return;
        if (bar == null) bar = BossBar.bossBar(Component.empty(), 1f, barColor, barOverlay);
        updateBar(remainingMillis());
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
    }

    private void updateBar(long remaining) {
        if (bar == null) {
            if (bossbarEnabled) showBar();
            return;
        }
        LootBoxSession s = state.session;
        long duration = Math.max(1L, s.endTime() - s.startTime());
        bar.name(plugin.messages().get("lootbox.bossbar.title", "time", TimeUtil.format(remaining),
                "left", unopenedCount(), "total", s.amount()));
        bar.progress(Math.max(0f, Math.min(1f, remaining / (float) duration)));
    }

    private void hideBar() {
        if (bar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        bar = null;
    }

    public void onJoin(Player p) {
        if (bar != null) p.showBossBar(bar);
    }

    // ------------------------------------------------------------ efek

    private void playEffectsAll(String key) {
        World w = Bukkit.getWorld(locations.area().world());
        if (w == null) return;
        Sound s = EffectUtil.sound(plugin.config().cfg().getString("lootbox.effects." + key + ".sound"));
        if (s == null) return;
        float volume = (float) plugin.config().cfg().getDouble("lootbox.effects." + key + ".volume", 1.0);
        float pitch = (float) plugin.config().cfg().getDouble("lootbox.effects." + key + ".pitch", 1.0);
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), s, volume, pitch);
    }

    private void playEffectsAt(String key, Location at) {
        FileConfiguration c = plugin.config().cfg();
        String base = "lootbox.effects." + key;
        Sound s = EffectUtil.sound(c.getString(base + ".sound"));
        if (s != null && at.getWorld() != null) {
            at.getWorld().playSound(at, s, (float) c.getDouble(base + ".volume", 1.0), (float) c.getDouble(base + ".pitch", 1.0));
        }
        Particle p = EffectUtil.particle(c.getString(base + ".particle"));
        if (p != null && at.getWorld() != null) {
            at.getWorld().spawnParticle(p, at, Math.max(1, c.getInt(base + ".count", 20)), 0.4, 0.5, 0.4, 0.05);
        }
    }

    private void runMain(Runnable r) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }
}
