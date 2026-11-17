package de.carl.minecraftchallenges.util;

import net.kyori.adventure.text.Component;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Regression checks for asynchronous ordering and preserving the displayed scoreboard. */
public final class PerformancePolicyTest {
    private static int checks;
    public static void main(String[] args) {
        queueOrderingAndRecovery();
        queueShutdown();
        scoreboardChanges();
        System.out.println(checks + " performance policy checks passed.");
    }

    private static void queueOrderingAndRecovery() {
        Queue<Runnable> ticks = new ArrayDeque<>();
        SerialWorkQueue queue = new SerialWorkQueue(ticks::add);
        List<String> started = new ArrayList<>();
        CompletableFuture<Boolean> loading = new CompletableFuture<>();
        var first = queue.submit(() -> { started.add("first"); return loading; });
        var failing = queue.submit(() -> { started.add("failure"); throw new IllegalStateException("bad world"); });
        var last = queue.submit(() -> { started.add("last"); return CompletableFuture.completedFuture(true); });
        expect(started.isEmpty(), "World jobs must not start synchronously before the caller stores its future");
        ticks.remove().run();
        expect(started.equals(List.of("first")) && ticks.isEmpty(), "Async generation must retain the queue until ready");
        loading.complete(true);
        expect(first.join() && !last.isDone(), "Completing one world must not synchronously create the next");
        ticks.remove().run();
        expect(failing.isCompletedExceptionally(), "World creation exceptions must reach the caller");
        ticks.remove().run();
        expect(last.join() && started.equals(List.of("first", "failure", "last")), "A failed job must not stall later worlds");
        var asyncFailure = new CompletableFuture<Boolean>();
        var failed = queue.submit(() -> asyncFailure);
        var following = queue.submit(() -> CompletableFuture.completedFuture(7));
        ticks.remove().run();
        asyncFailure.completeExceptionally(new IllegalStateException("chunk failed"));
        ticks.remove().run();
        expect(failed.isCompletedExceptionally() && following.join() == 7, "Async chunk failures must also release the queue");
        var nullJob = queue.submit(() -> null);
        ticks.remove().run();
        var afterNull = queue.submit(() -> CompletableFuture.completedFuture(1));
        ticks.remove().run();
        expect(nullJob.isCompletedExceptionally() && afterNull.join() == 1, "A null job future must not wedge the queue");
    }

    private static void queueShutdown() {
        Queue<Runnable> ticks = new ArrayDeque<>();
        SerialWorkQueue queue = new SerialWorkQueue(ticks::add);
        CompletableFuture<Boolean> loading = new CompletableFuture<>();
        var active = queue.submit(() -> loading);
        AtomicInteger started = new AtomicInteger();
        var waiting = queue.submit(() -> { started.incrementAndGet(); return CompletableFuture.completedFuture(true); });
        ticks.remove().run();
        queue.close();
        loading.complete(true);
        expect(active.isCompletedExceptionally() && waiting.isCompletedExceptionally(), "Shutdown must settle active and queued callers");
        expect(started.get() == 0 && ticks.isEmpty(), "Shutdown must not create queued worlds");
        expect(queue.submit(() -> CompletableFuture.completedFuture(true)).isCompletedExceptionally(), "A stopped queue must reject new requests");
    }

    private static void scoreboardChanges() {
        AtomicReference<Component> title = new AtomicReference<>(Component.text("Waiting"));
        AtomicInteger titleWrites = new AtomicInteger();
        AtomicInteger scoreWrites = new AtomicInteger();
        AtomicInteger value = new AtomicInteger();
        AtomicReference<Boolean> scoreSet = new AtomicReference<>(false);
        Score score = (Score) Proxy.newProxyInstance(Score.class.getClassLoader(), new Class<?>[] {Score.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isScoreSet" -> scoreSet.get();
                    case "getScore" -> value.get();
                    case "setScore" -> { value.set((int) args[0]); scoreSet.set(true); scoreWrites.incrementAndGet(); yield null; }
                    default -> throw new AssertionError(method);
                });
        Objective objective = (Objective) Proxy.newProxyInstance(Objective.class.getClassLoader(), new Class<?>[] {Objective.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "displayName" -> {
                        if (args == null || args.length == 0) yield title.get();
                        title.set((Component) args[0]); titleWrites.incrementAndGet(); yield null;
                    }
                    case "getScore" -> score;
                    default -> throw new AssertionError(method);
                });
        ScoreboardUpdates.title(objective, Component.text("Waiting"));
        ScoreboardUpdates.title(objective, Component.text("Running: 59"));
        ScoreboardUpdates.title(objective, Component.text("Running: 59"));
        expect(titleWrites.get() == 1 && title.get().equals(Component.text("Running: 59")), "Only changed titles are written; timers still update");
        ScoreboardUpdates.score(objective, "Player", 0);
        ScoreboardUpdates.score(objective, "Player", 0);
        ScoreboardUpdates.score(objective, "Player", 1);
        expect(scoreWrites.get() == 2 && value.get() == 1, "Initial zero scores must appear and changed scores must update");
        scoreSet.set(false);
        ScoreboardUpdates.score(objective, "Player", 1);
        expect(scoreWrites.get() == 3, "An externally reset score must be restored even if its value matches");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
