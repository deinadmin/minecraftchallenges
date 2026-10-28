package de.carl.minecraftchallenges.lobby;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeManager;
import de.carl.minecraftchallenges.challenge.ChallengeSessionService;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.custom.CustomChallenge;
import de.carl.minecraftchallenges.randomizer.RandomizerChallenge;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class GameLobbyRegistry {
    public static final int LOBBY_COUNT = 10;

    private final MinecraftChallengesPlugin plugin;
    private final LobbyService lobby;
    private final ChallengeSessionService sessions;
    private final Map<GameType, List<GameInstance>> lobbies = new EnumMap<>(GameType.class);
    private final Map<String, GameInstance> byWorldName = new HashMap<>();
    private final List<GameInstance> all = new ArrayList<>();

    public GameLobbyRegistry(MinecraftChallengesPlugin plugin, LobbyService lobby,
                             ChallengeSessionService sessions) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.sessions = sessions;
    }

    public void register(GameInstance instance) {
        List<GameInstance> ofType = lobbies.computeIfAbsent(instance.gameType(), ignored -> new ArrayList<>());
        if (instance.lobbyId() != ofType.size() + 1) {
            throw new IllegalStateException("Lobbys müssen in Reihenfolge registriert werden: "
                    + instance.displayName());
        }
        for (String worldName : instance.worldNames()) {
            GameInstance previous = byWorldName.putIfAbsent(worldName, instance);
            if (previous != null) {
                throw new IllegalStateException("Weltname „" + worldName + "“ wird von "
                        + previous.displayName() + " und " + instance.displayName() + " verwendet.");
            }
        }
        ofType.add(instance);
        all.add(instance);
    }

    public void startIdleMonitor() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int idleSeconds = Math.max(30, plugin.getConfig().getInt("lobbies.idle-unload-seconds", 120));
            all.forEach(instance -> instance.tickIdle(idleSeconds));
        }, 20L, 20L);
    }

    public List<GameInstance> all() {
        return all;
    }

    public List<GameInstance> lobbies(GameType type) {
        return lobbies.getOrDefault(type, List.of());
    }

    public GameInstance lobby(GameType type, int lobbyId) {
        List<GameInstance> ofType = lobbies(type);
        return lobbyId >= 1 && lobbyId <= ofType.size() ? ofType.get(lobbyId - 1) : null;
    }

    public GameInstance instance(World world) {
        return world == null ? null : byWorldName.get(world.getName());
    }

    public ChallengeManager mobHunt(World world) {
        return instance(world) instanceof ChallengeManager manager ? manager : null;
    }

    public RandomizerChallenge randomizer(World world) {
        return instance(world) instanceof RandomizerChallenge challenge ? challenge : null;
    }

    public Stream<ChallengeManager> mobHunts() {
        return lobbies(GameType.MOB_HUNT).stream().map(ChallengeManager.class::cast);
    }

    public Stream<RandomizerChallenge> randomizers() {
        return all.stream()
                .filter(RandomizerChallenge.class::isInstance)
                .map(RandomizerChallenge.class::cast);
    }

    public CustomChallenge custom(World world) {
        return instance(world) instanceof CustomChallenge challenge ? challenge : null;
    }

    public Stream<CustomChallenge> customs() {
        return lobbies(GameType.CUSTOM).stream().map(CustomChallenge.class::cast);
    }

    public boolean mayEnter(Player player, GameInstance target) {
        return sessions.mayEnter(player, target.sessionKey(), target.displayName());
    }

    public boolean isWaitingForRegeneration(Player player) {
        return all.stream().anyMatch(instance -> instance.isWaitingForRegeneration(player));
    }

    /**
     * Moves a player into a lobby. A player coming from a different lobby is routed through the
     * hub first so the old lobby saves their run state before the new lobby touches the inventory.
     */
    public void join(Player player, GameInstance target) {
        GameInstance current = instance(player.getWorld());
        if (current == target) {
            target.select(player);
            return;
        }
        if (!mayJoin(player, target)) return;
        CompletableFuture<Boolean> preparation = target.prepare();
        if (!preparation.isDone()) {
            Messages.send(player, Messages.Type.INFO, target.displayName() + ": Die Welt wird vorbereitet …");
        }
        preparation.whenComplete((ready, error) -> {
            if (!player.isOnline()) return;
            if (error != null || !Boolean.TRUE.equals(ready)) {
                if (error != null) plugin.getLogger().warning(target.displayName()
                        + ": Welt konnte nicht vorbereitet werden: " + error.getMessage());
                Messages.send(player, Messages.Type.ERROR, target.displayName() + " ist gerade nicht verfügbar.");
                return;
            }
            GameInstance now = instance(player.getWorld());
            if (now == target) return;
            if (now != current || !mayJoin(player, target)) return;
            all.stream().filter(instance -> instance != target)
                    .forEach(instance -> instance.forgetPendingReturn(player.getUniqueId()));
            if (current != null) lobby.sendToLobby(player);
            target.select(player);
        });
    }

    private boolean mayJoin(Player player, GameInstance target) {
        if (target.state() == ChallengeState.REGENERATING) {
            Messages.send(player, Messages.Type.INFO,
                    target.displayName() + ": Die Welt wird gerade neu generiert.");
            return false;
        }
        return sessions.mayEnter(player, target.sessionKey(), target.displayName());
    }

    public void shutdown() {
        all.forEach(GameInstance::shutdown);
    }
}
