package dev.server.dropeditor.droptable;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * One drop in a droptable. Either:
 *   VANILLA  -> material is the actual drop
 *   MMOITEM  -> mmoType + mmoId define the drop, material is just the GUI icon
 *
 * Chance is stored 0.0 - 1.0 internally; the user enters/sees it as 1-100 percent.
 *
 * For MMOItems, cachedIcon may hold the actual ItemStack snapshot from when
 * the item was dragged in -- used as a fallback icon if MMOItems' live API
 * lookup fails when re-rendering the GUI after a reload.
 */
public class DropEntry {

    public enum Kind { VANILLA, MMOITEM }

    private final Kind kind;
    private Material material;
    private String mmoType;
    private String mmoId;
    private int minAmount;
    private int maxAmount;
    private double chance;
    private ItemStack cachedIcon; // optional snapshot for MMOItem display

    public DropEntry(Material material, int minAmount, int maxAmount, double chance) {
        this.kind = Kind.VANILLA;
        this.material = material;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.chance = clamp(chance);
    }

    public DropEntry(String mmoType, String mmoId, Material icon, int minAmount, int maxAmount, double chance) {
        this.kind = Kind.MMOITEM;
        this.mmoType = mmoType;
        this.mmoId = mmoId;
        this.material = icon != null ? icon : Material.PAPER;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.chance = clamp(chance);
    }

    public Kind     getKind()       { return kind; }
    public Material getMaterial()   { return material; }
    public String   getMmoType()    { return mmoType; }
    public String   getMmoId()      { return mmoId; }
    public int      getMinAmount()  { return minAmount; }
    public int      getMaxAmount()  { return maxAmount; }
    public double   getChance()     { return chance; }
    public ItemStack getCachedIcon() { return cachedIcon; }

    public void setCachedIcon(ItemStack icon) { this.cachedIcon = icon; }

    public int getChancePercent() { return (int) Math.round(chance * 100.0); }

    public void setMinAmount(int v)        { this.minAmount = Math.max(1, v); }
    public void setMaxAmount(int v)        { this.maxAmount = Math.max(minAmount, v); }
    public void setChance(double v)        { this.chance = clamp(v); }
    public void setChancePercent(int pct)  { this.chance = clamp(pct / 100.0); }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Builds the MythicMobs drop line.
     *  Vanilla: "DIAMOND 1-3 0.25"
     *  MMOItem: "MMOITEM{type=SWORD;id=EXCALIBUR} 1 0.1"
     */
    public String toMythicLine() {
        String amount = (minAmount == maxAmount) ? String.valueOf(minAmount) : minAmount + "-" + maxAmount;
        if (kind == Kind.MMOITEM) {
            return "MMOITEM{type=" + mmoType + ";id=" + mmoId + "} " + amount + " " + chance;
        }
        return material.name() + " " + amount + " " + chance;
    }

    public String displayName() {
        return kind == Kind.MMOITEM ? (mmoType + ":" + mmoId) : material.name();
    }
}
