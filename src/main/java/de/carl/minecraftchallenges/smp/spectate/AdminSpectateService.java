package de.carl.minecraftchallenges.smp.spectate;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.smp.SmpPlayerState;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.util.Messages;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Admins watch SMP players invisibly. Their survival self stays behind as a player-shaped double that looks AFK,
 * while they fly around in creative: hidden from everyone but other admins, without sounds, particles or traces.
 * The barrier "Zuschauen verlassen" in the last hotbar slot brings them back to the double in survival.
 */
public final class AdminSpectateService implements Listener {
    public static final int LEAVE_SLOT = 8;
    private static final EquipmentSlot[] BODY_SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final long HURT_COOLDOWN_MILLIS = 500;
    /** Orbs follow players within 8 blocks; hiding them from further away leaves room for a fast flyer. */
    private static final double ORB_HIDE_RANGE = 24.0;

    private final MinecraftChallengesPlugin plugin;
    private final SmpService smp;
    private final NamespacedKey leaveKey;
    private final NamespacedKey bodyKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> leaving = new HashSet<>();
    private final Map<Chunk, Ticket> tickets = new HashMap<>();
    private Consumer<Player> visibility = player -> { };
    private final MobAwareness mobs;
    private final com.destroystokyo.paper.entity.ai.GoalKey<org.bukkit.entity.Mob> lookAtDoubleKey;
    private boolean mobErrorLogged;
    private int ticks;

    private record Ticket(int users, boolean ours) { }

    /** Everything needed to put the admin back exactly as they were. */
    private static final class Session {
        final SmpPlayerState state;
        final Location origin;
        final boolean invulnerable;
        final boolean silent;
        final boolean collidable;
        final boolean affectsSpawning;
        Mannequin body;
        Location lastBodyLocation;
        long lastHurt;
        Chunk ticketChunk;
        /** XP orbs hidden from the spectator's own client, which would otherwise pull them in on its own. */
        final Set<UUID> hiddenOrbs = new HashSet<>();

        Session(Player player, SmpPlayerState state) {
            this.state = state;
            this.origin = player.getLocation();
            this.invulnerable = player.isInvulnerable();
            this.silent = player.isSilent();
            this.collidable = player.isCollidable();
            this.affectsSpawning = player.getAffectsSpawning();
        }
    }

    public AdminSpectateService(MinecraftChallengesPlugin plugin, SmpService smp) {
        this.plugin = plugin;
        this.smp = smp;
        this.leaveKey = new NamespacedKey(plugin, "spectate_leave");
        this.bodyKey = new NamespacedKey(plugin, "spectate_body");
        smp.holdStates(player -> {
            Session session = sessions.get(player.getUniqueId());
            return session == null ? null : session.state;
        }, player -> stop(player, false));
        MobAwareness awareness;
        try {
            awareness = new MobAwareness();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Mobs können versteckte Admins weiterhin bemerken (Paper-Interna haben sich geändert).", exception);
            awareness = null;
        }
        this.mobs = awareness;
        this.lookAtDoubleKey = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                new NamespacedKey(plugin, "look_at_spectator_double"));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    // ---------------------------------------------------------------- mobs must not notice the spectator

    private void mobs(String action, MobTask task) {
        if (mobs == null) return;
        try {
            task.run(mobs);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!mobErrorLogged) {
                mobErrorLogged = true;
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Mob-Wahrnehmung: " + action, exception);
            }
        }
    }

    private interface MobTask {
        void run(MobAwareness awareness) throws ReflectiveOperationException;
    }

    private void hideFromMobs(Player admin) {
        mobs("verstecken", awareness -> {
            boolean first = !awareness.active();
            awareness.hide(admin);
            if (first) {
                for (World world : List.of(smp.overworld(), smp.nether(), smp.end())) {
                    for (org.bukkit.entity.Mob mob : world.getEntitiesByClass(org.bukkit.entity.Mob.class)) awareness.patch(mob);
                }
            }
            awareness.forget(admin, admin.getNearbyEntities(32, 32, 32));
        });
    }

    /** Mobs near the double learn to look at it like at an AFK player; the goal idles once no double is left. */
    private void teachLookingAtDouble(Session session) {
        Mannequin body = session.body;
        if (body == null || !body.isValid()) return;
        com.destroystokyo.paper.entity.ai.MobGoals goals = plugin.getServer().getMobGoals();
        double reach = LookAtDoubleGoal.DISTANCE + 8;
        for (Entity nearby : body.getNearbyEntities(reach, reach, reach)) {
            if (!(nearby instanceof org.bukkit.entity.Mob mob) || goals.hasGoal(mob, lookAtDoubleKey)) continue;
            mobs("Figur anschauen", awareness -> {
                Integer priority = awareness.playerLookPriority(mob);
                if (priority == null) return;
                goals.addGoal(mob, priority, new LookAtDoubleGoal(lookAtDoubleKey, mob, this::nearestDouble,
                        player -> sessions.containsKey(player.getUniqueId())));
            });
        }
    }

    private Mannequin nearestDouble(org.bukkit.entity.Mob mob) {
        Mannequin nearest = null;
        double best = LookAtDoubleGoal.DISTANCE * LookAtDoubleGoal.DISTANCE;
        Location eyes = mob.getEyeLocation();
        for (Session session : sessions.values()) {
            Mannequin body = session.body;
            if (body == null || !body.isValid() || !body.getWorld().equals(eyes.getWorld())) continue;
            double distance = body.getEyeLocation().distanceSquared(eyes);
            if (distance <= best) {
                best = distance;
                nearest = body;
            }
        }
        return nearest;
    }

    /** New and freshly loaded mobs get the filter too, once spectating has been used. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobAdded(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent event) {
        if (mobs == null || !mobs.active() || !(event.getEntity() instanceof org.bukkit.entity.Mob mob)
                || !smp.isSmpWorld(event.getWorld())) {
            return;
        }
        mobs("neuer Mob", awareness -> awareness.patch(mob));
    }

    /** Lets the lobby isolation decide who sees whom; called after spectating starts or ends. */
    public void visibility(Consumer<Player> refresh) {
        this.visibility = refresh;
    }

    public boolean isSpectating(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** Spectating admins are hidden from every player who is not an admin. */
    public boolean conceals(Player viewer, Player target) {
        return sessions.containsKey(target.getUniqueId()) && !viewer.hasPermission(RankService.ADMIN);
    }

    // ---------------------------------------------------------------- start and stop

    /** Starts spectating where the admin stands; nobody notices, the double takes over in place. */
    public void spectateHere(Player admin) {
        spectate(admin, null);
    }

    /** With a null target the admin stays where they are. */
    public void spectate(Player admin, Player target) {
        if (isSpectating(admin)) {
            if (target == null) return;
            admin.teleport(target.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            admin.sendActionBar(Messages.smp(Messages.Type.INFO, "Du schaust jetzt " + target.getName() + " zu."));
            return;
        }
        String problem = startProblem(admin);
        if (problem != null) {
            Messages.send(admin, Messages.Type.ERROR, problem);
            return;
        }
        smp.cancelTeleportWarmup(admin, null);
        admin.closeInventory();
        Session session = new Session(admin, SmpPlayerState.capture(admin));
        session.body = spawnBody(admin, session.origin);
        session.lastBodyLocation = session.origin.clone();
        holdChunk(session, session.origin.getChunk());
        sessions.put(admin.getUniqueId(), session);
        // Hiding happens in the same tick as the double appears, so nobody sees a gap or a second body.
        visibility.accept(admin);
        hideFromMobs(admin);
        hideOrbsAround(admin, session);

        PlayerInventory inventory = admin.getInventory();
        inventory.clear();
        admin.getActivePotionEffects().forEach(effect -> admin.removePotionEffect(effect.getType()));
        admin.setFireTicks(0);
        admin.setGameMode(GameMode.CREATIVE);
        inventory.setItem(LEAVE_SLOT, leaveItem());
        admin.setInvulnerable(true);
        admin.setSilent(true);
        admin.setCollidable(false);
        admin.setAffectsSpawning(false);
        smp.save(admin);
        if (target == null) {
            Messages.send(admin, Messages.Type.SUCCESS, "Du bist jetzt unsichtbar im Zuschauermodus. Deine Figur "
                    + "steht AFK an deiner Position; mit der Barriere oder /spectate kehrst du zurück.");
            return;
        }
        admin.teleport(target.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        hideOrbsAround(admin, session);
        Messages.send(admin, Messages.Type.SUCCESS, "Du schaust " + target.getName()
                + " unsichtbar zu. Deine Figur steht AFK an deiner Position; mit der Barriere kehrst du zurück.");
    }

    private String startProblem(Player admin) {
        if (!smp.isActive(admin)) return "Betritt zuerst das SMP.";
        if (smp.isEditing(admin)) return "Beende zuerst den Bearbeitungsmodus mit /edit.";
        if (admin.isDead()) return "Du kannst gerade nicht zuschauen.";
        if (admin.isInsideVehicle()) return "Steig zuerst ab, sonst bleibt deine Figur nicht stehen.";
        if (admin.isSleeping() || admin.isGliding() || admin.isSwimming() || admin.isRiptiding()) {
            return "Stell dich zuerst normal hin, damit deine Figur unauffällig stehen bleibt.";
        }
        if (admin.getGameMode() != GameMode.SURVIVAL && admin.getGameMode() != GameMode.ADVENTURE) {
            return "Zuschauen geht nur aus dem Survival-Modus.";
        }
        return null;
    }

    /** Puts the admin back into their survival self; {@code returnToBody} is false while they leave the SMP. */
    public void stop(Player admin, boolean returnToBody) {
        Session session = sessions.get(admin.getUniqueId());
        if (session == null) return;
        leaving.remove(admin.getUniqueId());
        admin.closeInventory();
        if (returnToBody) {
            admin.setVelocity(new Vector());
            admin.teleport(bodyLocation(session), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        session.state.apply(admin);
        admin.setInvulnerable(session.invulnerable);
        admin.setSilent(session.silent);
        admin.setCollidable(session.collidable);
        admin.setAffectsSpawning(session.affectsSpawning);
        showOrbs(admin, session);
        removeBody(session);
        releaseChunk(session);
        sessions.remove(admin.getUniqueId());
        mobs("zeigen", awareness -> awareness.reveal(admin));
        // When leaving the SMP the world change refreshes visibility; showing the player here would flash them
        // at the spot where they watched.
        if (returnToBody) {
            visibility.accept(admin);
            smp.save(admin);
            admin.sendActionBar(Messages.smp(Messages.Type.SUCCESS, "Zurück an deiner Position."));
        }
    }

    private Location bodyLocation(Session session) {
        Mannequin body = session.body;
        if (body != null && body.isValid()) {
            Location location = body.getLocation();
            location.setYaw(session.origin.getYaw());
            location.setPitch(session.origin.getPitch());
            return location;
        }
        return session.lastBodyLocation != null ? session.lastBodyLocation : session.origin;
    }

    public void shutdown() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) stop(player, true);
        }
        sessions.clear();
    }

    // ---------------------------------------------------------------- the double

    private Mannequin spawnBody(Player admin, Location location) {
        return location.getWorld().spawn(location, Mannequin.class, body -> {
            body.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, admin.getUniqueId().toString());
            // Never written to disk: after a crash no stray double is left behind.
            body.setPersistent(false);
            body.setRemoveWhenFarAway(false);
            body.setCanPickupItems(false);
            body.setSilent(true);
            body.setProfile(ResolvableProfile.resolvableProfile(admin.getPlayerProfile()));
            body.setSkinParts(admin.getClientOption(com.destroystokyo.paper.ClientOption.SKIN_PARTS));
            body.setMainHand(admin.getMainHand());
            body.setDescription(null);
            body.customName(RankService.nametag(admin));
            body.setCustomNameVisible(true);
            body.setBodyYaw(admin.getBodyYaw());
            body.setArrowsInBody(admin.getArrowsInBody(), false);
            body.addPotionEffects(admin.getActivePotionEffects());
            wear(body, admin.getEquipment());
            if (admin.isSneaking() && Mannequin.validPoses().contains(Pose.SNEAKING)) {
                body.setSneaking(true);
                body.setPose(Pose.SNEAKING, true);
            }
        });
    }

    /** Dresses the double in a copy of the given equipment (armour and both hands). */
    public static void wear(Mannequin body, EntityEquipment from) {
        EntityEquipment to = body.getEquipment();
        for (EquipmentSlot slot : BODY_SLOTS) {
            ItemStack item = from.getItem(slot);
            to.setItem(slot, item == null ? null : item.clone(), true);
        }
    }

    private void removeBody(Session session) {
        if (session.body != null) session.body.remove();
        session.body = null;
    }

    private boolean isBody(Entity entity) {
        return entity instanceof Mannequin && entity.getPersistentDataContainer().has(bodyKey, PersistentDataType.STRING);
    }

    private Session sessionOfBody(Entity entity) {
        String owner = entity.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING);
        if (owner == null) return null;
        try {
            return sessions.get(UUID.fromString(owner));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Keeps the double's chunk loaded: a non-persistent entity would vanish with its chunk. */
    private void holdChunk(Session session, Chunk chunk) {
        session.ticketChunk = chunk;
        // A ticket that other code of this plugin already holds there is never ours to remove later.
        tickets.compute(chunk, (ignored, ticket) -> {
            if (ticket != null) return new Ticket(ticket.users() + 1, ticket.ours());
            boolean ours = !chunk.getPluginChunkTickets().contains(plugin);
            if (ours) chunk.addPluginChunkTicket(plugin);
            return new Ticket(1, ours);
        });
    }

    private void releaseChunk(Session session) {
        Chunk chunk = session.ticketChunk;
        session.ticketChunk = null;
        if (chunk == null) return;
        Ticket ticket = tickets.get(chunk);
        if (ticket == null) return;
        if (ticket.users() > 1) {
            tickets.put(chunk, new Ticket(ticket.users() - 1, ticket.ours()));
            return;
        }
        tickets.remove(chunk);
        if (ticket.ours()) chunk.removePluginChunkTicket(plugin);
    }

    private void tick() {
        boolean scanMobs = ++ticks % 10 == 0;
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) continue;
            Session session = entry.getValue();
            enforceLeaveItem(player);
            hideOrbsAround(player, session);
            // Goals a mob only gets later (taming, a new weapon) are filtered here before it can turn its head.
            if (scanMobs) {
                mobs("Umgebung", awareness -> {
                    for (Entity nearby : player.getNearbyEntities(32, 32, 32)) {
                        if (nearby instanceof org.bukkit.entity.Mob mob) awareness.patch(mob);
                    }
                });
                teachLookingAtDouble(session);
            }
            Mannequin body = session.body;
            if (body != null && body.isValid()) {
                session.lastBodyLocation = body.getLocation();
            } else if (session.lastBodyLocation != null) {
                World world = session.lastBodyLocation.getWorld();
                if (world != null && world.isChunkLoaded(session.lastBodyLocation.getBlockX() >> 4,
                        session.lastBodyLocation.getBlockZ() >> 4)) {
                    Location at = session.lastBodyLocation.clone();
                    at.setYaw(session.origin.getYaw());
                    at.setPitch(session.origin.getPitch());
                    session.body = spawnBodyFromState(player, session, at);
                }
            }
        }
    }

    /** Rebuilds a double that vanished (e.g. /kill) from the stored survival state, not the creative self. */
    private Mannequin spawnBodyFromState(Player admin, Session session, Location location) {
        Mannequin body = spawnBody(admin, location);
        EntityEquipment equipment = body.getEquipment();
        ItemStack[] items = SmpPlayerState.fit(session.state.inventory(), 41);
        int held = Math.max(0, Math.min(8, session.state.heldSlot()));
        equipment.setItem(EquipmentSlot.HAND, items[held], true);
        equipment.setItem(EquipmentSlot.FEET, items[36], true);
        equipment.setItem(EquipmentSlot.LEGS, items[37], true);
        equipment.setItem(EquipmentSlot.CHEST, items[38], true);
        equipment.setItem(EquipmentSlot.HEAD, items[39], true);
        equipment.setItem(EquipmentSlot.OFF_HAND, items[40], true);
        body.clearActivePotionEffects();
        body.addPotionEffects(session.state.potionEffects());
        return body;
    }

    // ---------------------------------------------------------------- the leave item

    private ItemStack leaveItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Zuschauen verlassen", NamedTextColor.RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Rechtsklick: zurück an deine Position", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
                Component.text("und in den Survival-Modus", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(leaveKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isLeaveItem(ItemStack item) {
        return item != null && item.getType() == Material.BARRIER && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(leaveKey, PersistentDataType.BYTE);
    }

    /** The barrier stays in its slot: copies elsewhere vanish and a moved or deleted one comes back. */
    private void enforceLeaveItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        boolean changed = false;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (slot != LEAVE_SLOT && isLeaveItem(contents[slot])) {
                inventory.setItem(slot, null);
                changed = true;
            }
        }
        if (!isLeaveItem(inventory.getItem(LEAVE_SLOT))) {
            ItemStack displaced = inventory.getItem(LEAVE_SLOT);
            inventory.setItem(LEAVE_SLOT, leaveItem());
            if (displaced != null && !displaced.getType().isAir()) inventory.addItem(displaced);
            changed = true;
        }
        InventoryView view = player.getOpenInventory();
        if (isLeaveItem(view.getCursor())) {
            view.setCursor(null);
            changed = true;
        }
        if (changed) player.updateInventory();
    }

    private void requestLeave(Player player) {
        // One tick later: the off-hand half of this very click must not reach the restored survival inventory.
        if (!leaving.add(player.getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (leaving.contains(player.getUniqueId()) && player.isOnline()) stop(player, true);
            leaving.remove(player.getUniqueId());
        });
    }

    // ---------------------------------------------------------------- what the spectator does

    private boolean spectating(Entity entity) {
        return entity instanceof Player player && sessions.containsKey(player.getUniqueId());
    }

    private Player spectatorOf(Entity entity) {
        if (spectating(entity)) return (Player) entity;
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player && sessions.containsKey(player.getUniqueId())) return player;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!spectating(player)) return;
        if (event.getAction() == Action.PHYSICAL || leaving.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        ItemStack item = event.getItem();
        if (isLeaveItem(item)) {
            event.setCancelled(true);
            if (event.getAction().isRightClick() && event.getHand() == EquipmentSlot.HAND) requestLeave(player);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block block = event.getClickedBlock();
            boolean placing = player.isSneaking() && item != null && placesBlock(item);
            if (!placing && block.getType() != Material.ENDER_CHEST) {
                ContainerPeek peek = ContainerPeek.of(block);
                if (peek != null) {
                    event.setCancelled(true);
                    if (event.getHand() == EquipmentSlot.HAND) peek.open(player);
                    return;
                }
            }
            // Buttons, levers, ender chests … would make noise or react. Crafting tables show nothing to others, and
            // the sound of doors, trapdoors and fence gates comes from the hidden spectator, so nobody hears it.
            if (block.getType() != Material.CRAFTING_TABLE && !silentlyUsable(block.getType())
                    && block.getType().isInteractable()) {
                event.setUseInteractedBlock(Event.Result.DENY);
            }
        }
        // Only blocks may be used: pearls, buckets, spawn eggs, bone meal or food would all leave traces.
        if (item != null && !item.getType().isAir() && !placesBlock(item)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    private static boolean silentlyUsable(Material type) {
        return org.bukkit.Tag.DOORS.isTagged(type) || org.bukkit.Tag.TRAPDOORS.isTagged(type)
                || org.bukkit.Tag.FENCE_GATES.isTagged(type);
    }

    private static boolean placesBlock(ItemStack item) {
        return item.getType().isItem() && item.getType().asItemType() != null && item.getType().asItemType().hasBlockType();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!spectating(player)) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND && isLeaveItem(player.getInventory().getItemInMainHand())) {
            requestLeave(player);
        }
    }

    /** Creative breaking, but silent and without the pops of blocks that stood on it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (!spectating(event.getPlayer())) return;
        event.setCancelled(true);
        // The server answers the cancelled break with the block's current state, which is then already air.
        SilentBlocks.breakSilently(event.getBlock());
    }

    /** Placing by a hidden player is already silent for everyone else; claims and spawn protection do not apply. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (!spectating(event.getPlayer())) return;
        if (isLeaveItem(event.getItemInHand())) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(false);
        event.setBuild(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageBySpectator(EntityDamageByEntityEvent event) {
        // Mobs may be hit; a hit on a player would give the spectator away. The own double reacts in onBodyDamage.
        if (spectatorOf(event.getDamager()) != null && event.getEntity() instanceof Player) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() != null && spectatorOf(event.getRemover()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (event.getAttacker() != null && spectatorOf(event.getAttacker()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (spectating(event.getEntered())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (spectating(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        if (spectating(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupExperience(PlayerPickupExperienceEvent event) {
        if (spectating(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (!spectating(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Messages.smp(Messages.Type.ERROR, "Beim Zuschauen kannst du nichts droppen."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (spectating(event.getPlayer()) && (isLeaveItem(event.getMainHandItem()) || isLeaveItem(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickItem(PlayerPickItemEvent event) {
        if (spectating(event.getPlayer()) && event.getTargetSlot() == LEAVE_SLOT) event.setCancelled(true);
    }

    /** Sculk sensors and shriekers must not notice the spectator. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onGameEvent(BlockReceiveGameEvent event) {
        if (event.getEntity() != null && spectatorOf(event.getEntity()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() == null || !spectating(event.getTarget())) return;
        // An orb picks the nearest player; with the spectator out of the way it goes where vanilla would send it.
        if (event.getEntity() instanceof org.bukkit.entity.ExperienceOrb orb
                && event instanceof org.bukkit.event.entity.EntityTargetLivingEntityEvent living) {
            Player next = nearestVisiblePlayer(orb.getLocation(), 8.0);
            if (next != null) {
                living.setTarget(next);
                return;
            }
        }
        event.setCancelled(true);
    }

    private Player nearestVisiblePlayer(Location location, double range) {
        Player nearest = null;
        double best = range * range;
        for (Player player : location.getWorld().getPlayers()) {
            if (spectating(player) || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) continue;
            double distance = player.getLocation().distanceSquared(location);
            if (distance <= best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    /** New orbs near a spectator are hidden from them before their client ever sees them. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOrbAdded(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent event) {
        if (sessions.isEmpty() || !(event.getEntity() instanceof org.bukkit.entity.ExperienceOrb orb)) return;
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.getWorld().equals(event.getWorld())
                    && player.getLocation().distanceSquared(orb.getLocation()) <= ORB_HIDE_RANGE * ORB_HIDE_RANGE) {
                hideOrb(player, entry.getValue(), orb);
            }
        }
    }

    private void hideOrbsAround(Player player, Session session) {
        for (Entity nearby : player.getNearbyEntities(ORB_HIDE_RANGE, ORB_HIDE_RANGE, ORB_HIDE_RANGE)) {
            if (nearby instanceof org.bukkit.entity.ExperienceOrb orb) hideOrb(player, session, orb);
        }
        session.hiddenOrbs.removeIf(uuid -> plugin.getServer().getEntity(uuid) == null);
    }

    private void hideOrb(Player player, Session session, org.bukkit.entity.ExperienceOrb orb) {
        if (session.hiddenOrbs.add(orb.getUniqueId())) player.hideEntity(plugin, orb);
    }

    private void showOrbs(Player player, Session session) {
        for (UUID uuid : session.hiddenOrbs) {
            Entity orb = plugin.getServer().getEntity(uuid);
            if (orb != null) player.showEntity(plugin, orb);
        }
        session.hiddenOrbs.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !spectating(player)) return;
        if (event.getInventory().getHolder(false) instanceof ContainerPeek peek) peek.click(event);
        boolean ownLeaveSlot = event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == LEAVE_SLOT;
        boolean numberKey = event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == LEAVE_SLOT;
        boolean offhandSwap = event.getClick() == ClickType.SWAP_OFFHAND
                && isLeaveItem(player.getInventory().getItemInOffHand());
        boolean thrownOut = event instanceof InventoryCreativeEvent && event.getSlotType() == InventoryType.SlotType.OUTSIDE;
        if (ownLeaveSlot || numberKey || offhandSwap || thrownOut
                || isLeaveItem(event.getCurrentItem()) || isLeaveItem(event.getCursor())) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (spectating(player)) enforceLeaveItem(player);
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !spectating(player)) return;
        if (event.getInventory().getHolder(false) instanceof ContainerPeek peek) peek.drag(event);
        InventoryView view = event.getView();
        boolean leaveSlot = event.getRawSlots().stream().anyMatch(raw ->
                view.getInventory(raw) instanceof PlayerInventory && view.convertSlot(raw) == LEAVE_SLOT);
        if (leaveSlot || isLeaveItem(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof ContainerPeek peek
                && event.getPlayer() instanceof Player player) {
            peek.close(player);
        }
    }

    /** Only the void or /kill can do this; nobody may learn about it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!spectating(event.getEntity())) return;
        event.deathMessage(null);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (spectating(player)) plugin.getServer().getScheduler().runTask(plugin, () -> stop(player, true));
    }

    // ---------------------------------------------------------------- the double reacts like an AFK player

    /** Mannequins have no drop chances; a double removed by /kill must not hand out copies of the admin's gear. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodyDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (!isBody(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBodyDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!isBody(entity)) return;
        event.setCancelled(true);
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return;
        Entity attacker = byEntity.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        if (!(attacker instanceof Player)) return;
        Session session = sessionOfBody(entity);
        long now = System.currentTimeMillis();
        if (session != null && now - session.lastHurt < HURT_COOLDOWN_MILLIS) return;
        if (session != null) session.lastHurt = now;
        Mannequin body = (Mannequin) entity;
        float yaw = attacker.getLocation().getYaw();
        body.playHurtAnimation(yaw - body.getYaw());
        body.getWorld().playSound(body.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1.0f, 1.0f);
        double radians = Math.toRadians(yaw);
        body.knockback(0.4, Math.sin(radians), -Math.cos(radians));
    }
}
