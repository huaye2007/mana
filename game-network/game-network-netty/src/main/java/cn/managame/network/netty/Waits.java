package cn.managame.network.netty;

import cn.managame.network.NetworkException;

import java.util.concurrent.*;

final class Waits {
    static void netty(io.netty.util.concurrent.Future<?> future, long deadline, String operation) {
        try {
            if (!future.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                throw new NetworkException(operation + " timed out");
            if (!future.isSuccess())
                throw new NetworkException(operation + " failed", future.cause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NetworkException(operation + " interrupted", e);
        }
    }
}
