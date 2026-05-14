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

    // Matches the overall structure: MMOITEM(S) { ... } amount [chance]
    // Accepts both "mmoitem" and "mmoitems" -- MythicMobs allows either.
    private static final Pattern MMOITEM_PATTERN = Pattern.compile(
        "^MMOITEMS?\\s*\\{\\s*(.+?)\\s*\\}\\s+(\\S+)(?:\\s+(\\S+))?$",
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
                    // Fall back to text scan -- Bukkit can't parse files with
                    // unquoted curly braces in list entries (MMOItem syntax).
                    if (fileContainsTopLevelKeyText(f, topLevelKey)) return f;
                }
            }
        }
        return null;
    }

    private boolean fileContainsTopLevelKeyText(File f, String key) {
        try {
            for (String raw : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String stripped = stripComment(raw.stripTrailing()).trim();
                if (stripped.equals(key + ":") || stripped.startsWith(key + ":")) return true;
            }
        } catch (IOException ex) {
            // ignore
        }
        return false;
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
        // First try Bukkit's YAML parser (clean path).
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        List<String> rawLines = cfg.getStringList(key + ".Drops");

        // If Bukkit returns nothing for the Drops list, it's likely that
        // snakeyaml choked on the MythicMobs/MMOItems syntax (curly braces,
        // semicolons, equals signs). Fall back to a plain-text scan so we
        // can still read these files.
        if (rawLines == null || rawLines.isEmpty()) {
            List<String> textLines = parseDropsAsText(file, key);
            if (!textLines.isEmpty()) {
                rawLines = textLines;
                plugin.getLogger().info("Loaded " + textLines.size()
                    + " drops from " + key + " via text fallback (YAML parse skipped them).");
            }
        }

        List<DropEntry> drops = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();

        for (String line : rawLines) {
            DropEntry e = parseLine(line);
            if (e != null) {
                drops.add(e);
            } else {
                unparsed.add(line);
                if (line != null && line.toUpperCase().contains("MMOITEM")) {
                    plugin.getLogger().info("[DEBUG] Failed to parse MMOItem line: \""
                        + line + "\" (preserving verbatim)");
                }
            }
        }

        LoadResult r = LoadResult.ok(drops, unparsed);
        r.targetFile = file;
        r.targetKey  = key;
        return r;
    }

    /**
     * Plain-text fallback for reading a Drops list when snakeyaml can't
     * handle the content (e.g. unquoted curly braces in MMOItem lines).
     *
     * Walks the file looking for "  <key>:" at any indent, then reads its
     * "Drops:" sub-section line by line, picking out "  - <stuff>" entries
     * until indentation drops back to the section header level.
     */
    private List<String> parseDropsAsText(File file, String key) {
        List<String> out = new ArrayList<>();
        java.util.List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return out;
        }

        int sectionIndent = -1;
        boolean inSection = false;
        boolean inDrops = false;
        int dropsIndent = -1;

        for (String raw : lines) {
            String stripped = raw.stripTrailing();
            if (stripped.isEmpty()) continue;
            // Remove comments
            String noComment = stripComment(stripped);
            if (noComment.trim().isEmpty()) continue;

            int indent = leadingSpaces(noComment);

            if (!inSection) {
                // Look for "key:" at any indent level
                String t = noComment.trim();
                if (t.equals(key + ":") || t.startsWith(key + ":")) {
                    inSection = true;
                    sectionIndent = indent;
                }
                continue;
            }

            // We're inside the section. End it if indent drops back to section level
            if (indent <= sectionIndent && !inDrops) {
                // New sibling key at same level -- but only end if it's not us
                String t = noComment.trim();
                if (!t.equals(key + ":")) {
                    inSection = false;
                    inDrops = false;
                    continue;
                }
            }

            if (!inDrops) {
                String t = noComment.trim();
                if (t.equals("Drops:")) {
                    inDrops = true;
                    dropsIndent = indent;
                }
                continue;
            }

            // We're in Drops:. End if indent drops to <= dropsIndent
            if (indent <= dropsIndent) {
                inDrops = false;
                // Reprocess this line as a potential section boundary
                if (indent <= sectionIndent) inSection = false;
                continue;
            }

            // List entry: "  - something"
            String t = noComment.trim();
            if (t.startsWith("- ")) {
                String value = t.substring(2).trim();
                // Strip surrounding YAML quotes
                if (value.length() >= 2) {
                    char a = value.charAt(0);
                    char b = value.charAt(value.length() - 1);
                    if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }
                out.add(value);
            } else if (t.equals("-")) {
                // skip
            }
        }
        return out;
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private static String stripComment(String line) {
        // Strip "# ..." but only when not inside quotes. For MythicMobs lines
        // we can be naive -- there's rarely a quoted '#' in a drop entry.
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble) {
                return line.substring(0, i).stripTrailing();
            }
        }
        return line;
    }

    private DropEntry parseLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;

        // Bukkit's YamlConfiguration may write our line as a quoted string.
        // Strip surrounding single or double quotes before parsing.
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last  = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }

        // MMOItem: "MMOITEM{key1=val1;key2=val2;...} AMOUNT CHANCE"
        // Any number/order of parameters is accepted. Only type and id are
        // required; other params (like unidentified=true) are silently dropped
        // because they have no effect in current MMOItems anyway.
        Matcher mm = MMOITEM_PATTERN.matcher(trimmed);
        if (mm.matches()) {
            String params = mm.group(1);
            int[] amt = parseAmount(mm.group(2));
            if (amt == null) return null;
            double chance = parseChance(mm.group(3));

            String type = null, id = null;
            for (String pair : params.split(";")) {
                String p = pair.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq <= 0) continue;
                String k = p.substring(0, eq).trim();
                String v = p.substring(eq + 1).trim();
                if (k.equalsIgnoreCase("type"))    type = v;
                else if (k.equalsIgnoreCase("id")) id = v;
                // anything else is ignored
            }
            if (type == null || id == null) return null;
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
        // Build the new list of lines for the Drops: section
        List<String> newDropLines = new ArrayList<>();
        for (DropEntry e : drops) newDropLines.add(e.toMythicLine());
        if (preserved != null) newDropLines.addAll(preserved);

        // Try Bukkit YAML save first. If the original file was clean enough
        // for Bukkit to parse, this writes the cleanest output.
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            // Check Bukkit didn't lose the section -- if the key is missing or
            // empty when we know there should be data, fall back to text edit.
            boolean bukkitOk = cfg.contains(key);
            if (bukkitOk) {
                cfg.set(key + ".Drops", newDropLines);
                cfg.save(file);
                reloadMythicMobs();
                return true;
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Bukkit save failed, falling back to text save: " + ex.getMessage());
        }

        // Fallback: surgical text edit of just the target Drops: block
        boolean ok = saveDropsAsText(file, key, newDropLines);
        if (ok) reloadMythicMobs();
        return ok;
    }

    /**
     * Surgical text-based save: finds "key:" then its "Drops:" sub-block and
     * replaces only those lines with newDropLines. Everything else in the
     * file (comments, other droptables, formatting) is preserved exactly.
     *
     * If the file doesn't yet contain key, the section is appended at the end.
     */
    private boolean saveDropsAsText(File file, String key, List<String> newDropLines) {
        List<String> lines;
        try {
            lines = file.exists()
                ? new ArrayList<>(java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8))
                : new ArrayList<>();
        } catch (IOException ex) {
            plugin.getLogger().severe("Read failed in text save: " + ex.getMessage());
            return false;
        }

        // Find the section start
        int sectionLine = -1;
        int sectionIndent = -1;
        for (int i = 0; i < lines.size(); i++) {
            String stripped = stripComment(lines.get(i).stripTrailing()).trim();
            if (stripped.equals(key + ":") || stripped.startsWith(key + ":")) {
                sectionLine = i;
                sectionIndent = leadingSpaces(lines.get(i));
                break;
            }
        }

        // If the section doesn't exist, append it at the end
        if (sectionLine < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
            lines.add(key + ":");
            lines.add("  Drops:");
            for (String dl : newDropLines) lines.add("  - " + dl);
            return writeLines(file, lines);
        }

        // Find the Drops: line within the section, and the block range
        int dropsLine = -1, dropsIndent = -1, dropsEnd = -1;
        for (int i = sectionLine + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String noc = stripComment(raw.stripTrailing());
            if (noc.trim().isEmpty()) continue;
            int ind = leadingSpaces(noc);
            if (ind <= sectionIndent) break; // out of the section
            if (dropsLine < 0) {
                if (noc.trim().equals("Drops:")) {
                    dropsLine = i;
                    dropsIndent = ind;
                }
            } else {
                if (ind <= dropsIndent) { dropsEnd = i; break; }
            }
        }

        if (dropsLine < 0) {
            // Section exists but has no Drops: -- insert one right after section header
            List<String> inserted = new ArrayList<>();
            String pad = " ".repeat(sectionIndent + 2);
            inserted.add(pad + "Drops:");
            for (String dl : newDropLines) inserted.add(pad + "- " + dl);
            lines.addAll(sectionLine + 1, inserted);
            return writeLines(file, lines);
        }

        if (dropsEnd < 0) dropsEnd = lines.size();

        // Remove existing drop entries (lines after Drops: up to dropsEnd that
        // are at indent > dropsIndent and look like list entries OR comments).
        // We preserve comments here too -- if the user has notes in the file
        // we don't want to wipe them.
        List<String> kept = new ArrayList<>();
        for (int i = dropsLine + 1; i < dropsEnd; i++) {
            String raw = lines.get(i);
            String noc = stripComment(raw.stripTrailing());
            // Keep comment-only lines verbatim
            if (noc.trim().isEmpty() && !raw.trim().isEmpty()) {
                kept.add(raw);
            }
            // Drop everything else (the actual list entries)
        }

        // Build replacement block
        String pad = " ".repeat(dropsIndent + 2);
        List<String> replacement = new ArrayList<>();
        for (String dl : newDropLines) replacement.add(pad + "- " + dl);

        // Splice: keep lines before Drops:, keep Drops: header, insert new
        // entries, then keep lines from dropsEnd onward
        List<String> result = new ArrayList<>(lines.subList(0, dropsLine + 1));
        result.addAll(replacement);
        result.addAll(kept); // preserved comment lines
        if (dropsEnd < lines.size()) result.addAll(lines.subList(dropsEnd, lines.size()));

        return writeLines(file, result);
    }

    private boolean writeLines(File file, List<String> lines) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.write(file.toPath(), lines,
                java.nio.charset.StandardCharsets.UTF_8);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Text save failed: " + ex.getMessage());
            return false;
        }
    }

    private void reloadMythicMobs() {
        // Safest cross-version reload is the console command
        Bukkit.getScheduler().runTask(plugin, () ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm reload")
        );
    }

    // ---------- droptable creation ----------

    /**
     * Validates a candidate droptable name. MythicMobs identifiers should
     * be alphanumeric + underscores, no spaces or special chars.
     */
    public boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]+");
    }

    /**
     * Creates a new empty droptable in DropTables/CustomDropTables.yml.
     * If a droptable with that name already exists, returns false without
     * touching anything.
     *
     * The new droptable starts with an empty Drops: list, ready to be edited
     * via openDroptableEditor.
     */
    public boolean createDroptable(String name) {
        if (!isValidName(name)) return false;
        // Don't allow overwrite -- check across all files
        if (findDroptableFile(name) != null) return false;

        File dir = findSubfolder("DropTables", "droptables", "Droptables");
        if (dir == null) {
            // No DropTables folder yet -- create one alongside the Mobs folder
            dir = new File(mythicDataFolder(), "DropTables");
            if (!dir.exists() && !dir.mkdirs()) {
                plugin.getLogger().severe("Couldn't create DropTables folder.");
                return false;
            }
        }

        // Use a shared file so we don't pollute the folder with one file per
        // table. Users who prefer one-file-per-table can move them later.
        File file = new File(dir, "CustomDropTables.yml");
        try {
            YamlConfiguration cfg = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
            cfg.set(name + ".Drops", new ArrayList<String>());
            cfg.save(file);
            reloadMythicMobs();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to create droptable: " + ex.getMessage());
            return false;
        }
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

            // Bukkit YAML first
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            java.util.Set<String> keys = cfg.getKeys(false);
            if (!keys.isEmpty()) {
                out.addAll(keys);
                continue;
            }

            // Fallback: scan file as text for top-level keys (lines that start
            // at column 0, are not comments, and end with ':').
            try {
                for (String raw : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                    String s = stripComment(raw.stripTrailing());
                    if (s.isEmpty()) continue;
                    if (leadingSpaces(s) != 0) continue;
                    String t = s.trim();
                    int colon = t.indexOf(':');
                    if (colon <= 0) continue;
                    String key = t.substring(0, colon).trim();
                    if (!key.isEmpty()) out.add(key);
                }
            } catch (IOException ex) {
                // ignore unreadable files
            }
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
