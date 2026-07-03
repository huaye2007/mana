package com.github.huaye2007.mana.jpa.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.demo.domain.PlayerAccount;
import com.github.huaye2007.mana.jpa.demo.executor.InMemoryDocExecutor;
import com.github.huaye2007.mana.jpa.demo.executor.InMemoryRdbExecutor;
import com.github.huaye2007.mana.jpa.demo.repository.PlayerAccountRepository;
import com.github.huaye2007.mana.jpa.demo.repository.PlayerProfileRepository;
import com.github.huaye2007.mana.jpa.docdb.DocdbModule;
import com.github.huaye2007.mana.jpa.rdb.RdbModule;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbEntityMetadata;
import com.github.huaye2007.mana.jpa.starter.GameJpaScan;
import com.github.huaye2007.mana.jpa.starter.GameJpaBootstrap;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link GameJpaBootstrap#scanPackages} 端到端:扫描包 -> 复用各模块 SPI 识别实体与 Repository
 * -> 实例化。RDB 与 DocDB 两个模块同时安装,证明扫描器自动覆盖所有已装模块、不硬编码类型。
 */
public class GameJpaScanPackagesTest {

    @Test
    public void scanPackagesAutowiresEntitiesAndRepositoriesAcrossModules() {
        InMemoryRdbExecutor rdb = new InMemoryRdbExecutor();
        InMemoryDocExecutor doc = new InMemoryDocExecutor();

        GameJpaScan scan = new GameJpaBootstrap()
                .install(RdbModule.withExecutor(rdb))
                .install(DocdbModule.withExecutor(doc))
                .routingStrategy(new ModuloRoutingStrategy())
                .scanPackages("com.github.huaye2007.mana.jpa.demo.domain", "com.github.huaye2007.mana.jpa.demo.repository");

        try {
            // 两个模块的 Repository 接口都被自动发现并实例化。
            assertTrue(scan.repositories().containsKey(PlayerAccountRepository.class));
            assertTrue(scan.repositories().containsKey(PlayerProfileRepository.class));

            // 扫包识别 @Entity,实体元数据已注册到上下文。
            assertTrue(scan.context().metadataRegistry()
                    .get(PlayerAccount.class, RdbEntityMetadata.class).isPresent());

            // 实例可用:经 GameJpaContext 代理真正落到 InMemory executor。
            PlayerAccountRepository accounts = scan.repository(PlayerAccountRepository.class);
            assertNotNull(accounts);
            PlayerAccount account = new PlayerAccount(1001L, 101, "knight-1001", 30, 9_000);
            accounts.insert(account);
            assertSame(account, accounts.findById(1001L, 101));
        } finally {
            scan.context().close();
        }
    }

    private static class ModuloRoutingStrategy implements RoutingStrategy {
        @Override
        public String resolveDataSource(String logicalName, Object routingKey) {
            return "game_ds_" + Math.floorMod(((Number) routingKey).intValue(), 2);
        }

        @Override
        public String resolvePhysicalName(String logicalName, Object routingKey) {
            return logicalName + "_" + String.format("%02d", Math.floorMod(((Number) routingKey).intValue(), 4));
        }
    }
}
