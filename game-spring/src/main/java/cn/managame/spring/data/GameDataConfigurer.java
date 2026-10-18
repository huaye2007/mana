package cn.managame.spring.data;

import cn.managame.data.GameDataBuilder;

/**
 * Applied in Spring order after source and Repository discovery and before build, so it can still redirect a
 * repository the integration registered: a registration that names a backend replaces one that did not.
 */
@FunctionalInterface
public interface GameDataConfigurer {
    void configure(GameDataBuilder builder);
}
