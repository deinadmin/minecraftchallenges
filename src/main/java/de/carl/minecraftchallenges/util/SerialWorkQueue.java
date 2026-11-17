package de.carl.minecraftchallenges.util;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Main-thread FIFO. A job owns the queue until its asynchronous preparation finishes. */
public final class SerialWorkQueue {
    private final Consumer<Runnable> schedule;
    private final Queue<Job<?>> waiting = new ArrayDeque<>();
    private Job<?> active;
    private boolean scheduled;
    private boolean closed;

    public SerialWorkQueue(Consumer<Runnable> schedule) {
        this.schedule = schedule;
    }

    public <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> work) {
        Job<T> job = new Job<>(work);
        if (closed) job.result.completeExceptionally(new CancellationException("World queue stopped"));
        else {
            waiting.add(job);
            scheduleNext();
        }
        return job.result;
    }

    private void scheduleNext() {
        if (closed || active != null || scheduled || waiting.isEmpty()) return;
        scheduled = true;
        // Always defer, including the first job, so callers can store the returned future first.
        schedule.accept(() -> {
            scheduled = false;
            if (closed) return;
            active = waiting.remove();
            start(active);
        });
    }

    private <T> void start(Job<T> job) {
        CompletableFuture<T> preparation;
        try {
            preparation = job.work.get();
            if (preparation == null) throw new IllegalStateException("World job returned no future");
        } catch (Exception error) {
            preparation = CompletableFuture.failedFuture(error);
        }
        preparation.whenComplete((value, error) -> {
            if (error == null) job.result.complete(value);
            else job.result.completeExceptionally(error);
            active = null;
            scheduleNext();
        });
    }

    public void close() {
        closed = true;
        CancellationException reason = new CancellationException("World queue stopped");
        if (active != null) active.result.completeExceptionally(reason);
        waiting.forEach(job -> job.result.completeExceptionally(reason));
        waiting.clear();
    }

    private static final class Job<T> {
        private final Supplier<CompletableFuture<T>> work;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private Job(Supplier<CompletableFuture<T>> work) { this.work = work; }
    }
}
