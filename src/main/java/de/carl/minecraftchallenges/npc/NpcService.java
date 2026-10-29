package de.carl.minecraftchallenges.npc;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.custom.CustomChallengeMenu;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.menu.ChallengeMenu;
import de.carl.minecraftchallenges.menu.LobbyMenu;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Lobby NPCs use native player-shaped mannequins; no packet or NPC plugin dependency. */
@SuppressWarnings("UnstableApiUsage")
public final class NpcService implements Listener, CommandExecutor {
    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final GameLobbyRegistry registry;
    private final ChallengeMenu navigator;
    private final LobbyMenu lobbyMenu;
    private final CustomChallengeMenu customMenu;
    private final SmpService smp;
    private final NamespacedKey entityKey;
    private final Map<UUID, Definition> definitions = new LinkedHashMap<>();
    private final Map<UUID, Actors> actors = new HashMap<>();
    private final Map<UUID, Set<UUID>> touching = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<UUID, UUID> dialogs = new HashMap<>();
    private final Set<Chunk> tickets = new HashSet<>();
    private final BukkitTask ticker;
    private final Path file;
    private final NpcLookPackets lookPackets;
    private boolean lookErrorLogged;
    private int ticks;

    private record Definition(UUID id, Location location, String skin, String action, String command) {
        String title() { return actionName(action); }
    }
    private record Actors(Mannequin body, TextDisplay title, TextDisplay hint, TextDisplay rejoinHint,
                          TextDisplay population) {
        void remove() {
            body.remove(); title.remove(); hint.remove(); population.remove();
            if (rejoinHint != null) rejoinHint.remove();
        }
    }
    private static final class Menu implements InventoryHolder {
        final Location placement;
        final UUID editing;
        final int page;
        final Map<Integer, String> actions = new HashMap<>();
        final Map<Integer, UUID> entries = new HashMap<>();
        Inventory inventory;
        Menu(Location placement, UUID editing, int page) {
            this.placement = placement.clone(); this.editing = editing; this.page = page;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    public NpcService(MinecraftChallengesPlugin plugin, LobbyService lobby, GameLobbyRegistry registry,
                      ChallengeMenu navigator, LobbyMenu lobbyMenu, CustomChallengeMenu customMenu, SmpService smp) {
        this.plugin = plugin; this.lobby = lobby; this.registry = registry;
        this.navigator = navigator; this.lobbyMenu = lobbyMenu; this.customMenu = customMenu;
        this.smp = smp;
        entityKey = new NamespacedKey(plugin, "lobby_npc");
        file = plugin.getDataFolder().toPath().resolve("npcs.yml");
        try { lookPackets = new NpcLookPackets(); }
        catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Paper NPC rotation packet layout is unsupported", error);
        }
        load();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && allowed(player)) openHome(player, player.getLocation(), 0);
        return true;
    }

    private boolean allowed(Player player) {
        if (!player.hasPermission(RankService.ADMIN)) {
            Messages.send(player, Messages.Type.ERROR, "Nur Admins dürfen NPCs verwalten."); return false;
        }
        if (!lobby.isLobbyWorld(player.getWorld())) {
            Messages.send(player, Messages.Type.ERROR, "NPCs können nur in der Lobby verwaltet werden."); return false;
        }
        return true;
    }

    private Inventory menu(Player player, Menu holder, String title) {
        dialogs.remove(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text(title, NamedTextColor.DARK_GRAY));
        MenuItems.drawBorder(holder.inventory);
        player.openInventory(holder.inventory);
        return holder.inventory;
    }

    private ItemStack item(Material icon, String title, String... lines) {
        return MenuItems.item(icon, Component.text(title, NamedTextColor.GOLD),
                Arrays.stream(lines).<Component>map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
    }

    private void openHome(Player player, Location placement, int page) {
        Menu holder = new Menu(placement, null, page);
        Inventory inventory = menu(player, holder, "Lobby-NPCs · Admin");
        inventory.setItem(4, item(Material.EMERALD, "NPC erstellen", "An deiner Position beim Öffnen von /npc", "inklusive Blickrichtung"));
        List<Definition> list = new ArrayList<>(definitions.values());
        for (int i = page * 28; i < Math.min(list.size(), (page + 1) * 28); i++) {
            int offset = i - page * 28;
            int slot = 10 + offset / 7 * 9 + offset % 7;
            Definition def = list.get(i);
            holder.entries.put(slot, def.id);
            inventory.setItem(slot, item(Material.PLAYER_HEAD, def.title(), "Skin: " + def.skin,
                    "Aktion: " + actionName(def.action), "Klicken zum Bearbeiten"));
        }
        if (page > 0) inventory.setItem(45, item(Material.ARROW, "Vorherige Seite"));
        if (list.size() > (page + 1) * 28) inventory.setItem(53, item(Material.ARROW, "Nächste Seite"));
    }

    private void openActions(Player player, Location placement, UUID editing) {
        Menu holder = new Menu(placement, editing, 0);
        Inventory inventory = menu(player, holder, "NPC · Aktion auswählen");
        int slot = 10;
        for (GameType type : GameType.values()) {
            holder.actions.put(slot, type.name());
            inventory.setItem(slot++, item(type.icon(), type.displayName(), "Links: Quick Join · Rechts: Lobby-Menü"));
        }
        if (navigator.isJumpAndRunAvailable()) {
            holder.actions.put(20, "JUMP_AND_RUN");
            inventory.setItem(20, item(Material.GOLDEN_BOOTS, "Jump'n'Run", "Teleportiert zum gesetzten Spawn"));
        }
        if (navigator.isSmpAvailable()) {
            holder.actions.put(21, "SMP");
            inventory.setItem(21, item(Material.GRASS_BLOCK, "SMP", "Tritt der dauerhaften Survival-Welt bei"));
        }
        holder.actions.put(24, "COMMAND");
        inventory.setItem(24, item(Material.COMMAND_BLOCK, "Eigener Befehl", "Rechtsklick / Berühren führt ihn als Spieler aus", "Es gelten die Rechte des Spielers. Platzhalter: {player}"));
        inventory.setItem(49, item(Material.ARROW, "Zurück"));
    }

    private void openEdit(Player player, Definition def) {
        Menu holder = new Menu(player.getLocation(), def.id, 0);
        Inventory inventory = menu(player, holder, "NPC bearbeiten · " + def.title());
        inventory.setItem(20, item(Material.NAME_TAG, def.action.equals("COMMAND") ? "Skin / Befehl bearbeiten" : "Skin ändern"));
        inventory.setItem(22, item(Material.COMPASS, "Aktion ändern"));
        inventory.setItem(24, item(Material.ENDER_PEARL, "Hierher versetzen", "Aktuelle Position und Blickrichtung übernehmen"));
        inventory.setItem(31, item(Material.BARRIER, "NPC löschen", "Öffnet eine Bestätigung"));
        inventory.setItem(49, item(Material.ARROW, "Zurück"));
    }

    private static String actionName(String action) {
        return switch (action) {
            case "JUMP_AND_RUN" -> "Jump'n'Run";
            case "SMP" -> "SMP";
            case "COMMAND" -> "Eigener Befehl";
            default -> GameType.valueOf(action).displayName();
        };
    }

    private void configure(Player player, Location placement, UUID editing, String action) {
        Definition previous = definitions.get(editing);
        String skin = previous == null ? player.getName() : previous.skin;
        List<DialogInput> inputs = new ArrayList<>(List.of(
                DialogInput.text("skin", 300, Component.text("Skin · Minecraft-Benutzername"), true, skin, 16, null)));
        if (action.equals("COMMAND")) inputs.add(DialogInput.text("command", 300,
                Component.text("Befehl als Spieler · {player} möglich"), true,
                previous == null ? "" : previous.command, 256, null));
        UUID token = UUID.randomUUID();
        dialogs.put(player.getUniqueId(), token);
        player.closeInventory();
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("NPC konfigurieren"))
                        .inputs(inputs).canCloseWithEscape(true).build())
                .type(DialogType.confirmation(ActionButton.create(Component.text("Speichern"), null, 150,
                        DialogAction.customClick((response, audience) -> {
                            if (!plugin.isEnabled() || !player.equals(audience)) return;
                            String newSkin = Objects.requireNonNullElse(response.getText("skin"), "").trim();
                            String rawCommand = Objects.requireNonNullElse(response.getText("command"), "").trim();
                            String newCommand = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!token.equals(dialogs.remove(player.getUniqueId())) || !allowed(player)) return;
                                if (editing != null && !definitions.containsKey(editing)) return;
                                if (!newSkin.matches("[A-Za-z0-9_]{1,16}")
                                        || action.equals("COMMAND") && (newCommand.isBlank()
                                        || newCommand.chars().anyMatch(Character::isISOControl))) {
                                    Messages.send(player, Messages.Type.ERROR, "Bitte gültigen Skin-Benutzername und gegebenenfalls Befehl eingeben.");
                                    configure(player, placement, editing, action); return;
                                }
                                Definition def = new Definition(editing == null ? UUID.randomUUID() : editing,
                                        previous == null ? placement.clone() : previous.location.clone(),
                                        newSkin, action, newCommand);
                                if (commit(def, false)) {
                                    // Do not immediately activate a newly placed NPC underneath its creator.
                                    touching.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>()).add(def.id);
                                    Messages.send(player, Messages.Type.SUCCESS, "NPC gespeichert.");
                                    openEdit(player, def);
                                }
                            });
                        }, ClickCallback.Options.builder().uses(1).build())),
                        ActionButton.create(Component.text("Abbrechen"), null, 150, null)))));
    }

    private void confirmDelete(Player player, Definition def) {
        UUID token = UUID.randomUUID(); dialogs.put(player.getUniqueId(), token);
        player.closeInventory();
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("NPC „" + def.title() + "“ löschen?"))
                        .canCloseWithEscape(true).build())
                .type(DialogType.confirmation(ActionButton.create(Component.text("Löschen"), null, 150,
                        DialogAction.customClick((response, audience) -> {
                            if (!plugin.isEnabled() || !player.equals(audience)) return;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!token.equals(dialogs.remove(player.getUniqueId())) || !allowed(player)) return;
                                if (definitions.containsKey(def.id) && commit(def, true)) openHome(player, player.getLocation(), 0);
                            });
                        }, ClickCallback.Options.builder().uses(1).build())),
                        ActionButton.create(Component.text("Abbrechen"), null, 150, null)))));
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !allowed(player)) return;
        int slot = event.getRawSlot();
        if (holder.actions.containsKey(slot)) {
            configure(player, holder.placement, holder.editing, holder.actions.get(slot)); return;
        }
        if (!holder.actions.isEmpty()) {
            if (slot == 49) openHome(player, holder.placement, 0);
            return;
        }
        if (holder.editing == null) {
            if (slot == 4) openActions(player, holder.placement, null);
            else if (holder.entries.containsKey(slot)) openEdit(player, definitions.get(holder.entries.get(slot)));
            else if (slot == 45 && holder.page > 0) openHome(player, holder.placement, holder.page - 1);
            else if (slot == 53 && definitions.size() > (holder.page + 1) * 28) openHome(player, holder.placement, holder.page + 1);
            return;
        }
        Definition def = definitions.get(holder.editing);
        if (def == null) { openHome(player, holder.placement, 0); return; }
        switch (slot) {
            case 20 -> configure(player, def.location, def.id, def.action);
            case 22 -> openActions(player, def.location, def.id);
            case 24 -> {
                Definition moved = new Definition(def.id, player.getLocation(), def.skin, def.action, def.command);
                if (commit(moved, false)) openEdit(player, moved);
            }
            case 31 -> confirmDelete(player, def);
            case 49 -> openHome(player, player.getLocation(), 0);
            default -> { }
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private Definition definition(Entity entity) {
        String id = entity.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
        if (id == null) return null;
        try { return definitions.get(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { return null; }
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onInteract(PlayerInteractEntityEvent event) {
        Definition def = definition(event.getRightClicked());
        if (def == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (lobby.isEditing(player)) {
            if (allowed(player)) openEdit(player, def);
            return;
        }
        activate(player, def, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onAttack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        Definition def = definition(event.getAttacked());
        if (def == null) return;
        event.setCancelled(true);
        if (!def.action.equals("COMMAND")) activate(event.getPlayer(), def, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onDamage(EntityDamageEvent event) {
        Definition def = definition(event.getEntity());
        if (def == null) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player player
                && !def.action.equals("COMMAND")) activate(player, def, true);
    }

    private void activate(Player player, Definition def, boolean quick) {
        if (!lobby.isLobbyWorld(player.getWorld()) || lobby.isEditing(player) || player.isDead()
                || player.getGameMode() == GameMode.SPECTATOR) return;
        long now = System.currentTimeMillis();
        if (now - cooldown.getOrDefault(player.getUniqueId(), 0L) < 1000) return;
        cooldown.put(player.getUniqueId(), now);
        switch (def.action) {
            case "JUMP_AND_RUN" -> { player.closeInventory(); navigator.selectJumpAndRun(player); }
            case "SMP" -> navigator.selectSmp(player);
            case "COMMAND" -> { player.closeInventory(); player.performCommand(def.command.replace("{player}", player.getName())); }
            default -> {
                GameType type = GameType.valueOf(def.action);
                var target = quick ? QuickJoin.select(registry.lobbies(type), player) : null;
                if (target != null) { player.closeInventory(); registry.join(player, target); }
                else if (type == GameType.CUSTOM) customMenu.openList(player);
                else lobbyMenu.open(player, type);
            }
        }
    }

    private GameInstance quickJoinTarget(Definition def) {
        if (def.action.equals("COMMAND") || def.action.equals("SMP") || def.action.equals("JUMP_AND_RUN")) return null;
        return QuickJoin.select(registry.lobbies(GameType.valueOf(def.action)));
    }

    private boolean hasQuickJoin(Definition def) {
        return quickJoinTarget(def) != null;
    }

    private Component populationText(Definition def) {
        if (def.action.equals("SMP")) {
            return smp == null ? Component.empty() : population(smp.playerCount(), smp.maxPlayers());
        }
        GameInstance target = quickJoinTarget(def);
        if (target != null) return population(target.onlineParticipantCount(), target.maxPlayers());
        return Component.empty();
    }

    private static Component population(int players, int capacity) {
        return Component.text("● " + players + "/" + capacity, NamedTextColor.GREEN);
    }

    private void tick() {
        if (ticks++ % 20 == 0) for (Definition def : definitions.values()) {
            Actors set = actors.get(def.id);
            if (set == null || !set.body.isValid() || !set.title.isValid() || !set.hint.isValid()
                    || !set.population.isValid()
                    || set.rejoinHint != null && !set.rejoinHint.isValid()) {
                if (set != null) set.remove();
                spawn(def); set = actors.get(def.id);
            }
            if (set == null) continue;
            Component population = populationText(def);
            if (!population.equals(set.population.text())) set.population.text(population);
            Component hint = Component.text(hasQuickJoin(def)
                    ? "Linksklick: Quick Join · Rechtsklick: Lobby-Menü"
                    : def.action.equals("COMMAND") ? "Rechtsklick oder Berühren"
                    : def.action.equals("JUMP_AND_RUN") || def.action.equals("SMP") ? "Klicken oder Berühren"
                    : "Klicken oder Berühren: Lobby-Menü", NamedTextColor.GRAY);
            if (!hint.equals(set.hint.text())) set.hint.text(hint);
            if (set.rejoinHint != null) {
                // Only returning participants see Quick Join when no public custom slot is joinable.
                boolean publicJoin = hasQuickJoin(def);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    boolean returning = !publicJoin && QuickJoin.select(registry.lobbies(GameType.CUSTOM), viewer) != null;
                    if (returning) {
                        viewer.hideEntity(plugin, set.hint);
                        viewer.showEntity(plugin, set.rejoinHint);
                    } else {
                        viewer.showEntity(plugin, set.hint);
                        viewer.hideEntity(plugin, set.rejoinHint);
                    }
                }
            }
        }
        if (ticks % 2 == 0) updateNpcLooks();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<UUID> previous = touching.getOrDefault(player.getUniqueId(), Set.of());
            Set<UUID> current = new HashSet<>();
            if (lobby.isLobbyWorld(player.getWorld())) {
                for (Definition def : definitions.values()) {
                    if (def.location.getWorld() != player.getWorld()) continue;
                    var box = player.getBoundingBox();
                    if (box.overlaps(new org.bukkit.util.BoundingBox(def.location.getX() - .4, def.location.getY(),
                            def.location.getZ() - .4, def.location.getX() + .4, def.location.getY() + 1.9,
                            def.location.getZ() + .4))) current.add(def.id);
                }
            }
            touching.put(player.getUniqueId(), current);
            if (player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) continue;
            for (UUID id : current) if (!previous.contains(id)) { activate(player, definitions.get(id), true); break; }
        }
    }

    private void updateNpcLooks() {
        for (Actors actor : actors.values()) {
            Mannequin npc = actor.body;
            if (!npc.isValid()) continue;
            Location eyes = npc.getEyeLocation();
            for (Player viewer : npc.getTrackedBy()) {
                if (!viewer.isOnline() || viewer.getWorld() != npc.getWorld() || !viewer.canSee(npc)) continue;
                Location target = viewer.getEyeLocation();
                NpcLookDirection direction = NpcLookDirection.towards(target.getX() - eyes.getX(),
                        target.getY() - eyes.getY(), target.getZ() - eyes.getZ(), eyes.getYaw());
                try { lookPackets.send(viewer, npc, direction); }
                catch (ReflectiveOperationException error) {
                    if (!lookErrorLogged) {
                        lookErrorLogged = true;
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "NPC-Blickrichtung konnte nicht an den Spieler gesendet werden", error);
                    }
                }
            }
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); touching.remove(id); cooldown.remove(id); dialogs.remove(id);
    }

    private void mark(Entity entity, UUID id) {
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, id.toString());
    }

    private void spawn(Definition def) {
        World world = def.location.getWorld();
        if (world == null || !lobby.isLobbyWorld(world)) return;
        Chunk chunk = def.location.getChunk();
        if (tickets.add(chunk)) {
            chunk.addPluginChunkTicket(plugin);
            for (Entity entity : chunk.getEntities()) {
                if (entity.getPersistentDataContainer().has(entityKey)) entity.remove();
            }
        }
        Mannequin body = world.spawn(def.location, Mannequin.class, npc -> {
            mark(npc, def.id); npc.setImmovable(true); npc.setGravity(false);
            npc.setAI(false); npc.setCollidable(false); npc.setInvulnerable(true); npc.setSilent(true);
            npc.setRemoveWhenFarAway(false); npc.setCanPickupItems(false); npc.setDescription(null);
            npc.setProfile(ResolvableProfile.resolvableProfile().name(def.skin).build());
        });
        TextDisplay title = text(def, 2.55, 1.0f);
        title.text(Component.text(def.title(), NamedTextColor.GOLD));
        TextDisplay hint = text(def, 2.23, .65f);
        TextDisplay rejoinHint = null;
        if (def.action.equals(GameType.CUSTOM.name())) {
            rejoinHint = text(def, 2.23, .65f);
            rejoinHint.setVisibleByDefault(false);
            rejoinHint.text(Component.text("Linksklick: Quick Join · Rechtsklick: Lobby-Menü", NamedTextColor.GRAY));
        }
        TextDisplay population = text(def, 2.90, .65f);
        population.text(populationText(def));
        actors.put(def.id, new Actors(body, title, hint, rejoinHint, population));
    }

    private TextDisplay text(Definition def, double height, float scale) {
        return def.location.getWorld().spawn(def.location.clone().add(0, height, 0), TextDisplay.class, text -> {
            mark(text, def.id); text.setBillboard(Display.Billboard.CENTER);
            text.setShadowed(true); text.setDefaultBackground(false); text.setLineWidth(1000);
            var transform = text.getTransformation(); transform.getScale().set(scale);
            text.setTransformation(transform);
        });
    }

    /** Persist first, then publish the new definition and replace the visible actors. */
    private boolean commit(Definition def, boolean delete) {
        Map<UUID, Definition> next = new LinkedHashMap<>(definitions);
        if (delete) next.remove(def.id); else next.put(def.id, def);
        YamlConfiguration yaml = new YamlConfiguration();
        next.values().forEach(entry -> {
            String key = "npcs." + entry.id;
            yaml.set(key + ".location", entry.location); yaml.set(key + ".skin", entry.skin);
            yaml.set(key + ".action", entry.action);
            yaml.set(key + ".command", entry.command);
        });
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "npcs-", ".tmp");
            try {
                Files.writeString(temporary, yaml.saveToString());
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException error) {
            plugin.getLogger().severe("NPCs konnten nicht gespeichert werden: " + error.getMessage());
            Bukkit.getOnlinePlayers().stream().filter(player -> player.hasPermission(RankService.ADMIN))
                    .forEach(player -> Messages.send(player, Messages.Type.ERROR,
                            "NPC konnte nicht gespeichert werden. Bitte Server-Log prüfen."));
            return false;
        }
        definitions.clear(); definitions.putAll(next);
        Actors old = actors.remove(def.id); if (old != null) old.remove();
        if (!delete) spawn(def);
        releaseUnusedTickets();
        return true;
    }

    private void releaseUnusedTickets() {
        tickets.removeIf(chunk -> {
            boolean used = definitions.values().stream().anyMatch(def -> def.location.getWorld() == chunk.getWorld()
                    && (def.location.getBlockX() >> 4) == chunk.getX() && (def.location.getBlockZ() >> 4) == chunk.getZ());
            if (!used) chunk.removePluginChunkTicket(plugin);
            return !used;
        });
    }

    private void load() {
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (Exception error) { throw new IllegalStateException("npcs.yml konnte nicht gelesen werden", error); }
        var section = yaml.getConfigurationSection("npcs");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                String path = "npcs." + key;
                Location location = yaml.getLocation(path + ".location");
                if (location == null || location.getWorld() == null || !lobby.isLobbyWorld(location.getWorld()))
                    throw new IllegalArgumentException("Lobby-Welt nicht verfügbar");
                String action = yaml.getString(path + ".action", ""); actionName(action);
                Definition def = new Definition(UUID.fromString(key), location, yaml.getString(path + ".skin", "Steve"),
                        action, yaml.getString(path + ".command", ""));
                definitions.put(def.id, def);
            } catch (RuntimeException error) {
                // Do not silently drop entries and overwrite the original file on the next edit.
                throw new IllegalStateException("Ungültiger NPC " + key + ": " + error.getMessage(), error);
            }
        }
    }

    public void shutdown() {
        ticker.cancel(); actors.values().forEach(Actors::remove); actors.clear();
        tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin)); tickets.clear();
        dialogs.clear(); touching.clear(); cooldown.clear();
    }
}
