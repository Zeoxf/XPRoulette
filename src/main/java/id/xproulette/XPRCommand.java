package id.xproulette;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class XPRCommand implements TabExecutor {

    private static final List<String> PLAYER_SUBS = List.of("status", "aturan", "preview");
    private static final List<String> ADMIN_SUBS = List.of("reload", "force", "reset");

    private final XPRoulette plugin;
    private final RouletteManager manager;
    private final Messages msg;

    XPRCommand(XPRoulette plugin, RouletteManager manager, Messages msg) {
        this.plugin = plugin;
        this.manager = manager;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "status", "info" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(msg.get("cmd.players-only"));
                    return true;
                }
                manager.sendStatus(p);
            }
            case "aturan", "rules", "help" -> manager.sendRules(sender);
            case "preview" -> {
                Integer level = null;
                if (args.length >= 2) {
                    try {
                        level = Math.max(0, Integer.parseInt(args[1]));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(msg.get("cmd.invalid-number"));
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    level = p.getLevel();
                }
                manager.sendPreview(sender, level);
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reloadAll();
                sender.sendMessage(msg.get("cmd.reloaded"));
            }
            case "force" -> {
                if (!admin(sender)) return true;
                Player target = target(sender, args);
                if (target == null) return true;
                manager.forceActivate(target);
                sender.sendMessage(msg.get("cmd.forced", "player", target.getName()));
            }
            case "reset" -> {
                if (!admin(sender)) return true;
                Player target = target(sender, args);
                if (target == null) return true;
                manager.resetPlayer(target);
                sender.sendMessage(msg.get("cmd.reset", "player", target.getName()));
            }
            default -> {
                sender.sendMessage(msg.get("cmd.usage"));
                if (sender.hasPermission("xproulette.admin")) {
                    sender.sendMessage(msg.get("cmd.usage-admin"));
                }
            }
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("xproulette.admin")) return true;
        sender.sendMessage(msg.get("cmd.no-permission"));
        return false;
    }

    /** Target = argumen ke-2, atau pengirim sendiri jika dia pemain. */
    private Player target(CommandSender sender, String[] args) {
        Player target = null;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        }
        if (target == null) {
            sender.sendMessage(msg.get("cmd.player-not-found"));
        }
        return target;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> all = new ArrayList<>(PLAYER_SUBS);
            if (sender.hasPermission("xproulette.admin")) all.addAll(ADMIN_SUBS);
            for (String s : all) {
                if (s.startsWith(prefix)) out.add(s);
            }
        } else if (args.length == 2 && sender.hasPermission("xproulette.admin")
                && (args[0].equalsIgnoreCase("force") || args[0].equalsIgnoreCase("reset"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
