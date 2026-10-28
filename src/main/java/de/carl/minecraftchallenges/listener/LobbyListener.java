package de.carl.minecraftchallenges.listener;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.custom.CustomChallengeMenu;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.menu.ChallengeMenu;
import de.carl.minecraftchallenges.menu.ChallengeMenuHolder;
import de.carl.minecraftchallenges.menu.LobbyMenu;
import de.carl.minecraftchallenges.menu.LobbyMenuHolder;
import de.carl.minecraftchallenges.util.LobbyService;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

public final class LobbyListener implements Listener {
    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;
    private final ChallengeMenu menu;
    private final LobbyMenu lobbyMenu;
    private final CustomChallengeMenu customMenu;

    public LobbyListener(MinecraftChallengesPlugin plugin, LobbyService lobby, GameLobbyRegistry registry,
                         ChallengeMenu menu, LobbyMenu lobbyMenu, CustomChallengeMenu customMenu) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.registry = registry;
        this.menu = menu;
        this.lobbyMenu = lobbyMenu;
        this.customMenu = customMenu;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lobby.playerQuit(event.getPlayer());
        plugin.getServer().getScheduler().runTask(plugin, () -> lobby.sendToLobby(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getTo().getY() >= 20.0) return;
        if (!lobby.isLobbyWorld(event.getPlayer().getWorld()) || lobby.isEditing(event.getPlayer())) return;
        lobby.sendToLobby(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!lobby.isLobbyWorld(event.getPlayer().getWorld()) || lobby.isEditing(event.getPlayer())) return;

        // In der geschützten Lobby ist jede Block-/Item-Interaktion verboten.
        // Die einzige Ausnahme ist der Rechtsklick mit dem Challenge-Kompass.
        event.setCancelled(true);
        if (!lobby.isCompass(event.getItem())) return;
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> menu.open(event.getPlayer());
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (lobby.isLobbyWorld(event.getPlayer().getWorld()) && !lobby.isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ChallengeMenuHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            GameType game = ChallengeMenu.gameAt(event.getRawSlot());
            if (game == GameType.CUSTOM) {
                customMenu.openList(player);
            } else if (game != null) {
                lobbyMenu.open(player, game);
            } else if (event.getRawSlot() == ChallengeMenu.JUMP_AND_RUN_SLOT
                    && menu.isJumpAndRunAvailable()) {
                menu.selectJumpAndRun(player);
            } else if (event.getRawSlot() == ChallengeMenu.SMP_SLOT) {
                menu.selectSmp(player);
            }
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof LobbyMenuHolder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getRawSlot() == LobbyMenu.BACK_SLOT) {
                menu.open(player);
                return;
            }
            GameInstance target = lobbyMenu.lobbyAt(holder, event.getRawSlot());
            if (target == null) return;
            player.closeInventory();
            registry.join(player, target);
            return;
        }
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player
                && lobby.isLobbyWorld(player.getWorld())
                && !lobby.isEditing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ChallengeMenuHolder
                || event.getView().getTopInventory().getHolder() instanceof LobbyMenuHolder) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player
                && lobby.isLobbyWorld(player.getWorld()) && !lobby.isEditing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (lobby.isLobbyWorld(event.getPlayer().getWorld()) && !lobby.isEditing(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (lobby.isCompass(event.getItemDrop().getItemStack())
                || lobby.isJumpAndRunAbortItem(event.getItemDrop().getItemStack())
                || lobby.isRegenerationItem(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (lobby.isLobbyWorld(event.getPlayer().getWorld()) && !lobby.isEditing(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (lobby.isCompass(event.getMainHandItem()) || lobby.isCompass(event.getOffHandItem())
                || lobby.isJumpAndRunAbortItem(event.getMainHandItem())
                || lobby.isJumpAndRunAbortItem(event.getOffHandItem())
                || lobby.isRegenerationItem(event.getMainHandItem())
                || lobby.isRegenerationItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player
                && lobby.isLobbyWorld(player.getWorld())
                && (player.getGameMode() == GameMode.ADVENTURE || !lobby.isEditing(player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (lobby.isLobbyWorld(event.getPlayer().getWorld()) && !lobby.isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (lobby.isLobbyWorld(event.getPlayer().getWorld()) && !lobby.isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player
                && lobby.isLobbyWorld(player.getWorld()) && !lobby.isEditing(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player player = attackingPlayer(event);
        if (player != null && lobby.isLobbyWorld(player.getWorld())
                && !lobby.isEditing(player)) {
            event.setCancelled(true);
        }
    }

    private Player attackingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player
                && lobby.isLobbyWorld(player.getWorld()) && !lobby.isEditing(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler
    public void onWeatherChange(WeatherChangeEvent event) {
        if (lobby.isLobbyWorld(event.getWorld()) && event.toWeatherState()) event.setCancelled(true);
    }

    @EventHandler
    public void onThunderChange(ThunderChangeEvent event) {
        if (lobby.isLobbyWorld(event.getWorld()) && event.toThunderState()) event.setCancelled(true);
    }
}
