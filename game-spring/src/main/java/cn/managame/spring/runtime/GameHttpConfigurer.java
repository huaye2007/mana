package cn.managame.spring.runtime;

import cn.managame.network.http.HttpServerBuilder;

/** Optional native HTTP customization after property binding; Runtime dispatch remains installed. */
@FunctionalInterface
public interface GameHttpConfigurer {
    void configure(HttpServerBuilder builder);
}
