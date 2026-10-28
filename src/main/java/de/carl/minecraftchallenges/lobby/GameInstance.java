package de.carl.minecraftchallenges.lobby;

import de.carl.minecraftchallenges.challenge.ChallengeState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** One lobby of a game: owns its own worlds, players, scoreboard and round lifecycle. */
public interface GameInstance {
    GameType gameType();

    int lobbyId();

    /** Names of every world this lobby owns, whether currently loaded or not. */
    Set<String> worldNames();

    ChallengeState state();

    boolean isRoundActive();

    boolean isParticipant(Player player);

    int onlineParticipantCount();

    int maxPlayers();

    /** Every online player currently inside one of this lobby's worlds. */
    List<Player> presentPlayers();

    String durationName();

    /**
     * Loads the lobby's worlds and generates the waiting area without blocking the main thread.
     * Completes on the main thread with whether the lobby can be entered.
     */
    default CompletableFuture<Boolean> prepare() {
        return CompletableFuture.completedFuture(true);
    }

    void select(Player player);

    /** Hotbar items this lobby hands out itself (back to lobby, config, ready, skip …). */
    boolean isFixedItem(ItemStack item);

    boolean isWaitingForRegeneration(Player player);

    /** Drops a pending "return after world regeneration" for a player who joined another lobby. */
    void forgetPendingReturn(UUID player);

    /** Called once per second; unloads the lobby's worlds after they stayed empty for long enough. */
    void tickIdle(int idleUnloadSeconds);

    void shutdown();

    default String sessionKey() {
        return gameType().key() + "#" + lobbyId();
    }

    default String displayName() {
        return gameType().displayName() + " · Lobby " + lobbyId();
    }

    static String worldSuffix(int lobbyId) {
        return lobbyId == 1 ? "" : "_" + lobbyId;
    }
}
