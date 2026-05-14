package dev.server.dropeditor;

import dev.server.dropeditor.command.DroptableCommand;
import dev.server.dropeditor.droptable.DroptableManager;
import dev.server.dropeditor.gui.GuiListener;
import dev.server.dropeditor.gui.GuiManager;
import dev.server.dropeditor.util.ChatPromptManager;
import org.bukkit.plugin.java.JavaPlugin;

public class MythicDropEditor extends JavaPlugin {

    private DroptableManager droptableManager;
    private GuiManager guiManager;
    private ChatPromptManager chatPromptManager;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("MythicMobs") == null) {
            getLogger().severe("MythicMobs not found. Disabling MythicDropEditor.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        boolean mmoItems = getServer().getPluginManager().getPlugin("MMOItems") != null;
        getLogger().info("MMOItems " + (mmoItems ? "detected -- MMOItem drops enabled." : "not found -- vanilla items only."));

        this.droptableManager   = new DroptableManager(this);
        this.chatPromptManager  = new ChatPromptManager(this);
        this.guiManager         = new GuiManager(this);

        getCommand("droptable").setExecutor(new DroptableCommand(this));
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(chatPromptManager, this);

        getLogger().info("MythicDropEditor enabled.");
    }

    public DroptableManager   getDroptableManager()   { return droptableManager; }
    public GuiManager         getGuiManager()         { return guiManager; }
    public ChatPromptManager  getChatPromptManager()  { return chatPromptManager; }
}
