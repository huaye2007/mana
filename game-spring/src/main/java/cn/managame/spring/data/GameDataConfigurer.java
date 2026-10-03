package cn.managame.spring.data;

import cn.managame.data.GameDataBuilder;

/** Applied in Spring order after MySQL and Repository discovery, before build. */
@FunctionalInterface
public interface GameDataConfigurer {
    void configure(GameDataBuilder builder);
}
