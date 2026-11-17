package de.carl.minecraftchallenges.lobby;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeManager;
import de.carl.minecraftchallenges.challenge.ChallengeSessionService;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.custom.CustomChallenge;
import de.carl.minecraftchallenges.randomizer.RandomizerChallenge;
import de.carl.minecraftchallenges.npc.QuickJoin;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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

    private enum JoinMode { NORMAL, QUICK, INVITE }

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

    private GameInstance activeRun(Player player) {
        return all.stream().filter(instance -> instance.state() == ChallengeState.RUNNING
                && instance.isParticipant(player)).findFirst().orElse(null);
    }

    public boolean isInActiveRound(Player player) {
        if (sessions.hasActiveSession(player)) return true;
        GameInstance current = instance(player.getWorld());
        return current != null && current.isRoundActive() && current.isParticipant(player);
    }

    public GameInstance invitationLobby(Player player) {
        GameInstance current = instance(player.getWorld());
        return current != null && current.state() == ChallengeState.WAITING
                && current.gameType() != GameType.SPEEDRUN && current.isParticipant(player) ? current : null;
    }

    public void offerRejoin(Player player) {
        GameInstance run = activeRun(player);
        if (run == null) return;
        Component notice = Component.text("Du spielst gerade noch in „" + run.displayName() + "“. ")
                .append(Component.text("Klicke hier, um wieder beizutreten.", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/challenge rejoin")));
        Messages.send(player, Messages.Type.INFO, notice);
    }

    public void rejoin(Player player) {
        GameInstance run = activeRun(player);
        if (run == null) {
            Messages.send(player, Messages.Type.INFO, "Du hast gerade keine laufende Challenge.");
        } else if (lobby.isEditing(player)) {
            Messages.send(player, Messages.Type.INFO, "Beende zuerst den Lobby-Bearbeitungsmodus.");
        } else if (lobby.isLobbyWorld(player.getWorld()) || instance(player.getWorld()) == run) {
            join(player, run);
        } else {
            Messages.send(player, Messages.Type.INFO, "Geh zuerst mit /lobby zurück in die Lobby.");
        }
    }

    public boolean isWaitingForRegeneration(Player player) {
        return all.stream().anyMatch(instance -> instance.isWaitingForRegeneration(player));
    }

    /**
     * Moves a player into a lobby. A player coming from a different lobby is routed through the
     * hub first so the old lobby saves their run state before the new lobby touches the inventory.
     */
    public void join(Player player, GameInstance target) {
        join(player, target, JoinMode.NORMAL);
    }

    public void quickJoin(Player player, GameInstance target) {
        join(player, target, JoinMode.QUICK);
    }

    public void joinInvitation(Player player, GameInstance target) {
        join(player, target, JoinMode.INVITE);
    }

    private void join(Player player, GameInstance target, JoinMode mode) {
        if (!maySelect(player, target, mode)) return;
        World origin = player.getWorld();
        GameInstance current = instance(origin);
        if (current == target) {
            target.select(player);
            return;
        }
        if (!mayJoin(player, target)) return;
        CompletableFuture<Boolean> preparation = target.prepare();
        if (!preparation.isDone()) {
            Messages.send(player, Messages.Type.INFO, target.displayName() + ": Die Welt wird vorbereitet (bei Auslastung in der Warteschlange) …");
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
            if (mode == JoinMode.INVITE && player.getWorld() != origin) return;
            if (!maySelect(player, target, mode)) return;
            all.stream().filter(instance -> instance != target)
                    .forEach(instance -> instance.forgetPendingReturn(player.getUniqueId()));
            if (current != null || mode == JoinMode.INVITE && !lobby.isLobbyWorld(origin)) {
                lobby.sendToLobby(player);
                if (mode == JoinMode.INVITE && !lobby.isLobbyWorld(player.getWorld())) return;
            }
            target.select(player);
        });
    }

    private boolean maySelect(Player player, GameInstance target, JoinMode mode) {
        if (mode == JoinMode.NORMAL) return true;
        if (mode == JoinMode.QUICK) return mayQuickJoin(player, target);
        if (isInActiveRound(player)) {
            Messages.send(player, Messages.Type.ERROR, "Du bist gerade in einer aktiven Runde.");
        } else if (lobby.isEditing(player)) {
            Messages.send(player, Messages.Type.ERROR, "Beende zuerst den Lobby-Bearbeitungsmodus.");
        } else if (target.state() != ChallengeState.WAITING) {
            Messages.send(player, Messages.Type.ERROR, "Diese Challenge ist nicht mehr in der Wartephase.");
        } else if (instance(player.getWorld()) != target
                && target.onlineParticipantCount() >= target.maxPlayers()) {
            Messages.send(player, Messages.Type.ERROR, "Diese Challenge ist voll.");
        } else {
            return true;
        }
        return false;
    }

    private boolean mayQuickJoin(Player player, GameInstance target) {
        if (QuickJoin.canJoin(target)) return true;
        Messages.send(player, Messages.Type.INFO,
                "Diese Lobby ist inzwischen voll oder nicht mehr in der Wartephase. Bitte versuche Quick Join erneut.");
        return false;
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
