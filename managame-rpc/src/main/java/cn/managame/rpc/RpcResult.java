package cn.managame.rpc;

import cn.managame.runtime.Context;

/** What a call does with its result; runs as a task on the calling context's route. */
@FunctionalInterface
public interface RpcResult<R> {
    void onResult(Context context, R result) throws Exception;
}
