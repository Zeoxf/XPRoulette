package id.senzy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Pembuat item GUI. Item polos (tanpa custom model data / resource pack) agar aman untuk Bedrock. */
public final class GuiItems {

    private GuiItems() {}

    public static ItemStack of(Material material, Component name, List<Component> lore, boolean glow) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(noItalic(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(GuiItems::noItalic).toList());
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (glow) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    public static ItemStack filler(Material material) {
        return of(material, Component.text(" "), null, false);
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
