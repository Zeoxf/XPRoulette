package id.senzy.command;

import id.senzy.SenzyPlugin;
import id.senzy.config.MessageManager;
import id.senzy.lootbox.LootBox;
import id.senzy.lootbox.LootBoxGui;
import id.senzy.lootbox.LootBoxLocationManager;
import id.senzy.lootbox.LootBoxManager;
import id.senzy.util.LocationUtil;
import id.senzy.util.TimeUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /senzy lootbox {status,locate,gui,help} + admin {start,stop,reset,locations,adjust}. */
final class LootBoxCommand {

    private final SenzyPlugin plugin;

    LootBoxCommand(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    void handle(CommandSender sender, String[] args) {
        MessageManager msg = plugin.messages();
        if (!sender.hasPermission("senzy.lootbox")) {
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
            case "locate" -> locate(sender);
            case "gui" -> openGui(sender);
            case "start" -> admin(sender, () -> plugin.lootbox().start(sender));
            case "stop" -> admin(sender, () -> plugin.lootbox().stop(sender));
            case "reset" -> admin(sender, () -> plugin.lootbox().reset(sender));
            case "reload" -> admin(sender, () -> {
                plugin.reloadSenzy();
                msg.send(sender, "generic.reloaded");
            });
            case "locations" -> admin(sender, () -> locations(sender, args));
            case "adjust" -> admin(sender, () -> adjust(sender, args));
            case "help" -> help(sender);
            default -> help(sender);
        }
    }

    private void admin(CommandSender sender, Runnable action) {
        if (!sender.hasPermission("senzy.lootbox.admin")) {
            plugin.messages().send(sender, "generic.no-permission");
            return;
        }
        action.run();
    }

    private void status(CommandSender sender) {
        MessageManager msg = plugin.messages();
        LootBoxManager lb = plugin.lootbox();
        if (!lb.isActive()) {
            msg.send(sender, "lootbox.status.inactive-line");
            return;
        }
        msg.send(sender, "lootbox.status.active-line", "opened", lb.openedCount(), "total", lb.totalCount(),
                "time", TimeUtil.format(lb.remainingMillis()));
    }

    private void locate(CommandSender sender) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        MessageManager msg = plugin.messages();
        LootBoxManager lb = plugin.lootbox();
        if (!lb.isActive()) {
            msg.send(p, "lootbox.locate.inactive");
            return;
        }
        LootBox box = lb.nearest(p.getLocation());
        if (box == null) {
            msg.send(p, "lootbox.locate.none");
            return;
        }
        double dx = box.x() + 0.5 - p.getLocation().getX();
        double dz = box.z() + 0.5 - p.getLocation().getZ();
        double dist = LocationUtil.distance2D(p.getLocation().getX(), p.getLocation().getZ(), box.x() + 0.5, box.z() + 0.5);
        String direction = msg.raw("direction." + LocationUtil.DIRECTION_KEYS[LocationUtil.compassIndex(dx, dz)]);
        String rarity = msg.raw("rarity." + box.rarity().id());
        if (lb.revealCoordinates()) {
            msg.send(p, "lootbox.locate.found-coords", "rarity", rarity, "distance", Math.round(dist),
                    "direction", direction, "x", box.x(), "y", box.y(), "z", box.z());
        } else {
            msg.send(p, "lootbox.locate.found", "rarity", rarity, "distance", Math.round(dist), "direction", direction);
        }
    }

    private void openGui(CommandSender sender) {
        Player p = requirePlayer(sender);
        if (p == null) return;
        plugin.gui().open(p, new LootBoxGui(plugin, p));
    }

    private void locations(CommandSender sender, String[] args) {
        MessageManager msg = plugin.messages();
        LootBoxManager lb = plugin.lootbox();
        if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
            Player p = requirePlayer(sender);
            if (p == null) return;
            var at = p.getLocation();
            lb.locations().setAnchor(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), true);
            msg.send(sender, "lootbox.admin.anchor-set", "world", at.getWorld().getName(),
                    "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ());
            return;
        }
        if (sender instanceof Player p) {
            plugin.gui().open(p, new id.senzy.lootbox.LootBoxLocationsGui(plugin, p));
            return;
        }
        var area = lb.locations().area();
        msg.send(sender, "lootbox.admin.area-info", "world", area.world(), "mode", area.mode().name(),
                "x", area.centerX(), "y", area.centerY(), "z", area.centerZ(), "radius", area.effectiveRadius());
    }

    private void adjust(CommandSender sender, String[] args) {
        MessageManager msg = plugin.messages();
        LootBoxManager lb = plugin.lootbox();
        if (args.length < 2) {
            msg.send(sender, "generic.usage", "usage", "/senzy lootbox adjust <point|radius> [angka]");
            return;
        }
        String action = args[1].toLowerCase();
        if (action.equals("point")) {
            Player p = requirePlayer(sender);
            if (p == null) return;
            var at = p.getLocation();
            lb.locations().setAnchor(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), true);
            msg.send(sender, "lootbox.admin.anchor-set", "world", at.getWorld().getName(),
                    "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ());
        } else if (action.equals("radius")) {
            if (args.length < 3) {
                msg.send(sender, "generic.usage", "usage", "/senzy lootbox adjust radius <angka>");
                return;
            }
            try {
                int radius = Math.max(1, Integer.parseInt(args[2]));
                if (lb.locations().area().mode() == LootBoxLocationManager.Mode.POINT) {
                    lb.locations().setAdjustRadius(radius);
                } else {
                    lb.locations().setRadius(radius);
                }
                msg.send(sender, "lootbox.admin.radius-set", "radius", radius);
            } catch (NumberFormatException e) {
                msg.send(sender, "generic.invalid-number", "value", args[2]);
            }
        } else {
            msg.send(sender, "generic.usage", "usage", "/senzy lootbox adjust <point|radius> [angka]");
        }
    }

    private void help(CommandSender sender) {
        plugin.messages().sendList(sender, "help.lootbox");
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player p) return p;
        plugin.messages().send(sender, "generic.players-only");
        return null;
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> subs = new ArrayList<>(List.of("status", "locate", "gui", "help"));
        if (sender.hasPermission("senzy.lootbox.admin")) {
            subs.addAll(List.of("start", "stop", "reset", "reload", "locations", "adjust"));
        }
        if (args.length == 1) return SenzyCommand.filter(subs, args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("locations")) return SenzyCommand.filter(List.of("set"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("adjust")) return SenzyCommand.filter(List.of("point", "radius"), args[1]);
        return List.of();
    }
}
