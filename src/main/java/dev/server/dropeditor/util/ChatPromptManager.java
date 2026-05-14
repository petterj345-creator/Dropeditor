package dev.server.dropeditor.util;

import dev.server.dropeditor.MythicDropEditor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Lets the plugin "ask" the player a question in chat. We close their inventory,
 * tell them to type the answer, and the next chat line goes to the registered
 * callback (which then reopens the GUI).
 *
 * Used for:
 *   - drop chance prompt (1-100)
 *   - amount prompt (N or N-M)
 *   - mob search prompt
 *
 * Why chat instead of an anvil GUI? Anvil GUIs require NMS or a wrapper library
 * and are version-fragile. Chat works on every Paper version.
 */
public class ChatPromptManager implements Listener {

    private final MythicDropEditor plugin;
    private final Map<UUID, Consumer<String>> pending = new HashMap<>();

    public ChatPromptManager(MythicDropEditor plugin) {
        this.plugin = plugin;
    }

    /**
     * Prompts the player. Closes their open inventory, sends them a chat
     * message asking the question, then waits for their next chat line.
     * Their reply is passed (trimmed) to {@code onReply} on the main thread.
     * The player can type "cancel" to abort.
     */
    public void prompt(Player player, String question, Consumer<String> onReply) {
        // Close inventory on the main thread (this method may be called from a click event)
        plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
        player.sendMessage("\u00a76\u00a7l>> \u00a7e" + question);
        player.sendMessage("\u00a77Type your answer in chat. Type \u00a7ccancel\u00a77 to abort.");
        pending.put(player.getUniqueId(), onReply);
    }

    public boolean hasPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void cancel(Player player) {
        pending.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        Consumer<String> cb = pending.remove(p.getUniqueId());
        if (cb == null) return;

        event.setCancelled(true);
        String message = event.getMessage().trim();

        // Run on main thread for inventory operations
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (message.equalsIgnoreCase("cancel")) {
                p.sendMessage("\u00a7cCancelled.");
                return;
            }
            try {
                cb.accept(message);
            } catch (Throwable t) {
                plugin.getLogger().warning("Prompt callback failed: " + t.getMessage());
                p.sendMessage("\u00a7cSomething went wrong. Try again.");
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
