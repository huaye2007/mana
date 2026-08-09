package cn.managame.dev.bootstrap;

import cn.managame.config.ConfigCenter;
import cn.managame.config.ConfigException;
import cn.managame.config.ConfigFactory;
import cn.managame.config.ConfigLayer;
import cn.managame.config.ConfigOptions;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冒烟测试：验证 game-dev 对 game-config（本地文件源）与 game-registry（memory 实现）
 * 的装配链路——即 Game.main 里 createConfigCenter / startRegistry 的同款用法。
 * 纯进程内，不开 socket、不连外部服务。
 */
class ConfigRegistryWiringTest {

    @Test
    void localConfigFileFeedsRegistryWiring(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("application.properties");
        Files.writeString(configFile, """
                game.service.name=wiring-test-server
                game.registry.type=memory
                game.registry.endpoints=wiring-test
                game.server.port=9100
                """);

        ConfigCenter configManager = ConfigFactory.open(ConfigOptions.builder("local")
                .resource(configFile.toString())
                .build());
        try {
            assertEquals("memory", configManager.snapshot().get("game.registry.type"));
            assertEquals(9100, configManager.snapshot().getInt("game.server.port", -1));

            try (ServiceRegistry registry = RegistryFactory.startRegistry(RegistryConfig.builder()
                    .type(configManager.snapshot().get("game.registry.type"))
                    .endpoints(configManager.snapshot().get("game.registry.endpoints"))
                    .build())) {
                registry.register(ServiceInstance.builder()
                        .name(configManager.snapshot().get("game.service.name"))
                        .address("127.0.0.1")
                        .port(configManager.snapshot().getInt("game.server.port", -1))
                        .build());

                List<ServiceInstance> discovered =
                        List.copyOf(registry.getInstances("wiring-test-server"));
                assertEquals(1, discovered.size());
                assertEquals(9100, discovered.get(0).getPort());
            }
        } finally {
            configManager.close();
        }
    }

    @Test
    void systemPropertyLayerOverridesTheLocalFileLikeGameMain(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("application.properties");
        Files.writeString(configFile, """
                game.server.port=9100
                game.db.password=from-file
                """);
        System.setProperty("game.server.port", "9200");

        // 与 Game.createConfigCenter 同款：文件兜底，-D 覆盖，环境变量再覆盖，密码必填。
        try (ConfigCenter configManager = ConfigFactory.open(ConfigOptions.builder()
                .layer(ConfigLayer.builder("local").resource(configFile.toString()).build())
                .systemProperties("game.")
                .environment("GAME_")
                .require("game.db.password")
                .build())) {
            assertEquals(9200, configManager.snapshot().getInt("game.server.port", -1));
            assertEquals("from-file", configManager.snapshot().require("game.db.password"));
        } finally {
            System.clearProperty("game.server.port");
        }
    }

    @Test
    void missingRequiredPasswordFailsStartup(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("application.properties");
        Files.writeString(configFile, "game.server.port=9100\n");

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigFactory.open(ConfigOptions.builder()
                .layer(ConfigLayer.builder("local").resource(configFile.toString()).build())
                .require("game.db.password")
                .build()));
        assertTrue(error.toString().contains("cannot open config center"));
    }

    @Test
    void memoryProviderIsOnRuntimeClasspath() {
        // game-registry-memory 以 runtime 依赖引入，SPI 必须能被 ServiceLoader 发现
        assertTrue(RegistryFactory.isAvailable("memory"));
        assertTrue(RegistryFactory.isAvailable("nacos"));
        assertTrue(RegistryFactory.isAvailable("etcd"));
    }
}
