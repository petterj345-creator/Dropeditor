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
