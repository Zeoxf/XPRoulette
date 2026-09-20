package id.xproulette.lootbox;

import id.xproulette.Messages;
import id.xproulette.event.EventState;
import id.xproulette.event.EventType;
import id.xproulette.event.GameEvent;
import id.xproulette.util.Configs;
import id.xproulette.util.TimeParser;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Random Loot Box Event.
 *
 * <pre>
 * IDLE -> STARTING (pilih pemain, cari 5 lokasi, pasang box)
 *      -> ACTIVE   (sampai duration habis / semua box dibuka)
 *      -> ENDING   (hapus box yang belum dibuka)
 *      -> COOLDOWN -> IDLE
 * </pre>
 *
 * Event ini tidak menyentuh XP Roulette sama sekali; koordinasi hanya lewat EventManager.
 * Semua kode di kelas ini berjalan di main thread.
 */
public final class LootBoxEvent implements GameEvent {

    public static final String ID = "loot-box";

    public enum StartResult {STARTED, NOT_READY, NO_PLAYER, INVALID_WORLD}

    private enum EndReason {EXPIRED, ALL_OPENED, ADMIN}

    /** Batas waktu ENDING sebelum dipaksa selesai (jaga-jaga jika pemuatan chunk macet). */
    private static final long ENDING_TIMEOUT_MILLIS = 60_000L;

    private final JavaPlugin plugin;
    private final Messages msg;
    private final LootBoxStore store;

    private LootBoxSettings settings;
    private LootGenerator generator;
    private LocationFinder finder;
    private PlayerSelector selector;

    private EventState state = EventState.IDLE;
    private String eventId = "-";
    private long startMillis;
    private long endMillis;
    private long cooldownUntil;
    private String centerName = "-";
    private String centerWorld = "";
    private int centerX;
    private int centerZ;

    private final List<LootBox> boxes = new ArrayList<>();
    private final Map<BlockKey, LootBox> byBlock = new HashMap<>();
    private List<LootBox> staging = new ArrayList<>();

    private BossBar bossBar;
    private int searchToken;
    private int endToken;
    private long endingSince;
    private Runnable endFinisher;
    private int ambientTicker;

    public LootBoxEvent(JavaPlugin plugin, Messages msg) {
        this.plugin = plugin;
        this.msg = msg;
        this.store = new LootBoxStore(plugin);
        applySettings();
    }

    // ============================================================== konfigurasi

    private void applySettings() {
        settings = new LootBoxSettings(Configs.load(plugin, "lootbox.yml"), plugin.getLogger());
        generator = new LootGenerator(settings);
        finder = new LocationFinder(plugin, settings);
        selector = PlayerSelector.create(settings.selectionMode, settings, plugin.getLogger());
    }

    /** /xpr loot reload. Sesi yang sedang berjalan tidak terganggu. */
    public void reload() {
        applySettings();
        info("lootbox.yml dimuat ulang");
    }

    public LootBoxSettings settings() {
        return settings;
    }

    // ================================================================ GameEvent

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public EventType getType() {
        return EventType.LOOT_BOX;
    }

    @Override
    public EventState getState() {
        return state;
    }

    @Override
    public boolean isExclusive() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return settings.enabled;
    }

    @Override
    public boolean canStart() {
        return settings.enabled && state == EventState.IDLE;
    }

    @Override
    public boolean start() {
        return startWith(null) == StartResult.STARTED;
    }

    @Override
    public void stop() {
        switch (state) {
            case STARTING -> {
                searchToken++;               // hasil pencarian yang datang belakangan diabaikan
                cleanupBoxes(staging, () -> { });
                staging = new ArrayList<>();
                setState(EventState.IDLE);
                info("LootBox event dibatalkan saat STARTING");
            }
            case ACTIVE -> endSession(EndReason.ADMIN);
            default -> { }
        }
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        switch (state) {
            case ACTIVE -> {
                if (now >= endMillis) {
                    endSession(EndReason.EXPIRED);
                    return;
                }
                updateBossBar(now);
                if (settings.ambientEnabled && ++ambientTicker >= settings.ambientInterval) {
                    ambientTicker = 0;
                    spawnAmbient();
                }
            }
            case ENDING -> {
                if (now - endingSince > ENDING_TIMEOUT_MILLIS && endFinisher != null) {
                    warn("ENDING melebihi batas waktu, dipaksa selesai");
                    endFinisher.run();
                }
            }
            case COOLDOWN -> {
                if (now >= cooldownUntil) {
                    setState(EventState.IDLE);
                }
            }
            default -> { }
        }
    }

    @Override
    public void shutdown() {
        hideBossBar();
        searchToken++;
        endToken++;
        switch (state) {
            case ACTIVE -> {
                if (settings.persistence) {
                    persist();                       // box tetap di dunia, dipulihkan saat start
                } else {
                    removeSync(boxes);
                    store.clearSession(0);
                }
            }
            case ENDING -> {
                removeSync(boxes);
                store.clearSession(0);
            }
            case STARTING -> removeSync(staging);
            default -> { }
        }
        store.save();
    }

    // ==================================================================== START

    /** Melewati cooldown (dipakai admin: /xpr loot start force). */
    public void skipCooldown() {
        if (state == EventState.COOLDOWN) {
            cooldownUntil = 0;
            store.clearSession(0);
            setState(EventState.IDLE);
        }
    }

    /**
     * Memulai sesi baru.
     *
     * @param manualCenter pemain pusat yang ditentukan admin, atau null untuk memilih lewat PlayerSelector
     */
    public StartResult startWith(Player manualCenter) {
        if (!canStart()) return StartResult.NOT_READY;

        Player center = manualCenter != null ? manualCenter : selector.select().orElse(null);
        if (center == null) {
            info("No eligible online player, LootBox event not started");
            return StartResult.NO_PLAYER;
        }
        World world = center.getWorld();
        if (!settings.isWorldAllowed(world)) {
            info("World '" + world.getName() + "' tidak diizinkan untuk Loot Box event");
            return StartResult.INVALID_WORLD;
        }

        setState(EventState.STARTING);
        eventId = store.nextEventId();
        centerName = center.getName();
        centerWorld = world.getName();
        Location loc = center.getLocation();
        centerX = loc.getBlockX();               // pusat disimpan sekarang, bukan posisi live pemain
        centerZ = loc.getBlockZ();
        startMillis = System.currentTimeMillis();
        boxes.clear();
        byBlock.clear();
        staging = new ArrayList<>();
        final int token = ++searchToken;

        info("LootBox event started (" + eventId + ")");
        info("Selected player: " + centerName + " @ " + centerWorld + " " + centerX + " " + centerZ);
        Bukkit.broadcast(msg.get("lootbox.starting", "player", centerName, "count", settings.count,
                "world", centerWorld, "x", centerX, "z", centerZ, "radius", settings.radiusX,
                "duration", TimeParser.format(settings.durationSeconds)));

        finder.find(world, centerX, centerZ).whenComplete((res, ex) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> onSearchDone(token, world, res, ex)));
        return StartResult.STARTED;
    }

    private void onSearchDone(int token, World world, LocationFinder.Result res, Throwable ex) {
        if (token != searchToken || state != EventState.STARTING) return; // sudah dibatalkan

        if (ex != null || res == null) {
            warn("Pencarian lokasi gagal: " + (ex == null ? "hasil kosong" : ex.toString()));
            setState(EventState.IDLE);
            return;
        }

        int found = res.points().size();
        info("Lokasi valid: " + found + "/" + res.requested() + " (gagal " + res.failed()
                + ", alasan penolakan: " + res.rejections() + ")");

        if (found < settings.minimumSuccessful) {
            warn("LootBox event dibatalkan: hanya " + found + " lokasi valid, minimum "
                    + settings.minimumSuccessful);
            Bukkit.broadcast(msg.get("lootbox.cancelled"));
            setState(EventState.IDLE);
            return;
        }
        if (res.failed() > 0) {
            notifyAdmins(msg.get("lootbox.invalid-location", "count", res.failed(),
                    "total", res.requested(), "event_id", eventId));
        }

        List<LootBox> created = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LocationFinder.SpawnPoint p : res.points()) {
            Rarity rarity = generator.rollRarity();
            LootBox b = new LootBox(store.nextBoxId(), eventId, world.getUID(), world.getName(),
                    p.x(), p.y(), p.z(), rarity, settings.blockFor(rarity), now);
            created.add(b);
            info("Generated location: " + world.getName() + " " + p.x() + " " + p.y() + " " + p.z());
            info("LootBox rarity: " + rarity.name() + " (" + b.id() + ")");
        }
        staging = created;
        placeAll(token, created);
    }

    private void placeAll(int token, List<LootBox> created) {
        int[] pending = {created.size()};
        for (LootBox b : created) {
            placeBox(b, ok -> {
                if (!ok) warn("Gagal menempatkan Loot Box " + b.id());
                pending[0]--;
                if (pending[0] == 0) finishStart(token, created);
            });
        }
    }

    private void finishStart(int token, List<LootBox> created) {
        List<LootBox> ok = new ArrayList<>();
        for (LootBox b : created) {
            if (b.isPlaced()) ok.add(b);
        }

        if (token != searchToken || state != EventState.STARTING) {
            cleanupBoxes(ok, () -> { });               // dibatalkan saat pemasangan
            return;
        }
        staging = new ArrayList<>();

        if (ok.size() < settings.minimumSuccessful) {
            warn("LootBox event dibatalkan: hanya " + ok.size() + " box berhasil dipasang, minimum "
                    + settings.minimumSuccessful);
            Bukkit.broadcast(msg.get("lootbox.cancelled"));
            cleanupBoxes(ok, () -> setState(EventState.IDLE));
            return;
        }

        boxes.clear();
        boxes.addAll(ok);
        byBlock.clear();
        for (LootBox b : ok) byBlock.put(b.key(), b);

        endMillis = System.currentTimeMillis() + settings.durationSeconds * 1000L;
        setState(EventState.ACTIVE);
        showBossBar();

        for (LootBox b : ok) {
            Location l = boxLocation(b);
            if (l != null) settings.spawnFx.play(l);
        }
        Bukkit.broadcast(msg.get("lootbox.spawned", "player", centerName, "count", ok.size(),
                "world", centerWorld, "x", centerX, "z", centerZ, "radius", settings.radiusX,
                "duration", TimeParser.format(settings.durationSeconds), "event_id", eventId));
        if (settings.revealCoordinates) {
            int n = 1;
            for (LootBox b : ok) {
                Bukkit.broadcast(msg.get("lootbox.spawned-box", "n", n++, "loot_id", b.id(),
                        "world", b.worldName(), "x", b.x(), "y", b.y(), "z", b.z()));
            }
        }
        persist();
        info("Loot Box spawned: " + ok.size() + "/" + settings.count + " (" + eventId + ")");
    }

    // ======================================================== PEMASANGAN BLOCK

    private void placeBox(LootBox b, Consumer<Boolean> done) {
        World w = Bukkit.getWorld(b.worldId());
        if (w == null) {
            done.accept(false);
            return;
        }
        int chunkX = b.x() >> 4;
        int chunkZ = b.z() >> 4;
        if (w.isChunkLoaded(chunkX, chunkZ)) {
            done.accept(placeNow(w, b));
            return;
        }
        w.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, ex) ->
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        done.accept(ex == null && chunk != null && placeNow(w, b))));
    }

    /** Memasang block Loot Box. Mengembalikan false jika lokasi sudah tidak aman. */
    private boolean placeNow(World w, LootBox b) {
        try {
            Block block = w.getBlockAt(b.x(), b.y(), b.z());
            if (block.getType() == b.material()) {      // pemulihan: sudah terpasang
                b.setPlaced(true);
                return true;
            }
            if (!block.isPassable() || block.isLiquid()) return false;
            if (!block.getRelative(BlockFace.DOWN).getType().isSolid()) return false;

            b.setPreviousData(block.getBlockData().getAsString());
            block.setType(b.material(), false);
            b.setPlaced(true);
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Gagal memasang Loot Box " + b.id(), ex);
            return false;
        }
    }

    /** Mengembalikan block asli. Tidak menimpa jika block sudah bukan Loot Box. */
    private void restoreBlock(World w, LootBox b) {
        try {
            Block block = w.getBlockAt(b.x(), b.y(), b.z());
            if (block.getType() != b.material()) return;
            BlockData data;
            try {
                data = Bukkit.createBlockData(b.previousData());
            } catch (IllegalArgumentException ex) {
                data = Bukkit.createBlockData(Material.AIR);
            }
            block.setBlockData(data, false);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Gagal menghapus Loot Box " + b.id(), ex);
        }
    }

    /** Menghapus box yang belum dibuka. Chunk yang belum termuat dimuat asinkron dulu. */
    private void cleanupBoxes(List<LootBox> list, Runnable done) {
        List<LootBox> targets = new ArrayList<>();
        for (LootBox b : list) {
            if (b.isPlaced() && !b.isOpened()) targets.add(b);
        }
        if (targets.isEmpty()) {
            done.run();
            return;
        }
        int[] pending = {targets.size()};
        Runnable one = () -> {
            pending[0]--;
            if (pending[0] == 0) done.run();
        };
        for (LootBox b : targets) {
            World w = Bukkit.getWorld(b.worldId());
            if (w == null) {
                one.run();
                continue;
            }
            int chunkX = b.x() >> 4;
            int chunkZ = b.z() >> 4;
            if (w.isChunkLoaded(chunkX, chunkZ)) {
                restoreBlock(w, b);
                one.run();
            } else {
                w.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, ex) ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (ex == null && chunk != null) restoreBlock(w, b);
                            one.run();
                        }));
            }
        }
    }

    /** Versi sinkron untuk shutdown plugin (tidak boleh menunggu tugas asinkron). */
    private void removeSync(List<LootBox> list) {
        for (LootBox b : list) {
            if (!b.isPlaced() || b.isOpened()) continue;
            World w = Bukkit.getWorld(b.worldId());
            if (w != null) restoreBlock(w, b);
        }
    }

    // ==================================================================== CLAIM

    /** Mencari Loot Box aktif di sebuah block (null jika bukan Loot Box). */
    public LootBox findBox(Block block) {
        return byBlock.get(BlockKey.of(block));
    }

    /**
     * Alur: validasi event ACTIVE -> validasi belum dibuka -> lock -> generate -> beri ->
     * tandai opened -> hapus box -> pesan / broadcast.
     */
    public void claim(Player player, LootBox box) {
        if (state != EventState.ACTIVE) {
            player.sendMessage(msg.get("lootbox.not-active"));
            return;
        }
        if (box.isOpened() || !box.tryLock()) {           // lock: hanya satu pemain yang lolos
            player.sendMessage(msg.get("lootbox.already-opened"));
            return;
        }

        List<ItemStack> loot;
        try {
            loot = generator.generate(box.rarity());
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Gagal membuat loot untuk " + box.id(), ex);
            box.unlock();
            return;
        }
        if (loot.isEmpty()) {
            warn("Loot table " + box.rarity() + " kosong, " + box.id() + " tidak dibuka");
            box.unlock();
            return;
        }

        try {
            give(player, loot);
        } catch (RuntimeException ex) {
            // Sebagian item mungkin sudah masuk. Box tetap dianggap terbuka agar tidak terjadi duplikasi.
            plugin.getLogger().log(Level.SEVERE, "Error saat memberi loot " + box.id() + " ke " + player.getName(), ex);
        }
        box.markOpened(player.getName());
        byBlock.remove(box.key());

        World w = Bukkit.getWorld(box.worldId());
        Location loc = boxLocation(box);
        if (w != null) restoreBlock(w, box);
        if (loc != null) {
            settings.openFx.play(loc);
            if (box.rarity() == Rarity.LEGENDARY) settings.legendaryFx.play(loc);
        }

        String rarityName = settings.rarityName(box.rarity());
        List<String> lines = LootGenerator.describe(loot);
        player.sendMessage(msg.get("lootbox.opened", "player", player.getName(), "loot_id", box.id(),
                "rarity", rarityName, "count", lines.size()));
        for (String line : lines) {
            player.sendMessage(msg.get("lootbox.opened-item", "item", line));
        }

        if (settings.shouldAnnounce(box.rarity())) {
            String key = box.rarity() == Rarity.LEGENDARY ? "lootbox.legendary" : "lootbox.found";
            Bukkit.broadcast(msg.get(key, "player", player.getName(), "rarity", rarityName, "loot_id", box.id()));
        }

        info("LootBox claimed: " + box.id() + " by " + player.getName() + " (" + box.rarity() + ")");
        persist();
        updateBossBar(System.currentTimeMillis());

        if (settings.endWhenAllOpened && availableCount() == 0) {
            endSession(EndReason.ALL_OPENED);
        }
    }

    private void give(Player player, List<ItemStack> loot) {
        for (ItemStack stack : loot) {
            Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
            for (ItemStack rest : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest); // inventory penuh
            }
        }
    }

    // ====================================================================== END

    private void endSession(EndReason reason) {
        if (state != EventState.ACTIVE) return;

        int remaining = availableCount();
        hideBossBar();

        switch (reason) {
            case EXPIRED -> {
                if (remaining > 0) Bukkit.broadcast(msg.get("lootbox.expired", "count", remaining, "event_id", eventId));
            }
            case ALL_OPENED -> Bukkit.broadcast(msg.get("lootbox.all-opened", "event_id", eventId));
            case ADMIN -> Bukkit.broadcast(msg.get("lootbox.stopped", "count", remaining, "event_id", eventId));
        }

        boolean cooldown = reason != EndReason.ADMIN && settings.cooldownSeconds > 0;
        beginEnding(new ArrayList<>(boxes), cooldown, System.currentTimeMillis());
    }

    private void beginEnding(List<LootBox> toRemove, boolean applyCooldown, long endedAt) {
        setState(EventState.ENDING);
        endingSince = System.currentTimeMillis();
        final int token = ++endToken;
        Runnable finish = () -> {
            if (token == endToken && state == EventState.ENDING) completeEnd(applyCooldown, endedAt);
        };
        endFinisher = finish;
        cleanupBoxes(toRemove, finish);
    }

    private void completeEnd(boolean applyCooldown, long endedAt) {
        endToken++;
        endFinisher = null;
        boxes.clear();
        byBlock.clear();
        if (applyCooldown) {
            cooldownUntil = endedAt + settings.cooldownSeconds * 1000L;
            setState(cooldownUntil > System.currentTimeMillis() ? EventState.COOLDOWN : EventState.IDLE);
        } else {
            cooldownUntil = 0;
            setState(EventState.IDLE);
        }
        store.clearSession(cooldownUntil);
        info("LootBox event ended (" + eventId + ")");
    }

    // ================================================================== RESTORE

    /**
     * Dipanggil sekali setelah server siap. Memuat state tersimpan:
     * event masih berlaku -> pulihkan; sudah kedaluwarsa -> bersihkan.
     */
    public void restore() {
        long now = System.currentTimeMillis();
        LootBoxStore.Session ses = store.loadSession();

        if (ses == null) {
            cooldownUntil = store.cooldownUntil();
            setState(now < cooldownUntil ? EventState.COOLDOWN : EventState.IDLE);
            return;
        }

        eventId = ses.eventId();
        startMillis = ses.startMillis();
        endMillis = ses.endMillis();
        centerName = ses.centerName();
        centerWorld = ses.worldName();
        centerX = ses.centerX();
        centerZ = ses.centerZ();
        boxes.clear();
        boxes.addAll(ses.boxes());

        if (settings.persistence && now < endMillis) {
            byBlock.clear();
            for (LootBox b : boxes) {
                if (!b.isOpened()) byBlock.put(b.key(), b);
            }
            setState(EventState.ACTIVE);
            showBossBar();
            for (LootBox b : boxes) {
                if (!b.isOpened()) {
                    placeBox(b, ok -> {
                        if (!ok) warn("Loot Box " + b.id() + " tidak bisa dipulihkan di dunia");
                    });
                }
            }
            info("LootBox event restored: " + eventId + ", sisa "
                    + TimeParser.format((endMillis - now) / 1000L) + ", box tersedia " + availableCount());
        } else {
            info("Sesi tersimpan " + eventId + " sudah berakhir, membersihkan Loot Box");
            long endedAt = Math.min(endMillis, now);
            beginEnding(new ArrayList<>(boxes), settings.cooldownSeconds > 0, endedAt);
        }
    }

    private void persist() {
        if (state != EventState.ACTIVE) return;
        store.saveSession(new LootBoxStore.Session(eventId, startMillis, endMillis, centerName,
                centerWorld, centerX, centerZ, boxes));
    }

    // ================================================================== BOSSBAR

    private void showBossBar() {
        if (!settings.bossbarEnabled) return;
        hideBossBar();
        bossBar = BossBar.bossBar(Component.empty(), 1f, settings.bossbarColor, settings.bossbarOverlay);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bossBar);
        updateBossBar(System.currentTimeMillis());
    }

    private void hideBossBar() {
        if (bossBar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bossBar);
        bossBar = null;
    }

    private void updateBossBar(long now) {
        if (bossBar == null) return;
        long leftMillis = Math.max(0, endMillis - now);
        long totalMillis = Math.max(1, endMillis - startMillis);
        bossBar.name(msg.get("lootbox.bossbar", "count", availableCount(), "total", boxes.size(),
                "remaining", TimeParser.format(leftMillis / 1000L)));
        bossBar.progress(Math.max(0f, Math.min(1f, leftMillis / (float) totalMillis)));
    }

    public void onJoin(Player p) {
        if (bossBar != null && state == EventState.ACTIVE) p.showBossBar(bossBar);
    }

    public void onQuit(Player p) {
        if (bossBar != null) p.hideBossBar(bossBar);
    }

    // ================================================================== HELPER

    private void spawnAmbient() {
        for (LootBox b : boxes) {
            if (!b.isAvailable()) continue;
            World w = Bukkit.getWorld(b.worldId());
            if (w == null || !w.isChunkLoaded(b.x() >> 4, b.z() >> 4)) continue;
            settings.ambientFx.play(new Location(w, b.x() + 0.5, b.y() + 1.5, b.z() + 0.5));
        }
    }

    private Location boxLocation(LootBox b) {
        World w = Bukkit.getWorld(b.worldId());
        return w == null ? null : new Location(w, b.x() + 0.5, b.y() + 0.5, b.z() + 0.5);
    }

    private void notifyAdmins(Component message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("xproulette.admin")) p.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    private void setState(EventState next) {
        if (state != next && settings.debug) {
            plugin.getLogger().info("LootBox state: " + state + " -> " + next);
        }
        state = next;
    }

    private void info(String s) {
        plugin.getLogger().info("[LootBox] " + s);
    }

    private void warn(String s) {
        plugin.getLogger().warning("[LootBox] " + s);
    }

    // ================================================================== STATUS

    public String eventId() {
        return eventId;
    }

    public String centerName() {
        return centerName;
    }

    public int totalBoxes() {
        return boxes.size();
    }

    public int availableCount() {
        int n = 0;
        for (LootBox b : boxes) {
            if (b.isAvailable()) n++;
        }
        return n;
    }

    public int openedCount() {
        int n = 0;
        for (LootBox b : boxes) {
            if (b.isOpened()) n++;
        }
        return n;
    }

    /** Rarity tertinggi di sesi ini (null jika belum ada box). */
    public Rarity highestRarity() {
        Rarity best = null;
        for (LootBox b : boxes) {
            if (best == null || b.rarity().ordinal() > best.ordinal()) best = b.rarity();
        }
        return best;
    }

    public long remainingSeconds() {
        return state == EventState.ACTIVE ? Math.max(0, (endMillis - System.currentTimeMillis()) / 1000L) : 0;
    }

    public long cooldownRemainingSeconds() {
        return state == EventState.COOLDOWN ? Math.max(0, (cooldownUntil - System.currentTimeMillis()) / 1000L) : 0;
    }

    public List<LootBox> getBoxes() {
        return Collections.unmodifiableList(boxes);
    }

    public String getCenterWorld() {
        return centerWorld;
    }
}
