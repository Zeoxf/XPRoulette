package id.senzy.command;

import id.senzy.SenzyPlugin;
import id.senzy.config.MessageManager;
import id.senzy.data.PlayerData;
import id.senzy.util.TextUtil;
import id.senzy.util.TimeUtil;
import id.senzy.xpr.Boost;
import id.senzy.xpr.BoostLevel;
import id.senzy.xpr.XPRManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /senzy xpr {status,boosts,info,upgrade,roll,activate,gui,help} + admin {grant,reset}. */
final class XPRCommand {

    private final SenzyPlugin plugin;

    XPRCommand(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    void handle(CommandSender sender, String[] args) {
        MessageManager msg = plugin.messages();
        if (!sender.hasPermission("senzy.xpr")) {
            msg.send(sender, "generic.no-permission");
            return;
        }
        if (args.length == 0) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status" -> status(sender);
            case "boosts" -> boosts(sender);
            case "info" -> info(sender, arg(args, 1));
            case "upgrade" -> upgrade(sender, arg(args, 1));
            case "roll" -> roll(sender);
            case "activate", "use" -> activate(sender, arg(args, 1));
            case "gui" -> openGui(sender);
            case "grant" -> grant(sender, args);
            case "reset" -> resetPlayer(sender, args);
            case "help" -> help(sender);
            default -> help(sender);
        }
    }

    private void status(CommandSender sender) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        MessageManager msg = plugin.messages();
        PlayerData d = plugin.xpr().dataOf(p);
        if (d == null) return;
        int owned = 0;
        for (Boost b : plugin.xpr().boosts().all()) if (d.owns(b.id())) owned++;
        msg.send(p, "xpr.status.header", "owned", owned, "total", plugin.xpr().boosts().all().size());
        if (d.activeBoost() != null) {
            Boost active = plugin.xpr().boosts().get(d.activeBoost());
            long remaining = Math.max(0, d.activeExpiration() - System.currentTimeMillis());
            msg.send(p, "xpr.status.active", "boost", active == null ? d.activeBoost() : active.displayName(),
                    "time", TimeUtil.format(remaining));
        } else {
            msg.send(p, "xpr.status.no-active");
        }
    }

    private void boosts(CommandSender sender) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        MessageManager msg = plugin.messages();
        PlayerData d = plugin.xpr().dataOf(p);
        if (d == null) return;
        msg.send(p, "xpr.boosts.header");
        for (Boost b : plugin.xpr().boosts().all()) {
            PlayerData.BoostState st = d.state(b.id());
            if (st == null || !st.unlocked) {
                msg.send(p, "xpr.boosts.locked-line", "boost", b.displayName());
            } else {
                BoostLevel lv = plugin.xpr().levelOf(b, st);
                msg.send(p, "xpr.boosts.line", "boost", b.displayName(), "tier", TextUtil.roman(lv.tier()),
                        "level", lv.level(), "max", plugin.xpr().boosts().maxLevel());
            }
        }
    }

    private void info(CommandSender sender, String boostId) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        MessageManager msg = plugin.messages();
        if (boostId == null) {
            msg.send(p, "generic.usage", "usage", "/senzy xpr info <boost>");
            return;
        }
        Boost b = plugin.xpr().boosts().get(boostId);
        if (b == null) {
            msg.send(p, "xpr.unknown-boost", "boost", boostId);
            return;
        }
        PlayerData d = plugin.xpr().dataOf(p);
        PlayerData.BoostState st = d == null ? null : d.state(b.id());
        if (st == null || !st.unlocked) {
            msg.send(p, "xpr.info.locked", "boost", b.displayName());
            return;
        }
        BoostLevel lv = plugin.xpr().levelOf(b, st);
        var bm = plugin.xpr().boosts();
        msg.send(p, "xpr.info.line", "boost", b.displayName(), "tier", TextUtil.roman(lv.tier()),
                "level", lv.level(), "max", bm.maxLevel(), "duration", TimeUtil.format(bm.duration(b, lv)),
                "cooldown", TimeUtil.format(bm.cooldown(b, lv)), "side_effect", plugin.xpr().sideEffectName(b, lv));
    }

    private void upgrade(CommandSender sender, String boostId) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        if (boostId == null) {
            plugin.messages().send(p, "generic.usage", "usage", "/senzy xpr upgrade <boost>");
            return;
        }
        plugin.xpr().advance(p, boostId);
    }

    private void roll(CommandSender sender) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        plugin.xpr().roll(p);
    }

    private void activate(CommandSender sender, String boostId) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        if (boostId == null) {
            plugin.messages().send(p, "generic.usage", "usage", "/senzy xpr activate <boost>");
            return;
        }
        plugin.xpr().activate(p, boostId);
    }

    private void openGui(CommandSender sender) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        plugin.gui().open(p, new id.senzy.xpr.XPRGui(plugin, p));
    }

    private void grant(CommandSender sender, String[] args) {
        if (!sender.hasPermission("senzy.admin")) {
            plugin.messages().send(sender, "generic.no-permission");
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(sender, "generic.usage", "usage", "/senzy xpr grant <player> <boost> [times]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "generic.player-not-found", "player", args[1]);
            return;
        }
        int times = 1;
        if (args.length >= 4) {
            try {
                times = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException ignored) {
                // pakai 1
            }
        }
        boolean ok = plugin.xpr().grant(target, args[2], times);
        plugin.messages().send(sender, ok ? "xpr.admin.grant-ok" : "xpr.unknown-boost",
                "player", target.getName(), "boost", args[2]);
    }

    private void resetPlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("senzy.admin")) {
            plugin.messages().send(sender, "generic.no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "generic.usage", "usage", "/senzy xpr reset <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "generic.player-not-found", "player", args[1]);
            return;
        }
        plugin.xpr().resetPlayer(target);
        plugin.messages().send(sender, "xpr.admin.reset-ok", "player", target.getName());
    }

    private void help(CommandSender sender) {
        plugin.messages().sendList(sender, "help.xpr");
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player p) return p;
        plugin.messages().send(sender, "generic.players-only");
        return null;
    }

    private String arg(String[] args, int i) {
        return args.length > i ? args[i] : null;
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> subs = new ArrayList<>(List.of("status", "boosts", "info", "upgrade", "roll", "activate", "gui", "help"));
        if (sender.hasPermission("senzy.admin")) subs.addAll(List.of("grant", "reset"));
        if (args.length == 1) return SenzyCommand.filter(subs, args[0]);
        if (args.length == 2 && List.of("info", "upgrade", "activate").contains(args[0].toLowerCase())) {
            List<String> ids = new ArrayList<>();
            for (Boost b : plugin.xpr().boosts().all()) ids.add(b.id());
            return SenzyCommand.filter(ids, args[1]);
        }
        if (args.length == 2 && List.of("grant", "reset").contains(args[0].toLowerCase())) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return SenzyCommand.filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("grant")) {
            List<String> ids = new ArrayList<>();
            for (Boost b : plugin.xpr().boosts().all()) ids.add(b.id());
            return SenzyCommand.filter(ids, args[2]);
        }
        return List.of();
    }
}
