package cn.managame.spring.runtime;

import cn.managame.network.http.HttpServer;
import cn.managame.network.http.HttpServerBuilder;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.http.HttpHandler;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.InetSocketAddress;

@Configuration(proxyBeanMethods = false)
class RuntimeHttpConfiguration {
    @Bean(destroyMethod = "close")
    HttpServer gameHttpServer(GameRuntime runtime, Environment environment,
                              ObjectProvider<GameHttpConfigurer> configurers) {
        int port = environment.getProperty("game.http.port", Integer.class, 8080);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("game.http.port must be 0..65535");
        String address = environment.getProperty("game.http.bind-address", "127.0.0.1");
        if (address.isBlank()) throw new IllegalArgumentException("game.http.bind-address must not be blank");
        var dispatch = new HttpContextPath(environment.getProperty("game.http.context-path", ""), runtime.http());
        var builder = HttpServer.builder().bindAddress(new InetSocketAddress(address, port))
                .maxContentLength(environment.getProperty("game.http.max-content-length", Integer.class,
                        HttpServerBuilder.DEFAULT_MAX_CONTENT_LENGTH))
                .maxInitialLineLength(environment.getProperty("game.http.max-initial-line-length", Integer.class,
                        HttpServerBuilder.DEFAULT_MAX_INITIAL_LINE_LENGTH))
                .maxHeaderSize(environment.getProperty("game.http.max-header-size", Integer.class,
                        HttpServerBuilder.DEFAULT_MAX_HEADER_SIZE))
                .readTimeoutMillis(environment.getProperty("game.http.read-timeout-millis", Long.class,
                        HttpServerBuilder.DEFAULT_READ_TIMEOUT_MILLIS));
        Integer backlog = environment.getProperty("game.http.backlog", Integer.class);
        if (backlog != null) {
            if (backlog <= 0) throw new IllegalArgumentException("game.http.backlog must be positive");
            builder.option(ChannelOption.SO_BACKLOG, backlog);
        }
        Boolean keepAlive = environment.getProperty("game.http.keep-alive", Boolean.class);
        if (keepAlive != null) builder.childOption(ChannelOption.SO_KEEPALIVE, keepAlive);
        Boolean tcpNoDelay = environment.getProperty("game.http.tcp-no-delay", Boolean.class);
        if (tcpNoDelay != null) builder.childOption(ChannelOption.TCP_NODELAY, tcpNoDelay);
        configurers.orderedStream().forEach(configurer -> configurer.configure(builder));
        return builder.asyncHandler(dispatch::dispatch).build();
    }

    @Bean
    HttpServerLifecycle gameHttpLifecycle(@Qualifier("gameHttpServer") HttpServer gameHttpServer,
                                           ApplicationContext context, Environment environment) {
        boolean enabled = environment.getProperty("game.http.enabled", Boolean.class,
                context.getBeanNamesForAnnotation(HttpHandler.class).length != 0);
        return new HttpServerLifecycle(gameHttpServer, enabled);
    }
}
