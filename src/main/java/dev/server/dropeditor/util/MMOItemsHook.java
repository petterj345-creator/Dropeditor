package dev.server.dropeditor.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Reflection-based MMOItems hook. The plugin runs fine without MMOItems
 * installed -- isAvailable() returns false and detection always says
 * "vanilla item".
 *
 * Used for:
 *   - Detection: read MMOITEMS_ITEM_TYPE / MMOITEMS_ITEM_ID NBT tags off
 *     items dragged into the editor.
 *   - Rendering: build the real MMOItem ItemStack (with correct model and
 *     display name) for display in the editor GUI.
 */
public class MMOItemsHook {

    private static Boolean available;

    // Detection (NBTItem)
    private static Method nbtGetMethod;
    private static Method nbtHasTypeMethod;
    private static Method nbtGetTypeMethod;
    private static Method nbtGetStringMethod;

    // Rendering (MMOItems main class)
    private static Object mmoItemsInstance;
    private static Method getTypesMethod;
    private static Method typesGetMethod;
    private static Method mmoGetItemMethod; // getItem(Type, String) -> ItemStack

    public static boolean isAvailable() {
        if (available != null) return available;
        if (Bukkit.getPluginManager().getPlugin("MMOItems") == null) {
            available = false;
            return false;
        }
        try {
            // Detection setup
            Class<?> nbtItemClass = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            nbtGetMethod       = nbtItemClass.getMethod("get", ItemStack.class);
            nbtHasTypeMethod   = nbtItemClass.getMethod("hasType");
            nbtGetTypeMethod   = nbtItemClass.getMethod("getType");
            nbtGetStringMethod = nbtItemClass.getMethod("getString", String.class);

            // Rendering setup (these may fail on some MMOItems versions; that's OK)
            try {
                Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
                // MMOItems.plugin static field (newer versions also expose getInstance())
                try {
                    mmoItemsInstance = mmoItemsClass.getField("plugin").get(null);
                } catch (NoSuchFieldException nsfe) {
                    Method inst = mmoItemsClass.getMethod("getInstance");
                    mmoItemsInstance = inst.invoke(null);
                }
                getTypesMethod = mmoItemsClass.getMethod("getTypes");

                Class<?> typeManagerClass = Class.forName("net.Indyuce.mmoitems.manager.TypeManager");
                typesGetMethod = typeManagerClass.getMethod("get", String.class);

                Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
                mmoGetItemMethod = mmoItemsClass.getMethod("getItem", typeClass, String.class);
            } catch (Throwable t) {
                // Rendering not available, but detection still works
                mmoItemsInstance = null;
            }

            available = true;
        } catch (Throwable t) {
            available = false;
        }
        return available;
    }

    public static boolean isMMOItem(ItemStack item) {
        if (!isAvailable() || item == null) return false;
        try {
            Object nbt = nbtGetMethod.invoke(null, item);
            return (boolean) nbtHasTypeMethod.invoke(nbt);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns {type, id} or null if not an MMOItem. */
    public static String[] getTypeAndId(ItemStack item) {
        if (!isMMOItem(item)) return null;
        try {
            Object nbt = nbtGetMethod.invoke(null, item);
            String type = (String) nbtGetTypeMethod.invoke(nbt);
            String id   = (String) nbtGetStringMethod.invoke(nbt, "MMOITEMS_ITEM_ID");
            if (type == null || id == null || id.isEmpty()) return null;
            return new String[]{type, id};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Builds the actual MMOItem ItemStack (with correct material model and
     * display name) for the given type+id, so the editor GUI can show what
     * the player actually configured to drop.
     *
     * Returns null if MMOItems isn't installed, the type/id doesn't exist,
     * or the reflection setup failed.
     */
    public static ItemStack buildItem(String type, String id) {
        if (!isAvailable() || mmoItemsInstance == null) return null;
        try {
            Object typeManager = getTypesMethod.invoke(mmoItemsInstance);
            Object typeObj = typesGetMethod.invoke(typeManager, type);
            if (typeObj == null) return null;
            Object result = mmoGetItemMethod.invoke(mmoItemsInstance, typeObj, id);
            return result instanceof ItemStack ? (ItemStack) result : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
