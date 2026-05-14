package dev.server.dropeditor.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ItemBuilder {
    private final ItemStack stack;
    private final ItemMeta meta;

    public ItemBuilder(Material mat) {
        this.stack = new ItemStack(mat);
        this.meta  = stack.getItemMeta();
    }

    public ItemBuilder amount(int n) {
        stack.setAmount(Math.max(1, Math.min(64, n)));
        return this;
    }

    public ItemBuilder name(String s) {
        if (meta != null) meta.setDisplayName(s);
        return this;
    }

    public ItemBuilder lore(String... lines) {
        if (meta != null) meta.setLore(new ArrayList<>(Arrays.asList(lines)));
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        if (meta != null) meta.setLore(lines);
        return this;
    }

    public ItemStack build() {
        if (meta != null) stack.setItemMeta(meta);
        return stack;
    }
}
