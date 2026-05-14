package dev.server.dropeditor.command;

import dev.server.dropeditor.MythicDropEditor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DroptableCommand implements CommandExecutor {

    private final MythicDropEditor plugin;

    public DroptableCommand(MythicDropEditor plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!player.hasPermission("mythicdropeditor.use")) {
            player.sendMessage("\u00a7cYou don't have permission.");
            return true;
        }
        plugin.getGuiManager().openMainMenu(player);
        return true;
    }
}
