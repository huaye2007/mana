package cn.managame.rpc.core;

import cn.managame.network.Connection;

/** Internal diagnostic sink; creates no event object when only counters are enabled. */
@FunctionalInterface
interface RpcObserver {
    void observe(
            String event,
            Integer peer,
            Integer requestId,
            Connection connection,
            Throwable failure);

    default void observe(String event, Throwable failure) {
        observe(event, null, null, null, failure);
    }
}
