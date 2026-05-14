package dev.server.dropeditor.droptable;

import dev.server.dropeditor.MythicDropEditor;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.api.mobs.MythicMob;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads MythicMobs mob/droptable YAML files and writes edited drops back.
 *
 * Each mob's drop list lives either:
 *   - inline under the mob YAML  (Mobs/<file>.yml -> MobName: Drops: [...])
 *   - in a separate droptable file referenced via "DropTable: SomeTable"
 *     (DropTables/<file>.yml -> SomeTable: Drops: [...])
 *
 * We auto-detect which one and edit the right file. Unparseable lines
 * (custom MythicMobs items with metadata, conditions, etc.) are preserved
 * verbatim and tracked separately.
 */
public class DroptableManager {

    private static final Pattern MMOITEM_PATTERN = Pattern.compile(
        "^MMOITEM\\{type=([^;]+);id=([^}]+)\\}\\s+(\\S+)(?:\\s+(\\S+))?$",
        Pattern.CASE_INSENSITIVE
    );

    private final MythicDropEditor plugin;

    public DroptableManager(MythicDropEditor plugin) {
        this.plugin = plugin;
    }

    // ---------- mob discovery ----------

    public List<String> getAllMobNames() {
        List<String> names = new ArrayList<>();
        for (MythicMob mob : MythicBukkit.inst().getMobManager().getMobTypes()) {
            names.add(mob.getInternalName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public List<String> searchMobs(String query) {
        if (query == null || query.isEmpty()) return getAllMobNames();
        String lower = query.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String name : getAllMobNames()) {
            if (name.toLowerCase().contains(lower)) out.add(name);
        }
        return out;
    }

    // ---------- file discovery ----------

    private File mythicDataFolder() {
        return plugin.getServer().getPluginManager().getPlugin("MythicMobs").getDataFolder();
    }

    /**
     * Case-insensitive folder resolver. On Linux filesystems "Mobs" and "mobs"
     * are different folders -- MythicMobs accepts either, so we have to too.
     * Returns the first existing folder matching any of the given names.
     */
    private File findSubfolder(String... candidates) {
        File parent = mythicDataFolder();
        // First try exact matches (fast path)
        for (String c : candidates) {
            File f = new File(parent, c);
            if (f.exists() && f.isDirectory()) return f;
        }
        // Fall back to case-insensitive scan of parent dir
        File[] children = parent.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            for (String c : candidates) {
                if (child.getName().equalsIgnoreCase(c)) return child;
            }
        }
        return null;
    }

    public File findMobFile(String mobName) {
        File dir = findSubfolder("Mobs", "mobs");
        if (dir == null) return null;
        return searchYamlForKey(dir, mobName);
    }

    public File findDroptableFile(String droptableName) {
        File dir = findSubfolder("DropTables", "droptables", "Droptables");
        if (dir == null) return null;
        return searchYamlForKey(dir, droptableName);
    }

    private File searchYamlForKey(File dir, String topLevelKey) {
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = searchYamlForKey(f, topLevelKey);
                if (found != null) return found;
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".yml") || n.endsWith(".yaml")) {
                    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                    if (cfg.contains(topLevelKey)) return f;
                }
            }
        }
        return null;
    }

    // ---------- load ----------

    public LoadResult loadDropsForMob(String mobName) {
        File mobFile = findMobFile(mobName);
        if (mobFile == null) return LoadResult.error("Mob file not found.");

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(mobFile);
        ConfigurationSection mobSec = cfg.getConfigurationSection(mobName);
        if (mobSec == null) return LoadResult.error("Mob section missing.");

        // Follow DropTable: reference if present, otherwise edit inline
        String dropTableRef = mobSec.getString("DropTable");
        File   targetFile = mobFile;
        String targetKey  = mobName;

        if (dropTableRef != null && !dropTableRef.isEmpty()) {
            File dtFile = findDroptableFile(dropTableRef);
            if (dtFile != null) {
                targetFile = dtFile;
                targetKey  = dropTableRef;
            } else {
                // Reference exists but we couldn't find the file -- create one
                File dtDir = new File(mythicDataFolder(), "DropTables");
                if (!dtDir.exists()) dtDir.mkdirs();
                targetFile = new File(dtDir, dropTableRef + ".yml");
                targetKey  = dropTableRef;
            }
        }
        return parseDrops(targetFile, targetKey);
    }

    private LoadResult parseDrops(File file, String key) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        List<String> rawLines = cfg.getStringList(key + ".Drops");
        List<DropEntry> drops = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();

        for (String line : rawLines) {
            DropEntry e = parseLine(line);
            if (e != null) drops.add(e);
            else unparsed.add(line);
        }

        LoadResult r = LoadResult.ok(drops, unparsed);
        r.targetFile = file;
        r.targetKey  = key;
        return r;
    }

    private DropEntry parseLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;

        // MMOItem: "MMOITEM{type=X;id=Y} AMOUNT CHANCE"
        Matcher mm = MMOITEM_PATTERN.matcher(trimmed);
        if (mm.matches()) {
            String type = mm.group(1).trim();
            String id   = mm.group(2).trim();
            int[] amt = parseAmount(mm.group(3));
            if (amt == null) return null;
            double chance = parseChance(mm.group(4));
            return new DropEntry(type, id, Material.PAPER, amt[0], amt[1], chance);
        }

        // Vanilla: "MATERIAL AMOUNT CHANCE"
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) return null;
        if (parts[0].contains("{") || parts[0].contains("@")) return null; // unknown metadata

        Material mat = Material.matchMaterial(parts[0]);
        if (mat == null) return null;
        int[] amt = parseAmount(parts[1]);
        if (amt == null) return null;
        double chance = parts.length >= 3 ? parseChance(parts[2]) : 1.0;
        return new DropEntry(mat, amt[0], amt[1], chance);
    }

    private int[] parseAmount(String s) {
        if (s == null) return null;
        try {
            if (s.contains("-")) {
                String[] r = s.split("-");
                return new int[]{ Integer.parseInt(r[0]), Integer.parseInt(r[1]) };
            }
            int v = Integer.parseInt(s);
            return new int[]{ v, v };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseChance(String s) {
        if (s == null) return 1.0;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return 1.0; }
    }

    // ---------- save ----------

    public boolean saveDrops(File file, String key, List<DropEntry> drops, List<String> preserved) {
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            List<String> out = new ArrayList<>();
            for (DropEntry e : drops) out.add(e.toMythicLine());
            if (preserved != null) out.addAll(preserved);
            cfg.set(key + ".Drops", out);
            cfg.save(file);
            reloadMythicMobs();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save droptable: " + ex.getMessage());
            return false;
        }
    }

    private void reloadMythicMobs() {
        // Safest cross-version reload is the console command
        Bukkit.getScheduler().runTask(plugin, () ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm reload")
        );
    }

    // ---------- droptable browsing ----------

    /**
     * Returns every top-level key found in every YAML file under DropTables/.
     * Each key is a droptable name that can be referenced from a mob's
     * DropTable: field.
     */
    public List<String> getAllDroptableNames() {
        List<String> names = new ArrayList<>();
        File dir = findSubfolder("DropTables", "droptables", "Droptables");
        if (dir == null) return names;
        collectTopLevelKeys(dir, names);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public List<String> searchDroptables(String query) {
        if (query == null || query.isEmpty()) return getAllDroptableNames();
        String lower = query.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String name : getAllDroptableNames()) {
            if (name.toLowerCase().contains(lower)) out.add(name);
        }
        return out;
    }

    private void collectTopLevelKeys(File dir, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectTopLevelKeys(f, out);
                continue;
            }
            String n = f.getName().toLowerCase();
            if (!n.endsWith(".yml") && !n.endsWith(".yaml")) continue;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            for (String k : cfg.getKeys(false)) out.add(k);
        }
    }

    /**
     * Loads a droptable directly by name (not via a mob).
     */
    public LoadResult loadDroptableByName(String droptableName) {
        File file = findDroptableFile(droptableName);
        if (file == null) return LoadResult.error("Droptable not found.");
        return parseDrops(file, droptableName);
    }

    /**
     * Writes a "DropTable: <name>" field into the mob's YAML, replacing any
     * existing reference. Also reloads MythicMobs so the change goes live.
     *
     * Returns true on success.
     */
    public boolean linkDroptableToMob(String mobName, String droptableName) {
        File mobFile = findMobFile(mobName);
        if (mobFile == null) {
            plugin.getLogger().warning("Cannot link droptable: mob file for "
                + mobName + " not found.");
            return false;
        }
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(mobFile);
            ConfigurationSection mobSec = cfg.getConfigurationSection(mobName);
            if (mobSec == null) {
                plugin.getLogger().warning("Mob section " + mobName + " missing in file.");
                return false;
            }
            mobSec.set("DropTable", droptableName);
            // Also remove any inline droptable references from the Drops list
            // so there is only one source of truth.
            stripInlineDroptableRefs(mobSec);
            cfg.save(mobFile);
            reloadMythicMobs();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to link droptable: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Removes the DropTable: field from a mob, reverting it to using only
     * inline drops (or no drops if there are none). Also removes inline
     * droptable references from the Drops list.
     */
    public boolean unlinkDroptableFromMob(String mobName) {
        File mobFile = findMobFile(mobName);
        if (mobFile == null) return false;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(mobFile);
            ConfigurationSection mobSec = cfg.getConfigurationSection(mobName);
            if (mobSec == null) return false;
            mobSec.set("DropTable", null);
            stripInlineDroptableRefs(mobSec);
            cfg.save(mobFile);
            reloadMythicMobs();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to unlink droptable: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Removes any single-word lines from the mob's Drops list that match
     * the name of a known droptable. Leaves item entries alone.
     */
    private void stripInlineDroptableRefs(ConfigurationSection mobSec) {
        List<String> drops = mobSec.getStringList("Drops");
        if (drops.isEmpty()) return;
        List<String> droptableNames = getAllDroptableNames();
        if (droptableNames.isEmpty()) return;
        List<String> filtered = new ArrayList<>();
        for (String line : drops) {
            if (line == null) { filtered.add(line); continue; }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.contains(" ")) {
                filtered.add(line);
                continue;
            }
            boolean isDroptableRef = false;
            for (String dt : droptableNames) {
                if (dt.equalsIgnoreCase(trimmed)) { isDroptableRef = true; break; }
            }
            if (!isDroptableRef) filtered.add(line);
        }
        mobSec.set("Drops", filtered);
    }

    /**
     * Returns the droptable currently linked to this mob, or null.
     *
     * MythicMobs supports TWO ways to reference a droptable from a mob:
     *   1. A top-level field:  DropTable: OW_T1_MMOItems
     *   2. As a single-word entry inside the Drops list:
     *        Drops:
     *        - OW_T1_MMOItems     <-- this is a droptable reference
     *        - ROTTEN_FLESH 1-2
     *
     * This method checks both. If multiple are present, the DropTable: field
     * takes precedence (it's the more explicit form).
     */
    public String getLinkedDroptable(String mobName) {
        File mobFile = findMobFile(mobName);
        if (mobFile == null) return null;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(mobFile);
        ConfigurationSection mobSec = cfg.getConfigurationSection(mobName);
        if (mobSec == null) return null;

        // Form 1: explicit DropTable: field
        String ref = mobSec.getString("DropTable");
        if (ref != null && !ref.isEmpty()) return ref;

        // Form 2: single-word droptable name inside the Drops list
        List<String> droptableNames = getAllDroptableNames();
        if (droptableNames.isEmpty()) return null;
        for (String line : mobSec.getStringList("Drops")) {
            if (line == null) continue;
            String trimmed = line.trim();
            // Skip lines that have a space (those are item drops, not refs)
            if (trimmed.isEmpty() || trimmed.contains(" ")) continue;
            // It's a single-word entry -- is it a known droptable?
            for (String dt : droptableNames) {
                if (dt.equalsIgnoreCase(trimmed)) return dt;
            }
        }
        return null;
    }

    // ---------- result ----------

    public static class LoadResult {
        public List<DropEntry> drops;
        public List<String> preserved;
        public String error;
        public File targetFile;
        public String targetKey;

        public static LoadResult ok(List<DropEntry> drops, List<String> preserved) {
            LoadResult r = new LoadResult();
            r.drops = drops;
            r.preserved = preserved;
            return r;
        }
        public static LoadResult error(String msg) {
            LoadResult r = new LoadResult();
            r.error = msg;
            r.drops = new ArrayList<>();
            r.preserved = new ArrayList<>();
            return r;
        }
    }
}
