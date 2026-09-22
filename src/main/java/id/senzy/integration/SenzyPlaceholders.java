package id.senzy.integration;

import id.senzy.SenzyPlugin;
import id.senzy.data.PlayerData;
import id.senzy.util.TextUtil;
import id.senzy.util.TimeUtil;
import id.senzy.xpr.Boost;
import id.senzy.xpr.BoostLevel;
import id.senzy.xpr.XPRManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * %senzy_xpr_&lt;boost&gt;_tier% / _level% / _progress% / _cooldown%
 * %senzy_lootbox_time% / _remaining% / _active%
 */
public final class SenzyPlaceholders extends PlaceholderExpansion {

    private final SenzyPlugin plugin;

    public SenzyPlaceholders(SenzyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "senzy";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Senzy";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(org.bukkit.entity.Player onlinePlayer, @NotNull String params) {
        if (params.equalsIgnoreCase("lootbox_time") || params.equalsIgnoreCase("lootbox_remaining")) {
            return TimeUtil.format(plugin.lootbox().remainingMillis());
        }
        if (params.equalsIgnoreCase("lootbox_active")) {
            return String.valueOf(plugin.lootbox().isActive());
        }

        if (params.startsWith("xpr_") && onlinePlayer != null) {
            return xprPlaceholder(onlinePlayer, params.substring(4));
        }
        return null;
    }

    private String xprPlaceholder(OfflinePlayer player, String rest) {
        int lastUnderscore = rest.lastIndexOf('_');
        if (lastUnderscore <= 0) return null;
        String boostId = rest.substring(0, lastUnderscore);
        String field = rest.substring(lastUnderscore + 1);

        XPRManager xpr = plugin.xpr();
        Boost boost = xpr.boosts().get(boostId);
        if (boost == null) return "";
        PlayerData d = plugin.data().get(player.getUniqueId());
        if (d == null) return "";
        PlayerData.BoostState st = d.state(boostId);

        return switch (field.toLowerCase()) {
            case "tier" -> st == null || !st.unlocked ? "-" : TextUtil.roman(xpr.levelOf(boost, st).tier());
            case "level" -> st == null || !st.unlocked ? "-" : String.valueOf(xpr.levelOf(boost, st).level());
            case "progress" -> {
                if (st == null || !st.unlocked) yield "0%";
                int max = xpr.boosts().maxLevel();
                yield (xpr.levelOf(boost, st).level() * 100 / Math.max(1, max)) + "%";
            }
            case "cooldown" -> {
                if (st == null || st.cooldownUntil <= System.currentTimeMillis()) yield "0s";
                yield TimeUtil.format(st.cooldownUntil - System.currentTimeMillis());
            }
            default -> null;
        };
    }
}
