package id.senzy.xpr;

import id.senzy.SenzyPlugin;
import id.senzy.config.MessageManager;
import id.senzy.data.PlayerData;
import id.senzy.gui.GuiItems;
import id.senzy.gui.SenzyGui;
import id.senzy.util.TextUtil;
import id.senzy.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** GUI "Senzy XPR". Klik boost = aktifkan (atau buka konfirmasi tier), klik tombol roll = putar roulette. */
public final class XPRGui implements SenzyGui {

    /** 28 slot boost (4 baris x 7 kolom). Boost lebih dari ini tidak ditampilkan. */
    private static final int[] BOOST_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final int INFO_SLOT = 45;
    private static final int ROLL_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final SenzyPlugin plugin;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, String> slotToBoost = new HashMap<>();

    public XPRGui(SenzyPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, plugin.messages().get("gui.xpr.title"));
        render();
    }

    private void render() {
        inventory.clear();
        slotToBoost.clear();
        MessageManager msg = plugin.messages();
        XPRManager xpr = plugin.xpr();
        PlayerData d = xpr.dataOf(viewer);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, GuiItems.filler(Material.BLACK_STAINED_GLASS_PANE));
        }
        if (d == null) return;

        int index = 0;
        int owned = 0;
        for (Boost b : xpr.boosts().all()) {
            if (index >= BOOST_SLOTS.length) break;
            int slot = BOOST_SLOTS[index++];
            slotToBoost.put(slot, b.id());
            if (d.owns(b.id())) owned++;
            inventory.setItem(slot, boostItem(d, b));
        }

        inventory.setItem(INFO_SLOT, GuiItems.of(Material.BOOK,
                msg.get("gui.xpr.info.name"),
                msg.list("gui.xpr.info.lore", "owned", owned, "total", xpr.boosts().all().size()), false));
        inventory.setItem(ROLL_SLOT, GuiItems.of(Material.EXPERIENCE_BOTTLE,
                msg.get("gui.xpr.roll.name"),
                msg.list("gui.xpr.roll.lore", "cost", xpr.rollCostLevels(), "levels", viewer.getLevel()), true));
        inventory.setItem(CLOSE_SLOT, GuiItems.of(Material.BARRIER, msg.get("gui.xpr.close.name"), List.of(), false));
    }

    private ItemStack boostItem(PlayerData d, Boost b) {
        MessageManager msg = plugin.messages();
        XPRManager xpr = plugin.xpr();
        BoostManager bm = xpr.boosts();
        PlayerData.BoostState st = d.state(b.id());

        if (st == null || !st.unlocked) {
            return GuiItems.of(Material.GRAY_DYE, msg.get("gui.xpr.locked.name"),
                    msg.list("gui.xpr.locked.lore"), false);
        }

        BoostLevel lv = xpr.levelOf(b, st);
        int max = bm.maxLevel();
        XPRManager.BoostStatus status = xpr.statusOf(d, b);
        String statusRaw = switch (status.state()) {
            case ACTIVE -> msg.raw("gui.xpr.status.active").replace("<time>", TimeUtil.format(status.remainingMillis()));
            case COOLDOWN -> msg.raw("gui.xpr.status.cooldown").replace("<time>", TimeUtil.format(status.remainingMillis()));
            default -> msg.raw("gui.xpr.status.ready");
        };

        String next;
        if (xpr.progression().isFullyMaxed(st, b)) {
            next = msg.raw("gui.xpr.next.max");
        } else if (lv.level() >= max) {
            next = msg.raw("gui.xpr.next.pending").replace("<next>", TextUtil.roman(lv.tier() + 1));
        } else if (lv.tier() < b.tierCount()) {
            next = msg.raw("gui.xpr.next.tier").replace("<next>", TextUtil.roman(lv.tier() + 1))
                    .replace("<left>", String.valueOf(max - lv.level()));
        } else {
            next = msg.raw("gui.xpr.next.max-level").replace("<left>", String.valueOf(max - lv.level()));
        }

        Object[] ph = {
                "boost", b.displayName(),
                "tier", TextUtil.roman(lv.tier()),
                "level", lv.level(),
                "max", max,
                "progress", progressBar(lv.level(), max),
                "duration", TimeUtil.format(bm.duration(b, lv)),
                "cooldown", TimeUtil.format(bm.cooldown(b, lv)),
                "side_effect", xpr.sideEffectName(b, lv),
                "next", next,
                "status", statusRaw,
                "mastered", lv.level() >= max ? msg.raw("gui.xpr.mastered") : ""
        };
        Component name = msg.get("gui.xpr.boost.name", ph);
        List<Component> lore = msg.list("gui.xpr.boost.lore", ph);
        return GuiItems.of(b.icon(), name, lore, status.state() == XPRManager.BoostState.ACTIVE);
    }

    private String progressBar(int level, int max) {
        int length = 10;
        int filled = (int) Math.round((double) level / Math.max(1, max) * length);
        MessageManager msg = plugin.messages();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(msg.raw(i < filled ? "gui.xpr.bar.full" : "gui.xpr.bar.empty"));
        }
        return sb.toString();
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        XPRManager xpr = plugin.xpr();

        if (slot == CLOSE_SLOT) {
            viewer.closeInventory();
            return;
        }
        if (slot == ROLL_SLOT) {
            xpr.roll(viewer);
            refreshIfStillOpen();
            return;
        }
        String boostId = slotToBoost.get(slot);
        if (boostId == null) return;

        PlayerData d = xpr.dataOf(viewer);
        Boost b = xpr.boosts().get(boostId);
        if (d == null || b == null || !d.owns(boostId)) return;

        PlayerData.BoostState st = d.state(boostId);
        if (xpr.progression().canAdvance(st, b)) {
            // Level max: klik = buka konfirmasi tier berikutnya.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) plugin.gui().open(viewer, new TierConfirmGui(plugin, viewer, boostId));
            });
            return;
        }
        if (plugin.config().cfg().getBoolean("xpr.gui.click-to-activate", true)) {
            xpr.activate(viewer, boostId);
            refreshIfStillOpen();
        }
    }

    private void refreshIfStillOpen() {
        if (viewer.getOpenInventory().getTopInventory().getHolder() == this) render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
