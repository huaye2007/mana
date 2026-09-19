package cn.managame.network.testsupport;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One-shot observation for tests; no callback registration or continuation execution. */
public final class TestSignal<T> {
    private final CountDownLatch ready = new CountDownLatch(1);
    private T value;
    private Throwable failure;

    public synchronized boolean complete(T value) {
        if (isDone()) return false;
        this.value = value;
        ready.countDown();
        return true;
    }

    public synchronized boolean completeExceptionally(Throwable failure) {
        if (isDone()) return false;
        this.failure = Objects.requireNonNull(failure);
        ready.countDown();
        return true;
    }

    public boolean isDone() {
        return ready.getCount() == 0;
    }

    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (!ready.await(timeout, unit)) throw new TimeoutException("Test signal was not received");
        if (failure != null) throw new ExecutionException(failure);
        return value;
    }
}
