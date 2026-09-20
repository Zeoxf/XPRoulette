package id.xproulette.lootbox;

import id.xproulette.Messages;
import id.xproulette.event.EventManager;
import id.xproulette.event.EventState;
import id.xproulette.event.StartCheck;
import id.xproulette.util.TimeParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /xpr loot [status|locations|start|stop|reload].
 * Pemain: status. Admin (xproulette.admin): start, stop, reload, locations.
 * Start/stop lewat EventManager agar aturan tabrakan event tetap berlaku.
 */
public final class LootBoxCommand {

    private static final List<String> PLAYER_SUBS = List.of("status");
    private static final List<String> ADMIN_SUBS = List.of("locations", "start", "stop", "reload");

    private final EventManager manager;
    private final LootBoxEvent event;
    private final Messages msg;

    public LootBoxCommand(EventManager manager, LootBoxEvent event, Messages msg) {
        this.manager = manager;
        this.event = event;
        this.msg = msg;
    }

    /** @param args argumen SETELAH kata "loot" */
    public void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status", "info" -> sendStatus(sender);
            case "locations", "loc" -> {
                if (admin(sender)) sendLocations(sender);
            }
            case "start" -> {
                if (admin(sender)) start(sender, args);
            }
            case "stop" -> {
                if (admin(sender)) stop(sender);
            }
            case "reload" -> {
                if (admin(sender)) {
                    event.reload();
                    manager.reload();
                    sender.sendMessage(msg.get("lootbox.cmd.reloaded"));
                }
            }
            default -> {
                sender.sendMessage(msg.get("lootbox.cmd.usage"));
                if (sender.hasPermission("xproulette.admin")) {
                    sender.sendMessage(msg.get("lootbox.cmd.usage-admin"));
                }
            }
        }
    }

    // ------------------------------------------------------------------ status

    private void sendStatus(CommandSender sender) {
        EventState st = event.getState();
        sender.sendMessage(msg.get("lootbox.status.header"));
        sender.sendMessage(msg.get("lootbox.status.state", "state", st.name()));

        switch (st) {
            case ACTIVE -> {
                sender.sendMessage(msg.get("lootbox.status.remaining",
                        "remaining", TimeParser.format(event.remainingSeconds())));
                sender.sendMessage(msg.get("lootbox.status.boxes",
                        "count", event.availableCount(), "total", event.totalBoxes()));
                sender.sendMessage(msg.get("lootbox.status.opened", "opened", event.openedCount()));
                Rarity top = event.highestRarity();
                if (top != null) {
                    sender.sendMessage(msg.get("lootbox.status.highest",
                            "rarity", event.settings().rarityName(top)));
                }
                sender.sendMessage(msg.get("lootbox.status.event-id", "event_id", event.eventId()));
            }
            case COOLDOWN -> sender.sendMessage(msg.get("lootbox.cooldown",
                    "remaining", TimeParser.format(event.cooldownRemainingSeconds())));
            case IDLE -> sender.sendMessage(msg.get("lootbox.status.idle",
                    "remaining", TimeParser.format(manager.secondsUntilNextAttempt())));
            default -> { }
        }
    }

    private void sendLocations(CommandSender sender) {
        if (event.getState() != EventState.ACTIVE) {
            sender.sendMessage(msg.get("lootbox.not-active"));
            return;
        }
        sender.sendMessage(msg.get("lootbox.locations.header", "event_id", event.eventId()));
        int n = 1;
        for (LootBox b : event.getBoxes()) {
            String key = b.isOpened() ? "lootbox.locations.line-opened" : "lootbox.locations.line";
            sender.sendMessage(msg.get(key, "n", n++, "loot_id", b.id(),
                    "rarity", event.settings().rarityName(b.rarity()),
                    "world", b.worldName(), "x", b.x(), "y", b.y(), "z", b.z(),
                    "player", b.openedBy() == null ? "-" : b.openedBy()));
        }
    }

    // ------------------------------------------------------------- start / stop

    /** /xpr loot start [pemain] [force] */
    private void start(CommandSender sender, String[] args) {
        Player target = null;
        boolean force = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("force")) {
                force = true;
            } else {
                target = Bukkit.getPlayerExact(args[i]);
                if (target == null) {
                    sender.sendMessage(msg.get("cmd.player-not-found"));
                    return;
                }
            }
        }
        if (force) event.skipCooldown();

        StartCheck check = manager.checkStart(LootBoxEvent.ID);
        switch (check) {
            case OK -> {
                LootBoxEvent.StartResult r = event.startWith(target);
                switch (r) {
                    case STARTED -> sender.sendMessage(msg.get("lootbox.cmd.started"));
                    case NO_PLAYER -> sender.sendMessage(msg.get("lootbox.no-player"));
                    case INVALID_WORLD -> sender.sendMessage(msg.get("lootbox.cmd.invalid-world"));
                    default -> sender.sendMessage(msg.get("lootbox.cmd.not-ready"));
                }
            }
            case COOLDOWN -> sender.sendMessage(msg.get("lootbox.cooldown",
                    "remaining", TimeParser.format(event.cooldownRemainingSeconds())));
            case ALREADY_RUNNING -> sender.sendMessage(msg.get("lootbox.cmd.already-running"));
            case DISABLED -> sender.sendMessage(msg.get("lootbox.cmd.disabled"));
            case BLOCKED_BY_OTHER_EVENT -> sender.sendMessage(msg.get("lootbox.cmd.blocked"));
            default -> sender.sendMessage(msg.get("lootbox.cmd.not-ready"));
        }
    }

    private void stop(CommandSender sender) {
        if (manager.stopEvent(LootBoxEvent.ID)) {
            sender.sendMessage(msg.get("lootbox.cmd.stopped"));
        } else {
            sender.sendMessage(msg.get("lootbox.cmd.not-running"));
        }
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("xproulette.admin")) return true;
        sender.sendMessage(msg.get("cmd.no-permission"));
        return false;
    }

    // --------------------------------------------------------------------- tab

    /** @param args argumen SETELAH kata "loot" */
    public List<String> tab(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("xproulette.admin");
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> all = new ArrayList<>(PLAYER_SUBS);
            if (admin) all.addAll(ADMIN_SUBS);
            for (String s : all) {
                if (s.startsWith(prefix)) out.add(s);
            }
        } else if (args.length >= 2 && admin && args[0].equalsIgnoreCase("start")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if ("force".startsWith(prefix)) out.add("force");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
