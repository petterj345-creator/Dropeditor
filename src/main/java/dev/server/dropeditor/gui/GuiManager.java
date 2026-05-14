package dev.server.dropeditor.gui;

import dev.server.dropeditor.MythicDropEditor;
import dev.server.dropeditor.droptable.DropEntry;
import dev.server.dropeditor.droptable.DroptableManager;
import dev.server.dropeditor.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Three GUI types:
 *   1. Main menu     - choose "Edit mobs" or "Edit droptables".
 *   2. List          - paginated, searchable list of mobs OR droptables.
 *   3. Editor        - drop editor (reused for both mob inline drops and
 *                      direct droptable editing). Has Link/Unlink buttons
 *                      when editing a mob.
 *
 * The droptable list is also used in "link mode" - opened from the mob
 * editor when the player clicks Link Droptable. In that mode, clicking a
 * droptable links it to the current mob instead of opening it.
 */
public class GuiManager {

    public static final String MENU_TITLE        = "\u00a78Drop Editor";
    public static final String MOB_LIST_PREFIX   = "\u00a78Mobs";
    public static final String DT_LIST_PREFIX    = "\u00a78Droptables";
    public static final String DT_LINK_PREFIX    = "\u00a78Link droptable to: \u00a7e";
    public static final String EDITOR_PREFIX     = "\u00a78Editing: \u00a7e";
    public static final String DT_EDITOR_PREFIX  = "\u00a78Droptable: \u00a7d";

    private static final int LIST_SIZE     = 54;
    private static final int LIST_CONTENT  = 45;
    private static final int EDITOR_SIZE   = 54;
    private static final int EDITOR_SLOTS  = 45;

    private static final int SLOT_SEARCH   = 45;
    private static final int SLOT_PREV     = 48;
    private static final int SLOT_INFO     = 49;
    private static final int SLOT_NEXT     = 50;
    private static final int SLOT_CLOSE    = 53;

    private static final int SLOT_BACK     = 45;
    private static final int SLOT_LINK     = 46;
    private static final int SLOT_UNLINK   = 47;
    private static final int SLOT_INFO_ED  = 49;
    private static final int SLOT_SAVE     = 53;

    private final MythicDropEditor plugin;
    private final Map<UUID, EditorSession> sessions = new HashMap<>();
    private final Map<UUID, ListSession>   lists    = new HashMap<>();

    public GuiManager(MythicDropEditor plugin) {
        this.plugin = plugin;
    }

    // ===== main menu =====

    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE);

        inv.setItem(11, new ItemBuilder(Material.SPAWNER)
            .name("\u00a7eEdit mob drops")
            .lore("\u00a77Browse MythicMobs and edit",
                  "\u00a77their drops directly.").build());

        inv.setItem(15, new ItemBuilder(Material.CHEST)
            .name("\u00a7dEdit droptables")
            .lore("\u00a77Browse droptables and edit them",
                  "\u00a77without going through a mob.").build());

        inv.setItem(22, new ItemBuilder(Material.BARRIER)
            .name("\u00a7cClose").build());

        player.openInventory(inv);
    }

    // ===== mob list =====

    public void openMobList(Player player, int page, String search) {
        DroptableManager dm = plugin.getDroptableManager();
        List<String> mobs = dm.searchMobs(search);
        int totalPages = Math.max(1, (int) Math.ceil(mobs.size() / (double) LIST_CONTENT));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = MOB_LIST_PREFIX
            + (search.isEmpty() ? "" : " (search: " + search + ")")
            + " - page " + (page + 1) + "/" + totalPages;
        Inventory inv = Bukkit.createInventory(null, LIST_SIZE, title);

        int start = page * LIST_CONTENT;
        int end   = Math.min(start + LIST_CONTENT, mobs.size());
        for (int i = start; i < end; i++) {
            String mob = mobs.get(i);
            String linked = dm.getLinkedDroptable(mob);
            inv.setItem(i - start, new ItemBuilder(Material.SPAWNER)
                .name("\u00a7e" + mob)
                .lore("\u00a77Click to edit this mob's drops.",
                      linked != null
                        ? "\u00a7dLinked: \u00a7f" + linked
                        : "\u00a78No droptable linked")
                .build());
        }

        addListNav(inv, page, totalPages, mobs.size(), search);
        lists.put(player.getUniqueId(), ListSession.mobList(page, search));
        player.openInventory(inv);
    }

    // ===== droptable list (browse OR link mode) =====

    public void openDroptableList(Player player, int page, String search) {
        renderDroptableList(player, page, search, null);
    }

    public void openDroptableLinkPicker(Player player, String mobName, int page, String search) {
        renderDroptableList(player, page, search, mobName);
    }

    private void renderDroptableList(Player player, int page, String search, String linkForMob) {
        DroptableManager dm = plugin.getDroptableManager();
        List<String> tables = dm.searchDroptables(search);
        int totalPages = Math.max(1, (int) Math.ceil(tables.size() / (double) LIST_CONTENT));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String prefix = linkForMob != null ? (DT_LINK_PREFIX + linkForMob) : DT_LIST_PREFIX;
        String title = prefix
            + (search.isEmpty() ? "" : " (search: " + search + ")")
            + " - page " + (page + 1) + "/" + totalPages;
        Inventory inv = Bukkit.createInventory(null, LIST_SIZE, title);

        int start = page * LIST_CONTENT;
        int end   = Math.min(start + LIST_CONTENT, tables.size());
        for (int i = start; i < end; i++) {
            String name = tables.get(i);
            String[] lore = linkForMob != null
                ? new String[]{ "\u00a77Click to link this droptable to",
                                "\u00a7e" + linkForMob + "\u00a77." }
                : new String[]{ "\u00a77Click to edit this droptable." };
            inv.setItem(i - start, new ItemBuilder(Material.CHEST)
                .name("\u00a7d" + name).lore(lore).build());
        }

        addListNav(inv, page, totalPages, tables.size(), search);
        lists.put(player.getUniqueId(),
            linkForMob == null
                ? ListSession.dtList(page, search)
                : ListSession.dtLink(page, search, linkForMob));
        player.openInventory(inv);
    }

    private void addListNav(Inventory inv, int page, int totalPages, int count, String search) {
        inv.setItem(SLOT_SEARCH, new ItemBuilder(Material.OAK_SIGN)
            .name("\u00a7bSearch")
            .lore("\u00a77Click to search by name.",
                  search.isEmpty() ? "\u00a78(no filter)" : "\u00a77Current: \u00a7f" + search)
            .build());
        if (page > 0) {
            inv.setItem(SLOT_PREV, new ItemBuilder(Material.ARROW)
                .name("\u00a7ePrevious page").build());
        }
        if (page < totalPages - 1) {
            inv.setItem(SLOT_NEXT, new ItemBuilder(Material.ARROW)
                .name("\u00a7eNext page").build());
        }
        inv.setItem(SLOT_INFO, new ItemBuilder(Material.PAPER)
            .name("\u00a7f" + count + " entr" + (count == 1 ? "y" : "ies"))
            .lore("\u00a77Page " + (page + 1) + " of " + totalPages).build());
        inv.setItem(SLOT_CLOSE, new ItemBuilder(Material.BARRIER)
            .name("\u00a7cClose").build());
    }

    // ===== editor =====

    public void openMobEditor(Player player, String mobName) {
        DroptableManager.LoadResult res = plugin.getDroptableManager().loadDropsForMob(mobName);
        if (res.error != null) {
            player.sendMessage("\u00a7cCouldn't load: " + res.error);
            return;
        }
        EditorSession ses = new EditorSession();
        ses.mode        = EditorMode.MOB;
        ses.subjectName = mobName;
        ses.targetFile  = res.targetFile;
        ses.targetKey   = res.targetKey;
        ses.drops       = new ArrayList<>(res.drops);
        ses.preserved   = new ArrayList<>(res.preserved);
        renderEditor(player, ses);
    }

    public void openDroptableEditor(Player player, String droptableName) {
        DroptableManager.LoadResult res = plugin.getDroptableManager().loadDroptableByName(droptableName);
        if (res.error != null) {
            player.sendMessage("\u00a7cCouldn't load: " + res.error);
            return;
        }
        EditorSession ses = new EditorSession();
        ses.mode        = EditorMode.DROPTABLE;
        ses.subjectName = droptableName;
        ses.targetFile  = res.targetFile;
        ses.targetKey   = res.targetKey;
        ses.drops       = new ArrayList<>(res.drops);
        ses.preserved   = new ArrayList<>(res.preserved);
        renderEditor(player, ses);
    }

    public void renderEditor(Player player, EditorSession ses) {
        String prefix = ses.mode == EditorMode.MOB ? EDITOR_PREFIX : DT_EDITOR_PREFIX;
        Inventory inv = Bukkit.createInventory(null, EDITOR_SIZE, prefix + ses.subjectName);

        for (int i = 0; i < ses.drops.size() && i < EDITOR_SLOTS; i++) {
            inv.setItem(i, renderDrop(ses.drops.get(i)));
        }

        inv.setItem(SLOT_BACK, new ItemBuilder(Material.ARROW)
            .name("\u00a7eBack").build());

        if (ses.mode == EditorMode.MOB) {
            String linked = plugin.getDroptableManager().getLinkedDroptable(ses.subjectName);
            inv.setItem(SLOT_LINK, new ItemBuilder(Material.CHEST)
                .name("\u00a7dLink droptable")
                .lore("\u00a77Pick a droptable to link to this mob.",
                      linked != null
                        ? "\u00a77Currently linked: \u00a7f" + linked
                        : "\u00a78No droptable currently linked")
                .build());
            if (linked != null) {
                inv.setItem(SLOT_UNLINK, new ItemBuilder(Material.LAVA_BUCKET)
                    .name("\u00a7cUnlink droptable")
                    .lore("\u00a77Removes the DropTable: reference.").build());
            }
        }

        List<String> infoLore = new ArrayList<>();
        infoLore.add("\u00a77\u00bb Drag items in to add drops");
        infoLore.add("\u00a77\u00bb \u00a7eLeft-click\u00a77: set drop chance (1-100%)");
        infoLore.add("\u00a77\u00bb \u00a7eRight-click\u00a77: set amount");
        infoLore.add("\u00a77\u00bb \u00a7eShift-click\u00a77: remove drop");
        infoLore.add("\u00a77\u00bb \u00a7eSave\u00a77: writes to file + reloads MM");
        if (!ses.preserved.isEmpty())
            infoLore.add("\u00a76" + ses.preserved.size() + " advanced line(s) preserved");
        if (ses.mode == EditorMode.DROPTABLE)
            infoLore.add("\u00a7dEditing droptable directly");
        inv.setItem(SLOT_INFO_ED, new ItemBuilder(Material.BOOK)
            .name("\u00a7fHow to use").lore(infoLore).build());

        inv.setItem(SLOT_SAVE, new ItemBuilder(Material.EMERALD_BLOCK)
            .name("\u00a7aSave & reload")
            .lore("\u00a77Writes changes to MythicMobs",
                  "\u00a77and runs \u00a7f/mm reload\u00a77.").build());

        sessions.put(player.getUniqueId(), ses);
        player.openInventory(inv);
    }

    private ItemStack renderDrop(DropEntry e) {
        String chance = e.getChancePercent() + "%";
        String amount = e.getMinAmount() == e.getMaxAmount()
            ? String.valueOf(e.getMinAmount())
            : e.getMinAmount() + "-" + e.getMaxAmount();

        List<String> lore = new ArrayList<>();
        lore.add("\u00a77Chance: \u00a7f" + chance);
        lore.add("\u00a77Amount: \u00a7f" + amount);
        lore.add(e.getKind() == DropEntry.Kind.MMOITEM
            ? "\u00a7dMMOItem: \u00a7f" + e.getMmoType() + " / " + e.getMmoId()
            : "\u00a78Vanilla item");
        lore.add("");
        lore.add("\u00a7eLeft-click \u00a77: edit chance");
        lore.add("\u00a7eRight-click \u00a77: edit amount");
        lore.add("\u00a7eShift-click \u00a77: remove");

        // For MMOItems, try to render the actual item so the player sees
        // the correct model, display name, and lore. Fall back gracefully.
        if (e.getKind() == DropEntry.Kind.MMOITEM) {
            ItemStack icon = dev.server.dropeditor.util.MMOItemsHook.buildItem(
                e.getMmoType(), e.getMmoId());
            if (icon == null) icon = e.getCachedIcon();
            if (icon != null) {
                icon = icon.clone();
                icon.setAmount(Math.min(64, Math.max(1, e.getMaxAmount())));
                org.bukkit.inventory.meta.ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    // Keep MMOItem's display name if present; only add our lore
                    java.util.List<String> existingLore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    if (existingLore == null) existingLore = new ArrayList<>();
                    existingLore.add("");
                    existingLore.add("\u00a78\u2500\u2500 Drop settings \u2500\u2500");
                    existingLore.addAll(lore);
                    meta.setLore(existingLore);
                    icon.setItemMeta(meta);
                }
                return icon;
            }
            // No icon available -- fall through to ItemBuilder paper fallback
        }

        return new ItemBuilder(e.getMaterial())
            .amount(Math.min(64, e.getMaxAmount()))
            .name("\u00a7e" + e.displayName())
            .lore(lore).build();
    }

    // ===== session accessors =====

    public EditorSession getEditorSession(Player p) { return sessions.get(p.getUniqueId()); }
    public void clearEditorSession(Player p)        { sessions.remove(p.getUniqueId()); }
    public ListSession getListSession(Player p)     { return lists.get(p.getUniqueId()); }
    public void clearListSession(Player p)          { lists.remove(p.getUniqueId()); }

    // ===== state classes =====

    public enum EditorMode { MOB, DROPTABLE }

    public static class EditorSession {
        public EditorMode mode;
        public String subjectName;
        public File targetFile;
        public String targetKey;
        public List<DropEntry> drops;
        public List<String> preserved;
    }

    public enum ListKind { MOB, DROPTABLE, DROPTABLE_LINK }

    public static class ListSession {
        public ListKind kind;
        public int page;
        public String search;
        public String linkForMob;

        public static ListSession mobList(int page, String search) {
            ListSession s = new ListSession();
            s.kind = ListKind.MOB; s.page = page; s.search = search;
            return s;
        }
        public static ListSession dtList(int page, String search) {
            ListSession s = new ListSession();
            s.kind = ListKind.DROPTABLE; s.page = page; s.search = search;
            return s;
        }
        public static ListSession dtLink(int page, String search, String mobName) {
            ListSession s = new ListSession();
            s.kind = ListKind.DROPTABLE_LINK; s.page = page; s.search = search;
            s.linkForMob = mobName;
            return s;
        }
    }
}
