package de.carl.minecraftchallenges.npc;

import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.lobby.GameInstance;
import de.carl.minecraftchallenges.lobby.GameType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;

/** Dependency-free regression suite, invoked by Gradle check through npcPolicyTest. */
public final class QuickJoinTest {
    public static void main(String[] args) {
        var empty = lobby(1, 0, 8, ChallengeState.IDLE);
        var populated = lobby(2, 5, 8, ChallengeState.WAITING);
        var fewer = lobby(3, 2, 8, ChallengeState.WAITING);
        expect(populated, List.of(empty, fewer, populated), "Prefer most populated available lobby");
        expect(empty, List.of(lobby(2, 0, 8, ChallengeState.WAITING), empty), "Empty ties use first lobby number");
        expect(populated, List.of(populated, lobby(1, 8, 8, ChallengeState.WAITING)), "Skip full lobbies");
        expect(empty, List.of(empty, lobby(2, 6, 8, ChallengeState.RUNNING),
                lobby(3, 7, 8, ChallengeState.COUNTDOWN), lobby(4, 5, 8, ChallengeState.REGENERATING)),
                "Skip active rounds and regenerating worlds");
        expect(null, List.of(), "Empty registry opens menu");
        expect(null, List.of(lobby(1, 8, 8, ChallengeState.WAITING)), "All full opens menu");
        var finished = lobby(2, 2, 8, ChallengeState.FINISHED);
        expect(finished, List.of(empty, finished), "Finished rounds accept new players");
        var closed = new Fake(GameType.CUSTOM, 1, 0, 8, ChallengeState.IDLE);
        var custom = new Fake(GameType.CUSTOM, 2, 3, 8, ChallengeState.WAITING);
        expect(null, List.of(closed), "Closed custom slots have no Quick Join");
        expect(custom, List.of(closed, custom), "Join populated open custom slot");
        expect(null, List.of(closed, new Fake(GameType.CUSTOM, 2, 8, 8, ChallengeState.WAITING)),
                "Full custom slots disable Quick Join");
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, values) -> { throw new AssertionError("Unexpected player access: " + method); });
        var running = new Fake(null, 3, 8, 8, ChallengeState.RUNNING, true);
        expect(running, List.of(populated, empty, running), player, "Own running round takes priority even when full");
        var countdown = new Fake(null, 2, 2, 8, ChallengeState.COUNTDOWN, true);
        expect(countdown, List.of(populated, countdown), player, "Own countdown can be rejoined");
        expect(populated, List.of(populated, lobby(3, 4, 8, ChallengeState.RUNNING)), player,
                "Non-participants still use public lobby selection");
        var runningCustom = new Fake(GameType.CUSTOM, 2, 8, 8, ChallengeState.RUNNING, true);
        expect(runningCustom, List.of(closed, runningCustom), player, "Rejoin own running custom challenge");
        expect(null, List.of(new Fake(GameType.CUSTOM, 2, 4, 8, ChallengeState.RUNNING)), player,
                "Other players cannot Quick Join a running custom challenge");
        expect(empty, List.of(empty, new Fake(null, 2, 4, 8, ChallengeState.REGENERATING, true)), player,
                "Previous membership does not allow rejoining regeneration");
        expect(populated, List.of(populated, new Fake(null, 3, 0, 8, ChallengeState.FINISHED, true)), player,
                "Previous membership does not prioritize a completed round");
        System.out.println("17 NPC Quick Join regression checks passed.");
    }

    private static Fake lobby(int id, int players, int max, ChallengeState state) {
        return new Fake(null, id, players, max, state);
    }
    private static void expect(GameInstance expected, List<GameInstance> lobbies, String message) {
        if (QuickJoin.select(lobbies) != expected) throw new AssertionError(message);
    }
    private static void expect(GameInstance expected, List<GameInstance> lobbies, Player player, String message) {
        if (QuickJoin.select(lobbies, player) != expected) throw new AssertionError(message);
    }
    private record Fake(GameType gameType, int lobbyId, int onlineParticipantCount, int maxPlayers,
                        ChallengeState state, boolean participant) implements GameInstance {
        Fake(GameType type, int id, int players, int max, ChallengeState state) {
            this(type, id, players, max, state, false);
        }
        @Override public boolean isRoundActive() { return state == ChallengeState.RUNNING || state == ChallengeState.COUNTDOWN; }
        @Override public Set<String> worldNames() { return Set.of(); }
        @Override public boolean isParticipant(Player player) { return participant; }
        @Override public List<Player> presentPlayers() { return List.of(); }
        @Override public String durationName() { return "Test"; }
        @Override public void select(Player player) { throw new AssertionError("Selection must not join"); }
        @Override public boolean isFixedItem(ItemStack item) { return false; }
        @Override public boolean isWaitingForRegeneration(Player player) { return false; }
        @Override public void forgetPendingReturn(UUID player) { }
        @Override public void tickIdle(int seconds) { }
        @Override public void shutdown() { }
    }
}
