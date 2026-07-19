package cn.managame.jpa.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.demo.domain.PlayerAccount;
import cn.managame.jpa.demo.executor.InMemoryDocExecutor;
import cn.managame.jpa.demo.executor.InMemoryRdbExecutor;
import cn.managame.jpa.demo.repository.PlayerAccountRepository;
import cn.managame.jpa.demo.repository.PlayerProfileRepository;
import cn.managame.jpa.docdb.DocdbModule;
import cn.managame.jpa.rdb.RdbModule;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.starter.GameJpaScan;
import cn.managame.jpa.starter.GameJpaBootstrap;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link GameJpaBootstrap#scanPackages} 端到端:扫描包 -> 复用各模块 SPI 识别实体与 Repository
 * -> 实例化。RDB 与 DocDB 两个扩展同时启用,证明扫描器自动覆盖所有已启用扩展、不硬编码类型。
 */
public class GameJpaScanPackagesTest {

    @Test
    public void scanPackagesAutowiresEntitiesAndRepositoriesAcrossModules() {
        InMemoryRdbExecutor rdb = new InMemoryRdbExecutor();
        InMemoryDocExecutor doc = new InMemoryDocExecutor();

        GameJpaScan scan = new GameJpaBootstrap()
                .use(RdbModule.withExecutor(rdb))
                .use(DocdbModule.withExecutor(doc))
                .routingStrategy(new ModuloRoutingStrategy())
                .scanPackages("cn.managame.jpa.demo.domain", "cn.managame.jpa.demo.repository");

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
