package de.carl.minecraftchallenges.npc;

import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.lobby.GameInstance;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;

/** Never starts a spectator session or allocates an unused custom challenge slot. */
public final class QuickJoin {
    private QuickJoin() { }

    public static GameInstance select(List<GameInstance> lobbies, Player player) {
        return select(lobbies);
    }

    public static GameInstance select(List<GameInstance> lobbies) {
        return lobbies.stream()
                .filter(QuickJoin::canJoin)
                .sorted(Comparator.comparingInt(GameInstance::onlineParticipantCount).reversed()
                        .thenComparingInt(GameInstance::lobbyId))
                .findFirst().orElse(null);
    }

    /** Rechecked after asynchronous world preparation, before teleporting the player. */
    public static boolean canJoin(GameInstance instance) {
        ChallengeState state = instance.state();
        // Standard IDLE slots are dormant waiting lobbies, prepared lazily on entry.
        boolean waiting = state == ChallengeState.WAITING || state == ChallengeState.COUNTDOWN
                || state == ChallengeState.IDLE && instance.gameType() != GameType.CUSTOM;
        return waiting && instance.onlineParticipantCount() < instance.maxPlayers();
    }

}
