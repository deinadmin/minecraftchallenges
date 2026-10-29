package de.carl.minecraftchallenges.npc;

import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.lobby.GameInstance;

import java.util.Comparator;
import java.util.List;

/** Never starts a spectator session or allocates an unused custom challenge slot. */
public final class QuickJoin {
    private QuickJoin() { }

    public static GameInstance select(List<GameInstance> lobbies) {
        return lobbies.stream()
                .filter(instance -> instance.gameType() != GameType.CUSTOM || instance.state() != ChallengeState.IDLE)
                .filter(instance -> instance.state() == ChallengeState.WAITING
                        || instance.state() == ChallengeState.FINISHED || instance.state() == ChallengeState.IDLE)
                .filter(instance -> instance.onlineParticipantCount() < instance.maxPlayers())
                .sorted(Comparator.comparingInt(GameInstance::onlineParticipantCount).reversed()
                        .thenComparingInt(GameInstance::lobbyId))
                .findFirst().orElse(null);
    }
}
