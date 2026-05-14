package dev.server.dropeditor.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Reflection-based MMOItems hook. The plugin runs fine without MMOItems
 * installed -- isAvailable() returns false and detection always says
 * "vanilla item".
 *
 * MMOItems stamps its items with two NBT tags:
 *   MMOITEMS_ITEM_TYPE  (string)
 *   MMOITEMS_ITEM_ID    (string)
 *
 * Reads them via io.lumine.mythic.lib.api.item.NBTItem (from MythicLib,
 * shipped with MMOItems).
 */
public class MMOItemsHook {

    private static Boolean available;
    private static Method getMethod;
    private static Method hasTypeMethod;
    private static Method getTypeMethod;
    private static Method getStringMethod;

    public static boolean isAvailable() {
        if (available != null) return available;
        if (Bukkit.getPluginManager().getPlugin("MMOItems") == null) {
            available = false;
            return false;
        }
        try {
            Class<?> nbtItemClass = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            getMethod       = nbtItemClass.getMethod("get", ItemStack.class);
            hasTypeMethod   = nbtItemClass.getMethod("hasType");
            getTypeMethod   = nbtItemClass.getMethod("getType");
            getStringMethod = nbtItemClass.getMethod("getString", String.class);
            available = true;
        } catch (Throwable t) {
            available = false;
        }
        return available;
    }

    public static boolean isMMOItem(ItemStack item) {
        if (!isAvailable() || item == null) return false;
        try {
            Object nbt = getMethod.invoke(null, item);
            return (boolean) hasTypeMethod.invoke(nbt);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns {type, id} or null if not an MMOItem. */
    public static String[] getTypeAndId(ItemStack item) {
        if (!isMMOItem(item)) return null;
        try {
            Object nbt = getMethod.invoke(null, item);
            String type = (String) getTypeMethod.invoke(nbt);
            String id   = (String) getStringMethod.invoke(nbt, "MMOITEMS_ITEM_ID");
            if (type == null || id == null || id.isEmpty()) return null;
            return new String[]{type, id};
        } catch (Throwable t) {
            return null;
        }
    }
}
