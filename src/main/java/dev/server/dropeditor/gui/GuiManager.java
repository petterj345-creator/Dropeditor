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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the two GUI types:
 *   1. Mob List  -- /droptable opens this. Paginated, with search.
 *   2. Editor    -- click a mob in the list to open this. Drag items in,
 *                   left-click to set chance, right-click to set amount,
 *                   shift-click to remove, save button bottom right.
 *
 * State for each open inventory is tracked in `sessions` so the listener
 * knows which mob is being edited.
 */
public class GuiManager {

    public static final String LIST_TITLE_PREFIX   = "\u00a78Droptables";
    public static final String EDITOR_TITLE_PREFIX = "\u00a78Editing: \u00a7e";

    private static final int LIST_SIZE     = 54;   // 6 rows
    private static final int LIST_CONTENT  = 45;   // 5 rows of mobs (last row = nav)
    private static final int EDITOR_SIZE   = 54;
    private static final int EDITOR_SLOTS  = 45;   // top 5 rows = drops
    private static final int SLOT_SEARCH   = 45;
    private static final int SLOT_PREV     = 48;
    private static final int SLOT_INFO     = 49;
    private static final int SLOT_NEXT     = 50;
    private static final int SLOT_CLOSE    = 53;
    private static final int SLOT_SAVE     = 53;
    private static final int SLOT_BACK     = 45;

    private final MythicDropEditor plugin;
    private final Map<UUID, EditorSession> sessions = new HashMap<>();
    private final Map<UUID, ListSession>   lists    = new HashMap<>();

    public GuiManager(MythicDropEditor plugin) {
        this.plugin = plugin;
    }

    // ===== mob list =====

    public void openMobList(Player player, int page, String search) {
        DroptableManager dm = plugin.getDroptableManager();
        List<String> mobs = dm.searchMobs(search);

        int totalPages = Math.max(1, (int) Math.ceil(mobs.size() / (double) LIST_CONTENT));
        page = Math.max(0, Math.min(page, totalPages - 1));

        String title = LIST_TITLE_PREFIX
            + (search.isEmpty() ? "" : " (search: " + search + ")")
            + " - page " + (page + 1) + "/" + totalPages;
        Inventory inv = Bukkit.createInventory(null, LIST_SIZE, title);

        int start = page * LIST_CONTENT;
        int end   = Math.min(start + LIST_CONTENT, mobs.size());
        for (int i = start; i < end; i++) {
            String mob = mobs.get(i);
            inv.setItem(i - start, new ItemBuilder(Material.SPAWNER)
                .name("\u00a7e" + mob)
                .lore("\u00a77Click to edit this mob's drops.")
                .build());
        }

        inv.setItem(SLOT_SEARCH, new ItemBuilder(Material.OAK_SIGN)
            .name("\u00a7bSearch")
            .lore("\u00a77Click to search mobs by name.",
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
            .name("\u00a7f" + mobs.size() + " mob" + (mobs.size() == 1 ? "" : "s"))
            .lore("\u00a77Page " + (page + 1) + " of " + totalPages).build());

        inv.setItem(SLOT_CLOSE, new ItemBuilder(Material.BARRIER)
            .name("\u00a7cClose").build());

        lists.put(player.getUniqueId(), new ListSession(page, search));
        player.openInventory(inv);
    }

    // ===== editor =====

    public void openEditor(Player player, String mobName) {
        DroptableManager.LoadResult res = plugin.getDroptableManager().loadDropsForMob(mobName);
        if (res.error != null) {
            player.sendMessage("\u00a7cCouldn't load: " + res.error);
            return;
        }

        EditorSession ses = new EditorSession();
        ses.mobName     = mobName;
        ses.targetFile  = res.targetFile;
        ses.targetKey   = res.targetKey;
        ses.drops       = new ArrayList<>(res.drops);
        ses.preserved   = new ArrayList<>(res.preserved);

        renderEditor(player, ses);
    }

    public void renderEditor(Player player, EditorSession ses) {
        Inventory inv = Bukkit.createInventory(null, EDITOR_SIZE, EDITOR_TITLE_PREFIX + ses.mobName);

        for (int i = 0; i < ses.drops.size() && i < EDITOR_SLOTS; i++) {
            inv.setItem(i, renderDrop(ses.drops.get(i)));
        }

        inv.setItem(SLOT_BACK, new ItemBuilder(Material.ARROW)
            .name("\u00a7eBack to mob list").build());

        inv.setItem(49, new ItemBuilder(Material.BOOK)
            .name("\u00a7fHow to use")
            .lore("\u00a77\u00bb Drag items in to add drops",
                  "\u00a77\u00bb \u00a7eLeft-click\u00a77: set drop chance (1-100%)",
                  "\u00a77\u00bb \u00a7eRight-click\u00a77: set amount",
                  "\u00a77\u00bb \u00a7eShift-click\u00a77: remove drop",
                  "\u00a77\u00bb \u00a7eSave\u00a77: writes to file + reloads MM",
                  ses.preserved.isEmpty()
                    ? "\u00a78(no advanced lines preserved)"
                    : "\u00a76" + ses.preserved.size() + " advanced line(s) preserved")
            .build());

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
        return new ItemBuilder(e.getMaterial())
            .amount(Math.min(64, e.getMaxAmount()))
            .name("\u00a7e" + e.displayName())
            .lore(
                "\u00a77Chance: \u00a7f" + chance,
                "\u00a77Amount: \u00a7f" + amount,
                e.getKind() == DropEntry.Kind.MMOITEM
                    ? "\u00a7dMMOItem: \u00a7f" + e.getMmoType() + " / " + e.getMmoId()
                    : "\u00a78Vanilla item",
                "",
                "\u00a7eLeft-click \u00a77: edit chance",
                "\u00a7eRight-click \u00a77: edit amount",
                "\u00a7eShift-click \u00a77: remove"
            )
            .build();
    }

    // ===== session accessors =====

    public EditorSession getEditorSession(Player p) {
        return sessions.get(p.getUniqueId());
    }
    public void clearEditorSession(Player p) {
        sessions.remove(p.getUniqueId());
    }
    public ListSession getListSession(Player p) {
        return lists.get(p.getUniqueId());
    }
    public void clearListSession(Player p) {
        lists.remove(p.getUniqueId());
    }

    // ===== state classes =====

    public static class EditorSession {
        public String mobName;
        public java.io.File targetFile;
        public String targetKey;
        public List<DropEntry> drops;
        public List<String> preserved;
    }

    public static class ListSession {
        public int page;
        public String search;
        public ListSession(int page, String search) {
            this.page = page;
            this.search = search;
        }
    }
}
