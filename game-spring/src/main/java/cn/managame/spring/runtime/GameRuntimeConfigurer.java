package cn.managame.spring.runtime;

import cn.managame.runtime.GameRuntimeBuilder;

/** Applied in Spring order after discovery, before the Runtime is built. */
@FunctionalInterface
public interface GameRuntimeConfigurer {
    void configure(GameRuntimeBuilder builder);
}
