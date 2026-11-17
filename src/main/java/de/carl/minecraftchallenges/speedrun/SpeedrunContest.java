package de.carl.minecraftchallenges.speedrun;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeSessionService;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import de.carl.minecraftchallenges.util.TimeFormatter;
import de.carl.minecraftchallenges.util.WorldLoader;
import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public final class SpeedrunContest implements Listener {
    private static final int CAPACITY = 6;
    private static final int START_SLOT = 11;
    private static final int RECORDS_SLOT = 15;
    private static final int BACK_SLOT = 45;
    private static final int REFRESH_SLOT = 53;
    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;
    private final SpeedrunRecords records;
    private final List<SpeedrunRun> runs;
    private final Map<UUID, Object> pendingPortals = new HashMap<>();

    public SpeedrunContest(MinecraftChallengesPlugin plugin, LobbyService lobby, GameLobbyRegistry registry,
                           ChallengeSessionService sessions) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.registry = registry;
        records = new SpeedrunRecords(plugin.getDataFolder().toPath().resolve("speedrun-records.yml"), plugin.getLogger());
        runs = IntStream.rangeClosed(1, CAPACITY)
                .mapToObj(id -> new SpeedrunRun(plugin, lobby, sessions, records, id)).toList();
        runs.forEach(registry::register);
        Bukkit.getScheduler().runTaskTimer(plugin, records::save, 1200L, 1200L);
    }

    public void saveRecords() { records.save(); }

    private SpeedrunRun ownRun(Player player) {
        return runs.stream().filter(run -> run.isRoundActive() && run.isParticipant(player)).findFirst().orElse(null);
    }

    public String occupancy() {
        return runs.stream().filter(run -> run.state() != ChallengeState.IDLE).count() + "/" + CAPACITY;
    }

    public Map<UUID, Component> highscoreLabels(Set<UUID> viewers) {
        var ranking = records.ranked();
        Map<UUID, Component> labels = new HashMap<>();
        for (int i = 0; i < ranking.size(); i++) {
            var result = ranking.get(i);
            if (!viewers.contains(result.player())) continue;
            labels.put(result.player(), Component.text(
                    "Highscore: " + result.time() + " (Platz " + (i + 1) + ")", NamedTextColor.GREEN));
        }
        return labels;
    }

    public void quickStart(Player player) {
        if (!lobby.isLobbyWorld(player.getWorld()) || lobby.isEditing(player) || player.isDead()) {
            Messages.send(player, Messages.Type.INFO, "Starte deinen Speedrun aus der Lobby.");
            return;
        }
        SpeedrunRun own = ownRun(player);
        if (own != null) {
            own.select(player);
            return;
        }
        runs.stream().filter(run -> run.state() == ChallengeState.IDLE).findFirst()
                .ifPresentOrElse(run -> run.start(player), () -> Messages.send(player, Messages.Type.INFO,
                        "Alle sechs Speedrun-Plätze sind belegt. Versuche es gleich noch einmal."));
    }

    public void select(Player player, boolean quick, Consumer<Player> back) {
        // A navigator click is an InventoryClickEvent. Open or close the view on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (quick) quickStart(player);
            else open(player, back);
        });
    }

    private void open(Player player, Consumer<Player> back) {
        SpeedrunRun own = ownRun(player);
        var menu = new Menu(back, own);
        Inventory inventory = Bukkit.createInventory(menu, 54, Component.text("Solo-Speedrun", NamedTextColor.DARK_GRAY));
        menu.inventory = inventory;
        MenuItems.drawBorder(inventory);
        inventory.setItem(START_SLOT, MenuItems.item(Material.CLOCK,
                Component.text(own == null ? "Neuen Speedrun starten" : "Meinen Speedrun fortsetzen", NamedTextColor.GREEN),
                List.of(line("Besiege den Enderdrachen in einer zufälligen Welt."),
                        line("Zeitlimit 3 Stunden · " + occupancy() + " belegt"))));

        var ranking = records.ranked();
        var rankingLines = new java.util.ArrayList<Component>();
        if (ranking.isEmpty()) rankingLines.add(line("Noch keine gewerteten Läufe."));
        int personalPlace = 0;
        for (int i = 0; i < ranking.size(); i++) {
            var result = ranking.get(i);
            if (i < 5) rankingLines.add(line("#" + (i + 1) + "  " + result.name() + "  ·  " + result.time()));
            if (result.player().equals(player.getUniqueId())) personalPlace = i + 1;
        }
        rankingLines.add(Component.empty());
        rankingLines.add(Component.text(personalPlace == 0 ? "Du hast noch keine Bestzeit."
                : "Deine Bestzeit  " + ranking.get(personalPlace - 1).time() + "  ·  Platz #" + personalPlace,
                NamedTextColor.AQUA));
        inventory.setItem(RECORDS_SLOT, MenuItems.item(Material.WRITTEN_BOOK,
                Component.text("Bestenliste · Top 5", NamedTextColor.GOLD), rankingLines));

        int slot = 19;
        for (SpeedrunRun run : runs) {
            if (!run.isRoundActive()) continue;
            boolean running = run.state() == ChallengeState.RUNNING;
            boolean mine = run == own;
            inventory.setItem(slot, MenuItems.item(running ? Material.PLAYER_HEAD : Material.COMPASS,
                    Component.text(run.runnerName(), NamedTextColor.YELLOW),
                    List.of(line(running ? "Laufzeit  " + TimeFormatter.gameTime((int) (run.elapsedMillis() / 1000))
                                    : run.state() == ChallengeState.COUNTDOWN ? "Countdown läuft …"
                                    : run.readyToStart() ? "Wartet auf den Start" : "Welt wird vorbereitet …"),
                            line(mine ? "Klicken, um zurückzukehren" : running ? "Klicken, um zuzuschauen" : "Noch nicht gestartet"))));
            if (running || mine) menu.entries.put(slot, new Entry(run, run.overworld()));
            slot++;
        }
        if (slot == 19) inventory.setItem(22, MenuItems.item(Material.GRAY_DYE,
                Component.text("Gerade läuft kein Speedrun", NamedTextColor.GRAY), List.of()));
        inventory.setItem(BACK_SLOT, MenuItems.item(Material.ARROW, Component.text("Zurück"), List.of()));
        inventory.setItem(REFRESH_SLOT, MenuItems.item(Material.SUNFLOWER, Component.text("Aktualisieren"), List.of()));
        player.openInventory(inventory);
    }

    private static Component line(String text) { return Component.text(text, NamedTextColor.GRAY); }

    private record Entry(SpeedrunRun run, World world) { }

    private static final class Menu implements InventoryHolder {
        private final Consumer<Player> back;
        private final SpeedrunRun ownRun;
        private final Map<Integer, Entry> entries = new HashMap<>();
        private Inventory inventory;

        private Menu(Consumer<Player> back, SpeedrunRun ownRun) {
            this.back = back;
            this.ownRun = ownRun;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            if (event.getWhoClicked() instanceof Player player) {
                if (waiting(player)) event.setCancelled(true);
                activity(player);
            }
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !event.getClick().isLeftClick()
                && !event.getClick().isRightClick()) return;
        int slot = event.getRawSlot();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) return;
            switch (slot) {
                case BACK_SLOT -> menu.back.accept(player);
                case REFRESH_SLOT -> open(player, menu.back);
                case START_SLOT -> {
                    if (menu.ownRun != null && menu.ownRun != ownRun(player)) open(player, menu.back);
                    else quickStart(player);
                }
                default -> {
                    Entry entry = menu.entries.get(slot);
                    if (entry == null) return;
                    SpeedrunRun run = entry.run();
                    if (!run.isRoundActive() || entry.world() != run.overworld()) {
                        open(player, menu.back);
                    } else if (run.isParticipant(player)) {
                        quickStart(player);
                    } else if (ownRun(player) != null) {
                        Messages.send(player, Messages.Type.INFO, "Beende deinen eigenen Speedrun, bevor du zuschaust.");
                    } else {
                        run.spectate(player);
                    }
                }
            }
        });
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu
                || event.getWhoClicked() instanceof Player player && waiting(player)) event.setCancelled(true);
    }

    private SpeedrunRun runAt(World world) {
        return registry.instance(world) instanceof SpeedrunRun run ? run : null;
    }

    private void activity(Player player) {
        SpeedrunRun run = runAt(player.getWorld());
        if (run != null) run.activity(player);
    }

    @EventHandler public void onInput(PlayerInputEvent event) { activity(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        SpeedrunRun run = runAt(player.getWorld());
        if (run == null) return;
        run.activity(player);
        if (!run.waiting(player)) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND && event.getAction().isRightClick()) {
            run.useWaitingItem(player, event.getItem());
        }
    }

    private boolean waiting(Player player) {
        SpeedrunRun run = runAt(player.getWorld());
        return run != null && run.waiting(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (waiting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (waiting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (waiting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        onInteractEntity(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (waiting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && waiting(player)
                || event instanceof EntityDamageByEntityEvent hit
                && hit.getDamager() instanceof Player attacker && waiting(attacker)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && waiting(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && waiting(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && waiting(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (waiting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (waiting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) { activity(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (runAt(event.getPlayer().getWorld()) == null) return;
        if (event.hasChangedOrientation()) {
            activity(event.getPlayer());
        } else if (event.hasChangedPosition()) {
            var input = event.getPlayer().getCurrentInput();
            if (input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump()) {
                activity(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getPlayer().hasPermission(RankService.ADMIN)) return;
        SpeedrunRun run = runAt(event.getPlayer().getWorld());
        if (run == null) return;
        GameMode allowed;
        if (run.waiting(event.getPlayer())) allowed = GameMode.ADVENTURE;
        else if (run.state() == ChallengeState.RUNNING) {
            allowed = run.isParticipant(event.getPlayer()) ? GameMode.SURVIVAL : GameMode.SPECTATOR;
        } else return;
        if (event.getNewGameMode() != allowed) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        // Admins may use /tp freely; the plugin's own teleports stay guarded.
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND
                && event.getPlayer().hasPermission(RankService.ADMIN)) return;
        SpeedrunRun source = runAt(event.getFrom().getWorld());
        SpeedrunRun destination = runAt(event.getTo().getWorld());
        if (source == destination) {
            if (source != null && source.waiting(event.getPlayer())
                    && !event.getTo().getWorld().getWorldBorder().isInside(event.getTo())) event.setCancelled(true);
            return;
        }
        if (destination != null && !destination.mayEnterWorld(event.getPlayer())
                || source != null && !lobby.isLobbyWorld(event.getTo().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeparture(PlayerTeleportEvent event) {
        SpeedrunRun source = runAt(event.getFrom().getWorld());
        if (source != null && event.getTo() != null && !source.owns(event.getTo().getWorld())) {
            source.rememberExit(event.getPlayer(), event.getFrom());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        SpeedrunRun run = runAt(event.getFrom());
        if (run != null && !run.owns(event.getPlayer().getWorld())) run.leave(event.getPlayer(), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        SpeedrunRun run = runAt(player.getWorld());
        if (run != null) run.leave(player, player.getLocation());
        pendingPortals.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        SpeedrunRun run = runAt(player.getWorld());
        if (run == null) return;
        if (run.playing(player)) {
            event.setRespawnLocation(run.respawn(event.getRespawnLocation()));
        } else {
            event.setRespawnLocation(lobby.getLobby());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && lobby.isLobbyWorld(player.getWorld())) lobby.prepareLobbyPlayer(player);
            });
        }
    }

    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;
        SpeedrunRun run = runAt(dragon.getWorld());
        if (run != null) run.dragonDefeated(dragon.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNetherPortal(EntityPortalReadyEvent event) {
        if (event.getPortalType() != PortalType.NETHER) return;
        World source = event.getEntity().getWorld();
        SpeedrunRun run = runAt(source);
        if (run == null) return;
        if (run.state() != ChallengeState.RUNNING || event.getEntity() instanceof Player player && !run.playing(player)) {
            event.setCancelled(true);
            return;
        }
        World.Environment environment = source.getEnvironment() == World.Environment.NETHER
                ? World.Environment.NORMAL : World.Environment.NETHER;
        World destination = run.readyDimension(environment);
        if (destination != null) {
            event.setTargetWorld(destination);
            return;
        }
        event.setCancelled(true);
        if (event.getEntity() instanceof Player player) {
            preparePortal(run, player, player.getLocation(), environment, target -> player.setPortalCooldown(0));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        SpeedrunRun run = runAt(event.getFrom().getWorld());
        if (run == null) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (!run.playing(event.getPlayer()) || event.getTo() == null || !run.owns(event.getTo().getWorld())) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!run.playing(player)) return;
        if (event.getFrom().getWorld().getEnvironment() == World.Environment.THE_END) {
            Location destination = run.respawn(player.getRespawnLocation());
            Location source = event.getFrom().clone();
            World owner = run.overworld();
            WorldLoader.loadArea(destination.getWorld(), destination, 0)
                    .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (stillAtPortal(run, player, source, owner)) player.teleport(destination);
                    }));
        } else {
            preparePortal(run, player, event.getFrom().clone(), World.Environment.THE_END, target -> {
                SpeedrunRun.buildEndPlatform(target);
                player.teleport(SpeedrunRun.endArrival(target));
            });
        }
    }

    private void preparePortal(SpeedrunRun run, Player player, Location source,
                               World.Environment environment, Consumer<World> transfer) {
        UUID uuid = player.getUniqueId();
        Object transferToken = new Object();
        if (pendingPortals.putIfAbsent(uuid, transferToken) != null) return;
        Messages.send(player, Messages.Type.INFO, "Die Dimension wird vorbereitet …");
        World owner = run.overworld();
        run.dimension(environment).whenComplete((target, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!pendingPortals.remove(uuid, transferToken)) return;
                if (!stillAtPortal(run, player, source, owner)) return;
                if (error != null || target == null) {
                    Messages.send(player, Messages.Type.ERROR, "Die Dimension konnte nicht vorbereitet werden.");
                } else {
                    transfer.accept(target);
                }
            });
        });
    }

    private static boolean stillAtPortal(SpeedrunRun run, Player player, Location source, World owner) {
        return player.isOnline() && run.isCurrent(owner) && run.playing(player)
                && player.getWorld() == source.getWorld() && player.getLocation().distanceSquared(source) <= 16;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        SpeedrunRun source = runAt(event.getFrom().getWorld());
        SpeedrunRun destination = event.getTo() == null ? null : runAt(event.getTo().getWorld());
        if (source != destination) event.setCancelled(true);
    }
}
