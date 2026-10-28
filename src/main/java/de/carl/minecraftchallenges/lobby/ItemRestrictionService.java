package de.carl.minecraftchallenges.lobby;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.util.LobbyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * In the lobby (outside /edit) and in challenge worlds before the round runs, players may only hold the
 * items the plugin hands out. Anything else, including /give, creative and picked-up items, is removed
 * on the next tick, because commands like /give fire no inventory event.
 */
public final class ItemRestrictionService implements Listener {
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;

    public ItemRestrictionService(MinecraftChallengesPlugin plugin, LobbyService lobby, GameLobbyRegistry registry) {
        this.lobby = lobby;
        this.registry = registry;
        plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> Bukkit.getOnlinePlayers().forEach(this::sweep), 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isRestricted(player)) return;
        if (isAllowed(player.getWorld(), event.getItem().getItemStack())) return;
        event.setCancelled(true);
        event.getItem().remove();
    }

    private void sweep(Player player) {
        if (!isRestricted(player)) return;
        World world = player.getWorld();
        boolean removed = false;
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isForbidden(world, contents[slot])) {
                inventory.setItem(slot, null);
                removed = true;
            }
        }
        if (isForbidden(world, player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed = true;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) {
            for (int slot = 0; slot < top.getSize(); slot++) {
                if (isForbidden(world, top.getItem(slot))) {
                    top.setItem(slot, null);
                    removed = true;
                }
            }
        }
        if (removed) {
            player.sendActionBar(Component.text("Hier sind keine eigenen Items erlaubt.", NamedTextColor.RED));
        }
    }

    private boolean isRestricted(Player player) {
        World world = player.getWorld();
        if (lobby.isLobbyWorld(world)) return !lobby.isEditing(player);
        GameInstance instance = registry.instance(world);
        return instance != null && instance.state() != ChallengeState.RUNNING;
    }

    private boolean isForbidden(World world, ItemStack item) {
        return item != null && !item.isEmpty() && !isAllowed(world, item);
    }

    private boolean isAllowed(World world, ItemStack item) {
        if (lobby.isLobbyWorld(world)) {
            return lobby.isCompass(item) || lobby.isRegenerationItem(item) || lobby.isJumpAndRunAbortItem(item)
                    || lobby.isQueueItem(item);
        }
        GameInstance instance = registry.instance(world);
        return instance != null && instance.isFixedItem(item);
    }
}
