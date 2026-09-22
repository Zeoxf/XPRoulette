package id.senzy.command;

import id.senzy.SenzyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

/** /senzy [xpr|lootbox|reload|help ...] */
public final class SenzyCommand implements CommandExecutor, TabCompleter {

    private final SenzyPlugin plugin;
    private final XPRCommand xprCommand;
    private final LootBoxCommand lootBoxCommand;

    public SenzyCommand(SenzyPlugin plugin) {
        this.plugin = plugin;
        this.xprCommand = new XPRCommand(plugin);
        this.lootBoxCommand = new LootBoxCommand(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        String[] rest = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (sub) {
            case "xpr" -> xprCommand.handle(sender, rest);
            case "lootbox", "lb" -> lootBoxCommand.handle(sender, rest);
            case "reload" -> {
                if (!sender.hasPermission("senzy.admin")) {
                    plugin.messages().send(sender, "generic.no-permission");
                    return true;
                }
                plugin.reloadSenzy();
                plugin.messages().send(sender, "generic.reloaded");
            }
            case "help" -> help(sender);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        plugin.messages().sendList(sender, "help.main");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("xpr", "lootbox", "help"));
            if (sender.hasPermission("senzy.admin")) out.add("reload");
            return filter(out, args[0]);
        }
        if (args.length > 1) {
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            if (args[0].equalsIgnoreCase("xpr")) return xprCommand.tabComplete(sender, rest);
            if (args[0].equalsIgnoreCase("lootbox") || args[0].equalsIgnoreCase("lb")) return lootBoxCommand.tabComplete(sender, rest);
        }
        return List.of();
    }

    static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase();
        for (String o : options) if (o.toLowerCase().startsWith(p)) out.add(o);
        return out;
    }
}
