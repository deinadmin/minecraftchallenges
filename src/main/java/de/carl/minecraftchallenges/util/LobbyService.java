package de.carl.minecraftchallenges.util;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class LobbyService {
    public static final int COMPASS_SLOT = 4;
    private final MinecraftChallengesPlugin plugin;
    private final NamespacedKey compassKey;
    private final NamespacedKey regenerationKey;
    private final NamespacedKey jumpAndRunAbortKey;
    private final NamespacedKey queueKey;
    private final Set<UUID> editors = new HashSet<>();
    private final Set<UUID> activeJumpAndRuns = new HashSet<>();

    public LobbyService(MinecraftChallengesPlugin plugin) {
        this.plugin = plugin;
        this.compassKey = new NamespacedKey(plugin, "challenge_compass");
        this.regenerationKey = new NamespacedKey(plugin, "world_regeneration");
        this.jumpAndRunAbortKey = new NamespacedKey(plugin, "jump_n_run_abort");
        this.queueKey = new NamespacedKey(plugin, "custom_queue_leave");
        ensureLobbyWorld();
    }

    private void ensureLobbyWorld() {
        World lobbyWorld = Bukkit.getWorld("lobby");
        if (lobbyWorld == null) lobbyWorld = new WorldCreator("lobby").createWorld();
        if (lobbyWorld == null) {
            throw new IllegalStateException("Die Welt 'lobby' konnte nicht geladen werden.");
        }
        enforceLobbyEnvironment(lobbyWorld);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = Bukkit.getWorld("lobby");
            if (world != null) enforceLobbyEnvironment(world);
        }, 100L, 100L);

        String configuredWorld = plugin.getConfig().getString("lobby.world", "lobby");
        if (!"lobby".equals(configuredWorld)) {
            plugin.getConfig().set("lobby.configured", false);
        }
        plugin.getConfig().set("lobby.world", "lobby");
        plugin.saveConfig();
    }

    public void enforceLobbyEnvironment(World world) {
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setTime(6000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(0);
        world.setThunderDuration(0);
    }

    public Location getLobby() {
        String worldName = "lobby";
        World world = Bukkit.getWorld(worldName);
        if (world == null) world = Bukkit.getWorlds().getFirst();
        if (!plugin.getConfig().getBoolean("lobby.configured", false)) {
            return world.getSpawnLocation().add(0.5, 0, 0.5);
        }
        return new Location(world,
                plugin.getConfig().getDouble("lobby.x", world.getSpawnLocation().getX()),
                plugin.getConfig().getDouble("lobby.y", world.getSpawnLocation().getY()),
                plugin.getConfig().getDouble("lobby.z", world.getSpawnLocation().getZ()),
                (float) plugin.getConfig().getDouble("lobby.yaw", 0),
                (float) plugin.getConfig().getDouble("lobby.pitch", 0));
    }

    public boolean setLobby(Location location) {
        if (!"lobby".equals(location.getWorld().getName())) return false;
        plugin.getConfig().set("lobby.configured", true);
        plugin.getConfig().set("lobby.world", "lobby");
        plugin.getConfig().set("lobby.x", location.getX());
        plugin.getConfig().set("lobby.y", location.getY());
        plugin.getConfig().set("lobby.z", location.getZ());
        plugin.getConfig().set("lobby.yaw", location.getYaw());
        plugin.getConfig().set("lobby.pitch", location.getPitch());
        plugin.saveConfig();
        return true;
    }

    public void sendToLobby(Player player) {
        editors.remove(player.getUniqueId());
        player.teleport(getLobby());
        prepareLobbyPlayer(player);
    }

    public void sendToRegenerationLobby(Player player) {
        editors.remove(player.getUniqueId());
        player.teleport(getLobby());
        player.setGameMode(GameMode.ADVENTURE);
        resetVitals(player);
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        clearExperience(player);
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        player.getInventory().clear();
        player.getInventory().setItem(COMPASS_SLOT, createRegenerationItem());
        player.getInventory().setHeldItemSlot(COMPASS_SLOT);
        updateFlightPermission(player);
    }

    public void prepareLobbyPlayer(Player player) {
        editors.remove(player.getUniqueId());
        activeJumpAndRuns.remove(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        resetVitals(player);
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        clearExperience(player);
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        player.getInventory().clear();
        player.getInventory().setItem(COMPASS_SLOT, createCompass());
        player.getInventory().setHeldItemSlot(COMPASS_SLOT);
        updateFlightPermission(player);
    }

    private void resetVitals(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) maxHealth.setBaseValue(maxHealth.getDefaultValue());
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        if (!player.isDead()) player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
    }

    public void updateFlightPermission(Player player) {
        if (!isLobbyWorld(player.getWorld()) || isEditing(player)) return;
        boolean mayFly = player.hasPermission("minecraftchallenges.rank.admin")
                || player.hasPermission("minecraftchallenges.rank.mod");
        mayFly = mayFly && !activeJumpAndRuns.contains(player.getUniqueId());
        if (!mayFly && player.isFlying()) player.setFlying(false);
        player.setAllowFlight(mayFly);
    }

    public ItemStack createCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        meta.displayName(Component.text("Navigieren", NamedTextColor.GOLD));
        meta.lore(java.util.List.of(Component.text("Rechtsklick zum Öffnen", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        return compass;
    }

    public boolean isCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    public boolean isRegenerationItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .has(regenerationKey, PersistentDataType.BYTE);
    }

    public void startJumpAndRun(Player player) {
        activeJumpAndRuns.add(player.getUniqueId());
        if (player.isFlying()) player.setFlying(false);
        player.setAllowFlight(false);
        player.getInventory().setItem(COMPASS_SLOT, createJumpAndRunAbortItem());
        player.getInventory().setHeldItemSlot(COMPASS_SLOT);
    }

    public void restoreNavigationItem(Player player) {
        activeJumpAndRuns.remove(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> restoreNavigationItemNow(player));
    }

    private void restoreNavigationItemNow(Player player) {
        if (!player.isOnline()) return;
        if (!isLobbyWorld(player.getWorld()) || isEditing(player)) return;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isCompass(item) || isJumpAndRunAbortItem(item)) {
                player.getInventory().setItem(slot, null);
            }
        }
        player.getInventory().setItem(COMPASS_SLOT, createCompass());
        updateFlightPermission(player);
    }

    public boolean isJumpAndRunAbortItem(ItemStack item) {
        return item != null && item.getType() == Material.BARRIER && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .has(jumpAndRunAbortKey, PersistentDataType.BYTE);
    }

    public boolean isQueueItem(ItemStack item) {
        return item != null && item.getType() == Material.BARRIER && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(queueKey, PersistentDataType.BYTE);
    }

    public ItemStack createQueueItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Warteschlange verlassen", NamedTextColor.RED));
        meta.lore(java.util.List.of(Component.text("Rechtsklick zum Verlassen", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(queueKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isInJumpAndRun(Player player) {
        return activeJumpAndRuns.contains(player.getUniqueId());
    }

    public boolean isEditing(Player player) {
        return editors.contains(player.getUniqueId());
    }

    public void startEditing(Player player) {
        editors.add(player.getUniqueId());
        player.closeInventory();
        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);
    }

    public void finishEditing(Player player) {
        if (!editors.remove(player.getUniqueId())) return;
        World world = Bukkit.getWorld("lobby");
        if (world != null) world.save();
        prepareLobbyPlayer(player);
    }

    public void cancelEditing(Player player) {
        if (!editors.remove(player.getUniqueId())) return;
        World world = Bukkit.getWorld("lobby");
        if (world != null) world.save();
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
    }

    public void playerQuit(Player player) {
        editors.remove(player.getUniqueId());
        activeJumpAndRuns.remove(player.getUniqueId());
    }

    public void shutdownEditing() {
        World world = Bukkit.getWorld("lobby");
        if (!editors.isEmpty() && world != null) world.save();
        editors.clear();
    }

    private void clearExperience(Player player) {
        player.setExp(0.0f);
        player.setLevel(0);
        player.setTotalExperience(0);
    }

    private ItemStack createRegenerationItem() {
        ItemStack item = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Neue Welt wird generiert...", NamedTextColor.YELLOW));
        meta.lore(java.util.List.of(Component.text("Bitte einen Moment warten", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(regenerationKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createJumpAndRunAbortItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Jump'n'Run abbrechen", NamedTextColor.RED));
        meta.lore(java.util.List.of(Component.text("Rechtsklick zum Abbrechen", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(jumpAndRunAbortKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isLobbyWorld(World world) {
        return world.equals(getLobby().getWorld());
    }
}
