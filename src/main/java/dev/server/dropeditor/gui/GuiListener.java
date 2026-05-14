package dev.server.dropeditor.gui;

import dev.server.dropeditor.MythicDropEditor;
import dev.server.dropeditor.droptable.DropEntry;
import dev.server.dropeditor.util.MMOItemsHook;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Set;

public class GuiListener implements Listener {

    private final MythicDropEditor plugin;

    public GuiListener(MythicDropEditor plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();

        if (title.equals(GuiManager.MENU_TITLE)) {
            handleMenuClick(event, player);
        } else if (title.startsWith(GuiManager.MOB_LIST_PREFIX)
                || title.startsWith(GuiManager.DT_LIST_PREFIX)
                || title.startsWith(GuiManager.DT_LINK_PREFIX)) {
            handleListClick(event, player);
        } else if (title.startsWith(GuiManager.EDITOR_PREFIX)
                || title.startsWith(GuiManager.DT_EDITOR_PREFIX)) {
            handleEditorClick(event, player, event.getView().getTopInventory());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();

        // Block dragging in menus and lists
        if (title.equals(GuiManager.MENU_TITLE)
                || title.startsWith(GuiManager.MOB_LIST_PREFIX)
                || title.startsWith(GuiManager.DT_LIST_PREFIX)
                || title.startsWith(GuiManager.DT_LINK_PREFIX)) {
            event.setCancelled(true);
            return;
        }
        if (!title.startsWith(GuiManager.EDITOR_PREFIX)
                && !title.startsWith(GuiManager.DT_EDITOR_PREFIX)) return;

        Set<Integer> slots = event.getRawSlots();
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : slots) {
            if (slot < topSize && slot >= 45) {
                event.setCancelled(true);
                return;
            }
        }
        if (slots.size() == 1) {
            int slot = slots.iterator().next();
            if (slot < topSize && slot < 45) {
                event.setCancelled(true);
                ItemStack cursor = event.getOldCursor().clone();
                handleItemAdded(player, cursor);
                player.setItemOnCursor(null);
            }
        } else {
            event.setCancelled(true);
        }
    }

    // ===== main menu =====

    private void handleMenuClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        switch (clicked.getType()) {
            case SPAWNER -> plugin.getGuiManager().openMobList(player, 0, "");
            case CHEST   -> plugin.getGuiManager().openDroptableList(player, 0, "");
            case BARRIER -> player.closeInventory();
            default -> { }
        }
    }

    // ===== mob/droptable list =====

    private void handleListClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        GuiManager.ListSession ses = plugin.getGuiManager().getListSession(player);
        if (ses == null) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Bottom row controls
        switch (slot) {
            case 45 -> {
                plugin.getChatPromptManager().prompt(player,
                    "Type a search term (or 'all' to clear).",
                    reply -> {
                        String q = reply.equalsIgnoreCase("all") ? "" : reply;
                        reopenList(player, ses.kind, 0, q, ses.linkForMob);
                    });
                return;
            }
            case 48 -> { reopenList(player, ses.kind, ses.page - 1, ses.search, ses.linkForMob); return; }
            case 50 -> { reopenList(player, ses.kind, ses.page + 1, ses.search, ses.linkForMob); return; }
            case 53 -> { player.closeInventory(); return; }
            case 49 -> { return; }
        }

        // Content slot click
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        String name = stripColor(meta.getDisplayName());

        switch (ses.kind) {
            case MOB -> {
                if (clicked.getType() == Material.SPAWNER) {
                    plugin.getGuiManager().openMobEditor(player, name);
                }
            }
            case DROPTABLE -> {
                if (clicked.getType() == Material.CHEST) {
                    plugin.getGuiManager().openDroptableEditor(player, name);
                }
            }
            case DROPTABLE_LINK -> {
                if (clicked.getType() == Material.CHEST) {
                    boolean ok = plugin.getDroptableManager()
                        .linkDroptableToMob(ses.linkForMob, name);
                    if (ok) {
                        player.sendMessage("\u00a7aLinked droptable \u00a7f" + name
                            + "\u00a7a to mob \u00a7e" + ses.linkForMob);
                    } else {
                        player.sendMessage("\u00a7cFailed to link droptable. Check console.");
                    }
                    // Return to the mob editor
                    plugin.getGuiManager().openMobEditor(player, ses.linkForMob);
                }
            }
        }
    }

    private void reopenList(Player player, GuiManager.ListKind kind, int page, String search, String linkForMob) {
        switch (kind) {
            case MOB             -> plugin.getGuiManager().openMobList(player, page, search);
            case DROPTABLE       -> plugin.getGuiManager().openDroptableList(player, page, search);
            case DROPTABLE_LINK  -> plugin.getGuiManager().openDroptableLinkPicker(player, linkForMob, page, search);
        }
    }

    // ===== editor =====

    private void handleEditorClick(InventoryClickEvent event, Player player, Inventory top) {
        GuiManager.EditorSession ses = plugin.getGuiManager().getEditorSession(player);
        if (ses == null) return;

        int raw = event.getRawSlot();
        boolean inTop = raw < top.getSize();

        // Player inventory clicks
        if (!inTop) {
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                event.setCancelled(true);
                ItemStack moved = event.getCurrentItem();
                if (moved != null && moved.getType() != Material.AIR) {
                    handleItemAdded(player, moved.clone());
                }
            }
            return;
        }

        // Top inventory bottom row (controls)
        if (raw >= 45) {
            event.setCancelled(true);
            switch (raw) {
                case 45 -> {
                    // Back
                    if (ses.mode == GuiManager.EditorMode.MOB) {
                        GuiManager.ListSession ls = plugin.getGuiManager().getListSession(player);
                        int page = ls != null ? ls.page : 0;
                        String search = ls != null ? ls.search : "";
                        plugin.getGuiManager().openMobList(player, page, search);
                    } else {
                        plugin.getGuiManager().openDroptableList(player, 0, "");
                    }
                }
                case 46 -> {
                    // Link droptable (mob editor only)
                    if (ses.mode == GuiManager.EditorMode.MOB) {
                        plugin.getGuiManager()
                            .openDroptableLinkPicker(player, ses.subjectName, 0, "");
                    }
                }
                case 47 -> {
                    // Unlink droptable (mob editor only)
                    if (ses.mode == GuiManager.EditorMode.MOB) {
                        boolean ok = plugin.getDroptableManager()
                            .unlinkDroptableFromMob(ses.subjectName);
                        if (ok) {
                            player.sendMessage("\u00a7cUnlinked droptable from \u00a7e" + ses.subjectName);
                        } else {
                            player.sendMessage("\u00a7cFailed to unlink. Check console.");
                        }
                        plugin.getGuiManager().openMobEditor(player, ses.subjectName);
                    }
                }
                case 53 -> {
                    // Save
                    boolean ok = plugin.getDroptableManager()
                        .saveDrops(ses.targetFile, ses.targetKey, ses.drops, ses.preserved);
                    if (ok) {
                        player.sendMessage("\u00a7aSaved \u00a7f" + ses.drops.size()
                            + " drop(s)\u00a7a for \u00a7e" + ses.subjectName
                            + "\u00a7a and reloaded MythicMobs.");
                    } else {
                        player.sendMessage("\u00a7cSave failed -- check console.");
                    }
                    player.closeInventory();
                }
                default -> { }
            }
            return;
        }

        // Drop slot
        if (raw < ses.drops.size()) {
            event.setCancelled(true);
            DropEntry entry = ses.drops.get(raw);

            if (event.getClick().isShiftClick()) {
                ses.drops.remove(raw);
                player.sendMessage("\u00a7cRemoved drop: \u00a7f" + entry.displayName());
                plugin.getGuiManager().renderEditor(player, ses);
                return;
            }
            if (event.getClick() == ClickType.LEFT)  promptChance(player, entry, ses);
            else if (event.getClick() == ClickType.RIGHT) promptAmount(player, entry, ses);
            return;
        }

        // Empty slot - allow placing
        if (event.getCursor() != null && event.getCursor().getType() != Material.AIR) {
            event.setCancelled(true);
            ItemStack placed = event.getCursor().clone();
            handleItemAdded(player, placed);
            player.setItemOnCursor(null);
        } else {
            event.setCancelled(true);
        }
    }

    // ===== add new drop =====

    private void handleItemAdded(Player player, ItemStack item) {
        GuiManager.EditorSession ses = plugin.getGuiManager().getEditorSession(player);
        if (ses == null || item == null || item.getType() == Material.AIR) return;

        DropEntry entry;
        String[] mm = MMOItemsHook.getTypeAndId(item);
        if (mm != null) {
            entry = new DropEntry(mm[0], mm[1], item.getType(), item.getAmount(), item.getAmount(), 1.0);
            entry.setCachedIcon(item.clone()); // snapshot the real visual for the GUI
            player.sendMessage("\u00a7dDetected MMOItem: \u00a7f" + mm[0] + " / " + mm[1]);
        } else {
            entry = new DropEntry(item.getType(), item.getAmount(), item.getAmount(), 1.0);
            player.sendMessage("\u00a77Added vanilla item: \u00a7f" + item.getType().name());
        }
        ses.drops.add(entry);
        promptChance(player, entry, ses);
    }

    // ===== prompts =====

    private void promptChance(Player player, DropEntry entry, GuiManager.EditorSession ses) {
        plugin.getChatPromptManager().prompt(player,
            "How high should the drop chance be? Type a number 1-100 (= percent).",
            reply -> {
                try {
                    int pct = Integer.parseInt(reply.replace("%", "").trim());
                    if (pct < 1 || pct > 100) {
                        player.sendMessage("\u00a7cMust be between 1 and 100.");
                    } else {
                        entry.setChancePercent(pct);
                        player.sendMessage("\u00a7aChance set to \u00a7f" + pct + "%\u00a7a for \u00a7e"
                            + entry.displayName());
                    }
                } catch (NumberFormatException ex) {
                    player.sendMessage("\u00a7cNot a valid number: \"" + reply + "\"");
                }
                promptAmount(player, entry, ses);
            });
    }

    private void promptAmount(Player player, DropEntry entry, GuiManager.EditorSession ses) {
        plugin.getChatPromptManager().prompt(player,
            "How many? Type a single number (e.g. 3) or a range (e.g. 1-5).",
            reply -> {
                try {
                    if (reply.contains("-")) {
                        String[] parts = reply.split("-");
                        int min = Integer.parseInt(parts[0].trim());
                        int max = Integer.parseInt(parts[1].trim());
                        if (min < 1 || max < min) {
                            player.sendMessage("\u00a7cInvalid range. Min must be >=1 and <= max.");
                        } else {
                            entry.setMinAmount(min);
                            entry.setMaxAmount(max);
                            player.sendMessage("\u00a7aAmount set to \u00a7f" + min + "-" + max);
                        }
                    } else {
                        int n = Integer.parseInt(reply.trim());
                        if (n < 1) {
                            player.sendMessage("\u00a7cAmount must be at least 1.");
                        } else {
                            entry.setMinAmount(n);
                            entry.setMaxAmount(n);
                            player.sendMessage("\u00a7aAmount set to \u00a7f" + n);
                        }
                    }
                } catch (NumberFormatException ex) {
                    player.sendMessage("\u00a7cNot a valid number: \"" + reply + "\"");
                }
                plugin.getGuiManager().renderEditor(player, ses);
            });
    }

    private static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("\u00a7[0-9a-fk-or]", "");
    }
}
