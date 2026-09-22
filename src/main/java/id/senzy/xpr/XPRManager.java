package id.senzy.xpr;

import id.senzy.SenzyPlugin;
import id.senzy.config.MessageManager;
import id.senzy.data.PlayerData;
import id.senzy.event.SenzyModule;
import id.senzy.util.EffectUtil;
import id.senzy.util.RandomUtil;
import id.senzy.util.TextUtil;
import id.senzy.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * XPR = Boost Progression.
 * <pre>
 * RNG        -> menentukan BOOST apa yang didapat (tidak pernah tier / level / side effect)
 * LEVEL      -> menentukan kekuatan boost (durasi, cooldown, side effect)
 * LEVEL max  -> milestone yang membuka TIER berikutnya (kembali ke Level 1)
 * </pre>
 * Semua method dipanggil dari main thread.
 */
public final class XPRManager implements SenzyModule {

    public enum RollStatus { OK, DISABLED, NO_DATA, NO_BOOSTS, COOLDOWN, NOT_ENOUGH_XP, ALL_MAXED }

    public record RollResult(RollStatus status, Boost boost, BoostProgression.Result progression) {}

    public enum ActivateStatus { OK, DISABLED, NO_DATA, UNKNOWN_BOOST, NOT_OWNED, ALREADY_ACTIVE, ON_COOLDOWN }

    public enum AdvanceStatus { OK, DISABLED, NO_DATA, UNKNOWN_BOOST, NOT_OWNED, NOT_READY, MAX_TIER }

    public enum EndReason { EXPIRED, DEATH, RESET }

    public enum BoostState { LOCKED, ACTIVE, COOLDOWN, READY }

    /** Status tampil sebuah boost untuk seorang pemain. remainingMillis = sisa aktif / sisa cooldown. */
    public record BoostStatus(BoostState state, long remainingMillis) {}

    private final SenzyPlugin plugin;
    private final BoostManager boosts;
    private BoostProgression progression;
    private BukkitTask tickTask;

    private boolean enabled = true;
    private boolean endOnDeath = true;
    private int rollCostLevels = 3;
    private long rollCooldownMillis = 5000L;
    private boolean confirmGui = true;

    /** Waktu side effect berikutnya per pemain (hanya memori; aman hilang saat restart). */
    private final Map<UUID, Long> nextPulse = new HashMap<>();

    public XPRManager(SenzyPlugin plugin) {
        this.plugin = plugin;
        this.boosts = new BoostManager(plugin);
        this.progression = new BoostProgression(boosts);
    }

    @Override
    public String id() {
        return "xpr";
    }

    @Override
    public void enable() {
        reloadSettings();
        startTask();
    }

    @Override
    public void disable() {
        stopTask();
        for (PlayerData d : plugin.data().online()) {
            if (d.activeBoost() == null) continue;
            Player p = Bukkit.getPlayer(d.uuid());
            Boost b = boosts.get(d.activeBoost());
            if (p != null && b != null) clearEffects(p, b);
        }
        nextPulse.clear();
    }

    @Override
    public void reload() {
        stopTask();
        reloadSettings();
        startTask();
    }

    private void reloadSettings() {
        boosts.load();
        progression = new BoostProgression(boosts);
        var c = plugin.config().cfg();
        enabled = c.getBoolean("xpr.enabled", true);
        endOnDeath = c.getBoolean("xpr.active.end-on-death", true);
        rollCostLevels = Math.max(0, c.getInt("xpr.roll.cost-levels", 3));
        rollCooldownMillis = plugin.config().duration("xpr.roll.cooldown", 5000L);
        confirmGui = c.getBoolean("xpr.tiers.confirm-gui", true);
    }

    private void startTask() {
        stopTask();
        if (!enabled) return;
        long period = Math.max(5L, plugin.config().cfg().getLong("xpr.active.refresh-interval", 20L));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    private void stopTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    // ------------------------------------------------------------ akses

    public BoostManager boosts() {
        return boosts;
    }

    public BoostProgression progression() {
        return progression;
    }

    public boolean enabled() {
        return enabled;
    }

    public int rollCostLevels() {
        return rollCostLevels;
    }

    public PlayerData dataOf(Player p) {
        return plugin.data().get(p.getUniqueId());
    }

    /** Tier/level yang sudah dijepit ke definisi config saat ini. */
    public BoostLevel levelOf(Boost b, PlayerData.BoostState st) {
        int tier = Math.max(1, Math.min(b.tierCount(), st.tier));
        int level = Math.max(1, Math.min(boosts.maxLevel(), st.level));
        return new BoostLevel(tier, level);
    }

    public BoostStatus statusOf(PlayerData d, Boost b) {
        PlayerData.BoostState st = d.state(b.id());
        if (st == null || !st.unlocked) return new BoostStatus(BoostState.LOCKED, 0);
        long now = System.currentTimeMillis();
        if (b.id().equals(d.activeBoost())) {
            return new BoostStatus(BoostState.ACTIVE, Math.max(0, d.activeExpiration() - now));
        }
        if (st.cooldownUntil > now) return new BoostStatus(BoostState.COOLDOWN, st.cooldownUntil - now);
        return new BoostStatus(BoostState.READY, 0);
    }

    // ------------------------------------------------------------ roll (RNG)

    /**
     * Satu putaran roulette. RNG HANYA memilih jenis boost; hasilnya:
     * boost baru -> Unlock, boost lama -> Level + 1, level max -> milestone/Advance Tier.
     */
    public RollResult roll(Player p) {
        MessageManager msg = plugin.messages();
        if (!enabled) {
            msg.send(p, "xpr.disabled");
            return new RollResult(RollStatus.DISABLED, null, null);
        }
        PlayerData d = dataOf(p);
        if (d == null) return new RollResult(RollStatus.NO_DATA, null, null);
        if (boosts.all().isEmpty()) {
            msg.send(p, "xpr.roll.no-boosts");
            return new RollResult(RollStatus.NO_BOOSTS, null, null);
        }

        long now = System.currentTimeMillis();
        if (now < d.rollLockUntil()) {
            msg.send(p, "xpr.roll.cooldown", "time", TimeUtil.format(d.rollLockUntil() - now));
            return new RollResult(RollStatus.COOLDOWN, null, null);
        }

        // Kolam RNG: semua boost yang belum benar-benar maksimal (tier terakhir + level max).
        List<Boost> pool = new ArrayList<>();
        for (Boost b : boosts.all()) {
            if (b.weight() > 0 && !progression.isFullyMaxed(d.state(b.id()), b)) pool.add(b);
        }
        if (pool.isEmpty()) {
            msg.send(p, "xpr.roll.all-maxed");
            return new RollResult(RollStatus.ALL_MAXED, null, null);
        }
        if (rollCostLevels > 0 && p.getLevel() < rollCostLevels) {
            msg.send(p, "xpr.roll.not-enough-xp", "cost", rollCostLevels, "levels", p.getLevel());
            return new RollResult(RollStatus.NOT_ENOUGH_XP, null, null);
        }

        Boost picked = RandomUtil.weighted(pool, Boost::weight);
        if (picked == null) {
            msg.send(p, "xpr.roll.all-maxed");
            return new RollResult(RollStatus.ALL_MAXED, null, null);
        }

        PlayerData.BoostState st = d.stateOrCreate(picked.id());
        BoostProgression.Result r = progression.applyDuplicate(st, picked);

        // Tidak ada progres (menunggu konfirmasi tier) -> tidak memotong XP.
        if (r.outcome() != BoostProgression.Outcome.TIER_READY && rollCostLevels > 0) {
            p.giveExpLevels(-rollCostLevels);
        }
        d.setRollLockUntil(now + rollCooldownMillis);
        d.markDirty();
        plugin.data().save(d);

        plugin.debug("XPR roll: player=" + p.getName() + " boost=" + picked.id() + " outcome=" + r.outcome()
                + " tier=" + r.tier() + " level=" + r.level());
        if (r.outcome() == BoostProgression.Outcome.TIER_ADVANCED) {
            plugin.debug("XPR tier advancement: player=" + p.getName() + " boost=" + picked.id() + " -> tier " + r.tier());
        }

        announceRoll(p, picked, r);
        playSound(p, "xpr.roll.sound");

        if (r.awaitingAdvance() && confirmGui) {
            plugin.gui().open(p, new TierConfirmGui(plugin, p, picked.id()));
        }
        return new RollResult(RollStatus.OK, picked, r);
    }

    private void announceRoll(Player p, Boost b, BoostProgression.Result r) {
        MessageManager msg = plugin.messages();
        String name = b.displayName();
        int max = boosts.maxLevel();
        switch (r.outcome()) {
            case UNLOCKED -> msg.send(p, "xpr.roll.unlocked", "boost", name,
                    "tier", TextUtil.roman(r.tier()), "level", r.level(), "max", max);
            case LEVEL_UP -> {
                msg.send(p, "xpr.roll.level-up", "boost", name,
                        "tier", TextUtil.roman(r.tier()), "level", r.level(), "max", max);
                if (r.mastered()) {
                    String key = r.awaitingAdvance() ? "xpr.roll.mastered-ready" : "xpr.roll.mastered-final";
                    msg.send(p, key, "boost", name, "tier", TextUtil.roman(r.tier()),
                            "next", TextUtil.roman(r.tier() + 1));
                }
            }
            case TIER_ADVANCED -> msg.send(p, "xpr.roll.tier-advanced", "boost", name,
                    "old_tier", TextUtil.roman(r.tier() - 1), "tier", TextUtil.roman(r.tier()),
                    "level", r.level(), "max", max);
            case TIER_READY -> msg.send(p, "xpr.roll.tier-ready", "boost", name,
                    "tier", TextUtil.roman(r.tier()), "next", TextUtil.roman(r.tier() + 1));
            case MAXED -> msg.send(p, "xpr.roll.all-maxed");
        }
    }

    // ------------------------------------------------------------ aktivasi

    public ActivateStatus activate(Player p, String boostId) {
        MessageManager msg = plugin.messages();
        if (!enabled) {
            msg.send(p, "xpr.disabled");
            return ActivateStatus.DISABLED;
        }
        PlayerData d = dataOf(p);
        if (d == null) return ActivateStatus.NO_DATA;
        Boost b = boosts.get(boostId);
        if (b == null) {
            msg.send(p, "xpr.unknown-boost", "boost", boostId);
            return ActivateStatus.UNKNOWN_BOOST;
        }
        // Boost tidak boleh dipakai sebelum diperoleh.
        if (!d.owns(b.id())) {
            msg.send(p, "xpr.activate.not-owned", "boost", b.displayName());
            return ActivateStatus.NOT_OWNED;
        }
        long now = System.currentTimeMillis();
        if (d.activeBoost() != null) {
            Boost cur = boosts.get(d.activeBoost());
            msg.send(p, "xpr.activate.already-active", "boost", cur == null ? d.activeBoost() : cur.displayName(),
                    "time", TimeUtil.format(d.activeExpiration() - now));
            return ActivateStatus.ALREADY_ACTIVE;
        }
        PlayerData.BoostState st = d.state(b.id());
        if (st.cooldownUntil > now) {
            msg.send(p, "xpr.activate.on-cooldown", "boost", b.displayName(),
                    "time", TimeUtil.format(st.cooldownUntil - now));
            return ActivateStatus.ON_COOLDOWN;
        }

        BoostLevel lv = levelOf(b, st);
        long duration = boosts.duration(b, lv);
        d.setActive(b.id(), now + duration);
        plugin.data().save(d);
        nextPulse.remove(p.getUniqueId());
        applyBoostEffect(p, b, lv, duration);

        BoostSideEffect se = b.sideEffect();
        msg.send(p, "xpr.activate.ok", "boost", b.displayName(), "tier", TextUtil.roman(lv.tier()),
                "level", lv.level(), "duration", TimeUtil.format(duration));
        if (se != null) {
            msg.send(p, "xpr.activate.side-effect", "side_effect", sideEffectName(b, lv));
        }
        plugin.debug("XPR activate: player=" + p.getName() + " boost=" + b.id() + " duration=" + TimeUtil.format(duration));
        return ActivateStatus.OK;
    }

    // ------------------------------------------------------------ tier

    /** Membuka tier berikutnya (Tier + 1, Level 1). Syarat: level max di tier sekarang. */
    public AdvanceStatus advance(Player p, String boostId) {
        MessageManager msg = plugin.messages();
        if (!enabled) {
            msg.send(p, "xpr.disabled");
            return AdvanceStatus.DISABLED;
        }
        PlayerData d = dataOf(p);
        if (d == null) return AdvanceStatus.NO_DATA;
        Boost b = boosts.get(boostId);
        if (b == null) {
            msg.send(p, "xpr.unknown-boost", "boost", boostId);
            return AdvanceStatus.UNKNOWN_BOOST;
        }
        PlayerData.BoostState st = d.state(b.id());
        if (st == null || !st.unlocked) {
            msg.send(p, "xpr.upgrade.not-owned", "boost", b.displayName());
            return AdvanceStatus.NOT_OWNED;
        }
        if (progression.isFullyMaxed(st, b)) {
            msg.send(p, "xpr.upgrade.max-tier", "boost", b.displayName());
            return AdvanceStatus.MAX_TIER;
        }
        if (!progression.advance(st, b)) {
            msg.send(p, "xpr.upgrade.not-ready", "boost", b.displayName(), "level", st.level, "max", boosts.maxLevel());
            return AdvanceStatus.NOT_READY;
        }
        d.markDirty();
        plugin.data().save(d);
        msg.send(p, "xpr.upgrade.ok", "boost", b.displayName(), "old_tier", TextUtil.roman(st.tier - 1),
                "tier", TextUtil.roman(st.tier), "level", st.level, "max", boosts.maxLevel());
        plugin.debug("XPR tier advancement: player=" + p.getName() + " boost=" + b.id() + " -> tier " + st.tier);
        playSound(p, "xpr.roll.sound");
        return AdvanceStatus.OK;
    }

    // ------------------------------------------------------------ admin

    /** Memberi hasil RNG "boost ini" sebanyak times kali (tanpa biaya XP / cooldown). Untuk admin & testing. */
    public boolean grant(Player target, String boostId, int times) {
        PlayerData d = dataOf(target);
        Boost b = boosts.get(boostId);
        if (d == null || b == null) return false;
        PlayerData.BoostState st = d.stateOrCreate(b.id());
        for (int i = 0; i < Math.max(1, times); i++) {
            BoostProgression.Result r = progression.applyDuplicate(st, b);
            if (r.outcome() == BoostProgression.Outcome.MAXED || r.outcome() == BoostProgression.Outcome.TIER_READY) break;
        }
        d.markDirty();
        plugin.data().save(d);
        return true;
    }

    public void resetPlayer(Player target) {
        PlayerData d = dataOf(target);
        if (d == null) return;
        if (d.activeBoost() != null) {
            Boost b = boosts.get(d.activeBoost());
            if (b != null) clearEffects(target, b);
        }
        d.clearBoosts();
        nextPulse.remove(target.getUniqueId());
        plugin.data().save(d);
    }

    // ------------------------------------------------------------ event pemain

    public void onJoin(Player p) {
        PlayerData d = dataOf(p);
        if (d == null || d.activeBoost() == null) return;
        Boost b = boosts.get(d.activeBoost());
        long now = System.currentTimeMillis();
        if (b == null) {
            d.setActive(null, 0);
            plugin.data().save(d);
            return;
        }
        if (now >= d.activeExpiration()) {
            endActive(p, d, EndReason.EXPIRED);
        } else {
            plugin.messages().send(p, "xpr.welcome-back-active", "boost", b.displayName(),
                    "time", TimeUtil.format(d.activeExpiration() - now));
        }
    }

    public void onQuit(Player p) {
        nextPulse.remove(p.getUniqueId());
    }

    public void onDeath(Player p) {
        if (!endOnDeath) return;
        PlayerData d = dataOf(p);
        if (d != null && d.activeBoost() != null) endActive(p, d, EndReason.DEATH);
    }

    // ------------------------------------------------------------ tick

    private void tick() {
        long now = System.currentTimeMillis();
        for (PlayerData d : new ArrayList<>(plugin.data().online())) {
            String activeId = d.activeBoost();
            if (activeId == null) continue;
            Player p = Bukkit.getPlayer(d.uuid());
            if (p == null || !p.isOnline() || p.isDead()) continue;
            Boost b = boosts.get(activeId);
            PlayerData.BoostState st = d.state(activeId);
            if (b == null || st == null) {
                d.setActive(null, 0);
                continue;
            }
            if (now >= d.activeExpiration()) {
                endActive(p, d, EndReason.EXPIRED);
                continue;
            }
            BoostLevel lv = levelOf(b, st);
            applyBoostEffect(p, b, lv, d.activeExpiration() - now);
            pulseSideEffect(p, b, lv, now);
        }
    }

    private void applyBoostEffect(Player p, Boost b, BoostLevel lv, long remainingMillis) {
        PotionEffectType type = b.effect();
        int amp = b.tier(lv.tier()).amplifier();
        int wanted = (int) Math.min(Integer.MAX_VALUE / 4L, remainingMillis / 50L) + 20;
        PotionEffect cur = p.getPotionEffect(type);
        // Hanya pasang ulang jika hilang / berubah, supaya ikon efek tidak berkedip tiap detik.
        if (cur == null || cur.getAmplifier() != amp || cur.getDuration() < wanted - 60) {
            boolean particles = plugin.config().cfg().getBoolean("xpr.active.effect-particles", false);
            p.addPotionEffect(new PotionEffect(type, wanted, amp, false, particles, true));
        }
    }

    private void pulseSideEffect(Player p, Boost b, BoostLevel lv, long now) {
        BoostSideEffect se = b.sideEffect();
        if (se == null) return;
        Long next = nextPulse.get(p.getUniqueId());
        if (next != null && now < next) return;
        int stageNo = boosts.sideEffectStage(b, lv);
        BoostSideEffect.Stage stage = se.stage(stageNo);
        p.addPotionEffect(new PotionEffect(se.type(), stage.durationTicks(), Math.max(0, stage.amplifier() - 1), false, true, true));
        nextPulse.put(p.getUniqueId(), now + stage.intervalMillis());
        plugin.debug("XPR side effect: player=" + p.getName() + " boost=" + b.id() + " stage=" + stageNo
                + " amplifier=" + stage.amplifier());
    }

    private void endActive(Player p, PlayerData d, EndReason reason) {
        String id = d.activeBoost();
        Boost b = boosts.get(id);
        long now = System.currentTimeMillis();
        long endedAt = reason == EndReason.EXPIRED ? Math.min(now, d.activeExpiration()) : now;
        long cooldown = 0;
        if (b != null) {
            PlayerData.BoostState st = d.state(id);
            if (st != null) {
                cooldown = boosts.cooldown(b, levelOf(b, st));
                st.cooldownUntil = endedAt + cooldown;
                plugin.debug("XPR cooldown: boost=" + id + " tier=" + st.tier + " level=" + st.level
                        + " cooldown=" + TimeUtil.format(cooldown));
            }
            if (p != null) clearEffects(p, b);
        }
        d.setActive(null, 0);
        d.markDirty();
        plugin.data().save(d);
        nextPulse.remove(d.uuid());
        if (p != null && b != null) {
            long remainingCd = Math.max(0, d.state(id) == null ? 0 : d.state(id).cooldownUntil - now);
            String key = reason == EndReason.DEATH ? "xpr.ended.death" : "xpr.ended.expired";
            plugin.messages().send(p, key, "boost", b.displayName(), "cooldown", TimeUtil.format(remainingCd));
        }
    }

    private void clearEffects(Player p, Boost b) {
        p.removePotionEffect(b.effect());
        if (b.sideEffect() != null) p.removePotionEffect(b.sideEffect().type());
    }

    // ------------------------------------------------------------ util

    /** "Nausea III" (nama efek + angka romawi) untuk tampilan. */
    public String sideEffectName(Boost b, BoostLevel lv) {
        BoostSideEffect se = b.sideEffect();
        if (se == null) return plugin.messages().raw("gui.xpr.none");
        BoostSideEffect.Stage stage = se.stage(boosts.sideEffectStage(b, lv));
        String effectName = TextUtil.title(se.type().getKey().getKey());
        return effectName + " " + TextUtil.roman(stage.amplifier());
    }

    private void playSound(Player p, String configPath) {
        Sound s = EffectUtil.sound(plugin.config().cfg().getString(configPath));
        if (s != null) p.playSound(p.getLocation(), s, 1f, 1f);
    }
}
