package de.carl.minecraftchallenges.lobby;

import de.carl.minecraftchallenges.challenge.ChallengeKeepAlive;

/** A challenge lobby whose running round an admin can hold open with /keepalive (not SMP or speedrun). */
public interface KeepAliveChallenge extends GameInstance {
    ChallengeKeepAlive keepAlive();

    /** Saves the lobby's worlds and the data of every participant still inside. */
    void saveForKeepAlive();
}
