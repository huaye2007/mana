package cn.managame.runtime.execution;

import cn.managame.runtime.route.Route;

import java.util.Objects;
import java.util.function.*;
/** Explicit route override, or callback creation outside a handler. */
public record CallbackDefinition<T>(Route route, Consumer<? super T> onSuccess, Consumer<? super Throwable> onFail) {
    public CallbackDefinition { Objects.requireNonNull(route); Objects.requireNonNull(onSuccess); }
}
