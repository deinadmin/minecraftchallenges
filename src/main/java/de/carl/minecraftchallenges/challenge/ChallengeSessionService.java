package de.carl.minecraftchallenges.challenge;

import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Prevents one player from belonging to two simultaneously active challenge runs. */
public final class ChallengeSessionService {
    private final Map<UUID, Session> activeSessions = new HashMap<>();

    public boolean mayEnter(Player player, String challengeKey, String challengeName) {
        Session active = activeSessions.get(player.getUniqueId());
        if (active == null || active.key().equals(challengeKey)) return true;
        Messages.send(player, Messages.Type.ERROR,
                "Du spielst noch in „" + active.displayName() + "“. Beende diese Runde, bevor du „"
                        + challengeName + "“ beitrittst.");
        return false;
    }

    public void claim(Collection<UUID> players, String challengeKey, String challengeName) {
        Session session = new Session(challengeKey, challengeName);
        players.forEach(uuid -> activeSessions.put(uuid, session));
    }

    public void release(Collection<UUID> players, String challengeKey) {
        players.forEach(uuid -> activeSessions.computeIfPresent(uuid,
                (ignored, session) -> session.key().equals(challengeKey) ? null : session));
    }

    private record Session(String key, String displayName) { }
}
