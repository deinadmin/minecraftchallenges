package de.carl.minecraftchallenges.custom;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.PlayerInventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Hands out custom challenge slots. Players share the regular slots and wait in a queue while all of
 * them are taken; admins get additional slots, which are registered on demand.
 */
public final class CustomChallengeSlots implements Listener {
    public static final int MAX_EXTRA_SLOTS = 14;

    private final LobbyService lobby;
    private final RankService ranks;
    private final GameLobbyRegistry registry;
    private final IntFunction<CustomChallenge> slotFactory;
    private final Map<UUID, CustomSettings> queue = new LinkedHashMap<>();

    public CustomChallengeSlots(MinecraftChallengesPlugin plugin, LobbyService lobby, RankService ranks,
                                GameLobbyRegistry registry, IntFunction<CustomChallenge> slotFactory) {
        this.lobby = lobby;
        this.ranks = ranks;
        this.registry = registry;
        this.slotFactory = slotFactory;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ---------------------------------------------------------------- slots

    public List<CustomChallenge> regularSlots() {
        return registry.customs().filter(challenge -> challenge.lobbyId() <= CustomChallenge.SLOT_COUNT).toList();
    }

    public List<CustomChallenge> extraSlots() {
        return registry.customs().filter(challenge -> challenge.lobbyId() > CustomChallenge.SLOT_COUNT).toList();
    }

    private CustomChallenge freeRegularSlot() {
        return regularSlots().stream().filter(CustomChallenge::isAvailable).findFirst().orElse(null);
    }

    /** Queued players have priority over regular slots that just became free. */
    private boolean regularSlotOpen() {
        return queue.isEmpty() && freeRegularSlot() != null;
    }

    private boolean extraSlotPossible() {
        List<CustomChallenge> extras = extraSlots();
        return extras.size() < MAX_EXTRA_SLOTS || extras.stream().anyMatch(CustomChallenge::isAvailable);
    }

    private CustomChallenge extraSlot() {
        List<CustomChallenge> extras = extraSlots();
        CustomChallenge free = extras.stream().filter(CustomChallenge::isAvailable).findFirst().orElse(null);
        if (free != null || extras.size() >= MAX_EXTRA_SLOTS) return free;
        return slotFactory.apply((int) registry.customs().count() + 1);
    }

    public boolean usesExtraSlot(Player player) {
        return !regularSlotOpen() && ranks.isAdmin(player) && extraSlotPossible();
    }

    public boolean wouldQueue(Player player) {
        return !regularSlotOpen() && !usesExtraSlot(player);
    }

    // ---------------------------------------------------------------- queue

    public int queueSize() {
        return queue.size();
    }

    public boolean isQueued(Player player) {
        return queue.containsKey(player.getUniqueId());
    }

    /** Confirm button of the creation view: creates the challenge right away or queues the player. */
    public void submit(Player player, CustomSettings draft) {
        if (leadsChallenge(player)) {
            Messages.send(player, Messages.Type.ERROR, "Du leitest bereits eine eigene Challenge.");
            return;
        }
        if (isQueued(player)) {
            Messages.send(player, Messages.Type.ERROR, "Du bist bereits in der Warteschlange.");
            return;
        }
        CustomChallenge slot = regularSlotOpen() ? freeRegularSlot() : null;
        if (slot == null && ranks.isAdmin(player)) slot = extraSlot();
        if (slot != null) {
            launch(player, draft, slot);
            return;
        }
        enqueue(player, draft);
    }

    private void enqueue(Player player, CustomSettings draft) {
        if (lobby.isEditing(player)) {
            Messages.send(player, Messages.Type.ERROR, "Beende zuerst den Bearbeitungsmodus.");
            return;
        }
        if (!registry.mayEnter(player, regularSlots().getFirst())) return;
        player.closeInventory();
        if (!lobby.isLobbyWorld(player.getWorld())) lobby.sendToLobby(player);
        queue.put(player.getUniqueId(), draft.copy());
        showQueueItem(player);
        Messages.send(player, Messages.Type.INFO, "Alle " + CustomChallenge.SLOT_COUNT
                + " Plätze sind belegt. Du bist auf Platz " + queue.size() + " der Warteschlange.");
        Messages.send(player, Messages.Type.USUAL,
                "Sobald ein Platz frei wird, wird deine Challenge automatisch erstellt.");
        showPosition(player, queue.size());
    }

    public void leave(Player player) {
        if (queue.remove(player.getUniqueId()) == null) return;
        restoreNavigation(player);
        player.sendActionBar(Component.empty());
        Messages.send(player, Messages.Type.INFO, "Du hast die Warteschlange verlassen.");
    }

    private void tick() {
        queue.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        advanceQueue();
        int position = 0;
        for (UUID uuid : queue.keySet()) {
            position++;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || lobby.isEditing(player) || lobby.isInJumpAndRun(player)) continue;
            if (!lobby.isQueueItem(player.getInventory().getItem(LobbyService.COMPASS_SLOT))) showQueueItem(player);
            showPosition(player, position);
        }
    }

    private void advanceQueue() {
        while (!queue.isEmpty()) {
            CustomChallenge slot = freeRegularSlot();
            if (slot == null) return;
            UUID first = queue.keySet().iterator().next();
            CustomSettings draft = queue.remove(first);
            Player player = Bukkit.getPlayer(first);
            if (player == null) continue;
            restoreNavigation(player);
            player.sendActionBar(Component.empty());
            if (leadsChallenge(player)) continue;
            Messages.send(player, Messages.Type.SUCCESS, "Ein Platz ist frei geworden. Deine Challenge wird erstellt …");
            launch(player, draft, slot);
        }
    }

    private void launch(Player player, CustomSettings draft, CustomChallenge slot) {
        if (!registry.mayEnter(player, slot)) return;
        player.closeInventory();
        slot.create(player, draft).thenAccept(ready -> {
            if (ready && player.isOnline()) {
                Messages.send(player, Messages.Type.SUCCESS, "Deine Challenge wurde erstellt.");
            }
        });
        registry.join(player, slot);
    }

    private boolean leadsChallenge(Player player) {
        return registry.customs().anyMatch(challenge -> challenge.isActive() && challenge.isOwner(player));
    }

    private void showQueueItem(Player player) {
        if (lobby.isInJumpAndRun(player)) return;
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setItem(LobbyService.COMPASS_SLOT, lobby.createQueueItem());
        inventory.setHeldItemSlot(LobbyService.COMPASS_SLOT);
    }

    private void showPosition(Player player, int position) {
        player.sendActionBar(Messages.lobby(Messages.Type.INFO,
                "Warteschlange: Platz " + position + " von " + queue.size()));
    }

    private void restoreNavigation(Player player) {
        if (!lobby.isLobbyWorld(player.getWorld()) || lobby.isEditing(player) || lobby.isInJumpAndRun(player)) return;
        PlayerInventory inventory = player.getInventory();
        if (lobby.isQueueItem(inventory.getItem(LobbyService.COMPASS_SLOT))) {
            inventory.setItem(LobbyService.COMPASS_SLOT, lobby.createCompass());
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!lobby.isQueueItem(event.getItem())) return;
        event.setCancelled(true);
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            leave(event.getPlayer());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (lobby.isLobbyWorld(player.getWorld()) || queue.remove(player.getUniqueId()) == null) return;
        Messages.send(player, Messages.Type.INFO, "Du hast die Lobby verlassen und wurdest aus der Warteschlange entfernt.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        queue.remove(event.getPlayer().getUniqueId());
    }
}
