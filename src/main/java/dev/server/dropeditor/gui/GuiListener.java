package dev.server.dropeditor.gui;

import dev.server.dropeditor.MythicDropEditor;
import dev.server.dropeditor.droptable.DropEntry;
import dev.server.dropeditor.util.MMOItemsHook;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
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
        Inventory top = event.getView().getTopInventory();
        String title = event.getView().getTitle();

        if (title.startsWith(GuiManager.LIST_TITLE_PREFIX)) {
            handleListClick(event, player);
        } else if (title.startsWith(GuiManager.EDITOR_TITLE_PREFIX)) {
            handleEditorClick(event, player, top);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();

        if (title.startsWith(GuiManager.LIST_TITLE_PREFIX)) {
            // No dragging in the list view
            event.setCancelled(true);
            return;
        }
        if (!title.startsWith(GuiManager.EDITOR_TITLE_PREFIX)) return;

        // Allow drag only into the drop-slot area (0-44)
        Set<Integer> slots = event.getRawSlots();
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : slots) {
            if (slot < topSize && slot >= 45) {
                // Trying to drop onto a control button -- block
                event.setCancelled(true);
                return;
            }
        }
        // If they dropped a stack into a single editor slot, treat it as
        // an add. We can't fully handle multi-slot splits cleanly here,
        // so the simplest UX is: cancel native drag, take the cursor item,
        // and add it as a drop.
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

    // ---------- list ----------

    private void handleListClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        GuiManager.ListSession ses = plugin.getGuiManager().getListSession(player);
        if (ses == null) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        switch (slot) {
            case 45 -> {
                // Search
                plugin.getChatPromptManager().prompt(player,
                    "Type a search term for mob names (or 'all' to clear).",
                    reply -> {
                        String q = reply.equalsIgnoreCase("all") ? "" : reply;
                        plugin.getGuiManager().openMobList(player, 0, q);
                    });
            }
            case 48 -> plugin.getGuiManager().openMobList(player, ses.page - 1, ses.search);
            case 50 -> plugin.getGuiManager().openMobList(player, ses.page + 1, ses.search);
            case 53 -> player.closeInventory();
            case 49 -> { /* info pane, no action */ }
            default -> {
                // Mob slot
                if (clicked.getType() == Material.SPAWNER) {
                    ItemMeta meta = clicked.getItemMeta();
                    if (meta == null) return;
                    String name = stripColor(meta.getDisplayName());
                    plugin.getGuiManager().openEditor(player, name);
                }
            }
        }
    }

    // ---------- editor ----------

    private void handleEditorClick(InventoryClickEvent event, Player player, Inventory top) {
        GuiManager.EditorSession ses = plugin.getGuiManager().getEditorSession(player);
        if (ses == null) return;

        int raw = event.getRawSlot();
        boolean inTop = raw < top.getSize();

        // Clicks in player inventory: allow normal behavior unless it's a shift-click into top
        if (!inTop) {
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                // Shift-click from player inv = add this item as a drop
                event.setCancelled(true);
                ItemStack moved = event.getCurrentItem();
                if (moved != null && moved.getType() != Material.AIR) {
                    handleItemAdded(player, moved.clone());
                }
            }
            return;
        }

        // Top inventory click
        // Control rows (45-53)
        if (raw >= 45) {
            event.setCancelled(true);
            switch (raw) {
                case 45 -> {
                    // Back to mob list
                    GuiManager.ListSession ls = plugin.getGuiManager().getListSession(player);
                    int page = ls != null ? ls.page : 0;
                    String search = ls != null ? ls.search : "";
                    plugin.getGuiManager().openMobList(player, page, search);
                }
                case 53 -> {
                    // Save
                    boolean ok = plugin.getDroptableManager()
                        .saveDrops(ses.targetFile, ses.targetKey, ses.drops, ses.preserved);
                    if (ok) {
                        player.sendMessage("\u00a7aSaved \u00a7f" + ses.drops.size()
                            + " drop(s)\u00a7a for \u00a7e" + ses.mobName
                            + "\u00a7a and reloaded MythicMobs.");
                    } else {
                        player.sendMessage("\u00a7cSave failed -- check console.");
                    }
                    player.closeInventory();
                }
                default -> { /* info pane */ }
            }
            return;
        }

        // Drop slot (0-44)
        if (raw < ses.drops.size()) {
            // Click on existing drop
            event.setCancelled(true);
            DropEntry entry = ses.drops.get(raw);

            if (event.getClick().isShiftClick()) {
                // Remove
                ses.drops.remove(raw);
                player.sendMessage("\u00a7cRemoved drop: \u00a7f" + entry.displayName());
                plugin.getGuiManager().renderEditor(player, ses);
                return;
            }

            if (event.getClick() == ClickType.LEFT) {
                promptChance(player, entry, ses);
            } else if (event.getClick() == ClickType.RIGHT) {
                promptAmount(player, entry, ses);
            }
            return;
        }

        // Click on empty drop slot
        if (event.getCursor() != null && event.getCursor().getType() != Material.AIR) {
            // Player is trying to place an item -- intercept and add it as a drop
            event.setCancelled(true);
            ItemStack placed = event.getCursor().clone();
            handleItemAdded(player, placed);
            player.setItemOnCursor(null);
        } else {
            event.setCancelled(true);
        }
    }

    // ---------- add new drop ----------

    /**
     * Called when the player drops an item into the editor (drag, shift-click,
     * or place). Detects MMOItem vs vanilla, then prompts for chance and amount.
     */
    private void handleItemAdded(Player player, ItemStack item) {
        GuiManager.EditorSession ses = plugin.getGuiManager().getEditorSession(player);
        if (ses == null || item == null || item.getType() == Material.AIR) return;

        DropEntry entry;
        String[] mm = MMOItemsHook.getTypeAndId(item);
        if (mm != null) {
            entry = new DropEntry(mm[0], mm[1], item.getType(), item.getAmount(), item.getAmount(), 1.0);
            player.sendMessage("\u00a7dDetected MMOItem: \u00a7f" + mm[0] + " / " + mm[1]);
        } else {
            entry = new DropEntry(item.getType(), item.getAmount(), item.getAmount(), 1.0);
            player.sendMessage("\u00a77Added vanilla item: \u00a7f" + item.getType().name());
        }
        ses.drops.add(entry);

        // Prompt chance, then amount
        promptChance(player, entry, ses);
    }

    // ---------- prompts ----------

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
                // After chance, prompt for amount
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
                // Reopen editor with updated entry
                plugin.getGuiManager().renderEditor(player, ses);
            });
    }

    // ---------- helpers ----------

    private static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("\u00a7[0-9a-fk-or]", "");
    }
}
