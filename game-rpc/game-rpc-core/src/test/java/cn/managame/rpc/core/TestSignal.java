package cn.managame.rpc.core;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A test-only, one-shot result latch. It does not dispatch callbacks or support continuation chains. */
final class TestSignal<T> {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean done;
    private T value;
    private Throwable failure;

    synchronized boolean complete(T result) {
        if (done) return false;
        value = result;
        done = true;
        completed.countDown();
        return true;
    }

    synchronized boolean completeExceptionally(Throwable error) {
        Objects.requireNonNull(error);
        if (done) return false;
        failure = error;
        done = true;
        completed.countDown();
        return true;
    }

    boolean isDone() { return done; }

    T get() throws InterruptedException, ExecutionException {
        if (!done) completed.await();
        return result();
    }

    T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit);
        if (!done && !completed.await(timeout, unit)) throw new TimeoutException("Test signal not completed");
        return result();
    }

    T join() {
        boolean interrupted = false;
        try {
            while (!done) {
                try { completed.await(); }
                catch (InterruptedException error) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure instanceof CancellationException cancelled) throw cancelled;
        if (failure instanceof CompletionException completion) throw completion;
        if (failure != null) throw new CompletionException(failure);
        return value;
    }

    private T result() throws ExecutionException {
        if (failure instanceof CancellationException cancelled) throw cancelled;
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            throw new ExecutionException(cause);
        }
        return value;
    }
}
