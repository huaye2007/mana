package cn.managame.dev;

import cn.managame.dev.bootstrap.AnnotationScanner;
import cn.managame.dev.bootstrap.ForyMessageRegistrar;
import cn.managame.dev.bootstrap.GameJpaRepositoryRegistrar;
import cn.managame.dev.server.CustomTcpPipelineConfigurator;
import cn.managame.dev.server.GameHandler;
import cn.managame.dev.server.GameRouterManager;
import cn.managame.dev.server.GameTaskFailureReplier;
import cn.managame.dev.server.PlayerSessionManager;
import cn.managame.config.manager.GameConfigManager;
import cn.managame.config.starter.GameConfigStarter;
import cn.managame.network.connection.ServerConnectionIdGenerator;
import cn.managame.network.server.NettyTcpServer;
import cn.managame.network.server.NetworkTcpServerConfig;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.factory.RegistryType;
import cn.managame.registry.starter.GameRegistryStarter;
import cn.managame.jpa.rdb.cache.RdbCacheModule;
import cn.managame.jpa.rdb.mysql.MysqlDataSourceFactory;
import cn.managame.jpa.rdb.mysql.MysqlRdbExecutor;
import cn.managame.jpa.rdb.mysql.MysqlSchemaModule;
import cn.managame.jpa.starter.GameJpaContext;
import javax.sql.DataSource;
import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.GameController;
import cn.managame.runtime.command.CommandRegistry;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.exception.GameTaskExceptionHandlers;
import cn.managame.runtime.executor.DefaultExecutorGroup;
import cn.managame.runtime.executor.ExecutorGroupRegistry;
import cn.managame.runtime.executor.ExecutorGroups;
import cn.managame.runtime.monitor.GameTaskMonitors;
import cn.managame.runtime.timer.TimingWheel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;

public class Game {

    private static final Logger logger = LoggerFactory.getLogger(Game.class);

    private static final String BASE_PACKAGE = "cn.managame.dev";

    /** 在线人数统计打点间隔。 */
    private static final long ONLINE_REPORT_INTERVAL_MS = 60_000;

    public static void main(String[] args) {
        // 分层配置：命令行 > -D 系统属性 > 环境变量 > 本地 config/application.properties。
        // 暂时只接本地配置；后续要接 nacos/apollo 等远程源时在 createConfigManager 里补 remote 源即可。
        GameConfigManager configManager = createConfigManager(args);

        AnnotationConfigApplicationContext applicationContext = createApplicationContext();

        // 扫描 game-jpa Repository 接口并注册为容器单例。必须在 refresh 之前完成，
        // 否则 @GameController 在 refresh 阶段注入 Repository 时容器里还没有对应 bean。
        // 模块顺序：先 MysqlSchemaModule 按实体补建表（UPDATE：只增不删），再 RdbCacheModule
        // 提供 RDB + 主键缓存；两者共用同一个 DataSource。
        DataSource dataSource = createDataSource(configManager);
        GameJpaContext jpaContext = GameJpaRepositoryRegistrar.registerInto(
                applicationContext.getDefaultListableBeanFactory(), BASE_PACKAGE,
                MysqlSchemaModule.update(dataSource),
                RdbCacheModule.withExecutor(new MysqlRdbExecutor(dataSource)));

        applicationContext.refresh();

        registerExecutorGroups();
        registerAnnotatedBeans(applicationContext);
        // 默认 Fory 要求类注册，外网包体收发前先把消息 DTO 登记进去
        ForyMessageRegistrar.registerMessageTypes();
        // 任务失败统一回包：handler 异常/队列满丢弃都给客户端回错误码，不让请求方干等
        GameTaskFailureReplier failureReplier = new GameTaskFailureReplier();
        GameTaskExceptionHandlers.setHandler(failureReplier);
        GameTaskMonitors.setMonitor(failureReplier);

        PlayerSessionManager playerSessionManager = applicationContext.getBean(PlayerSessionManager.class);
        GameHandler gameHandler = new GameHandler(
                playerSessionManager,
                applicationContext.getBean(GameRouterManager.class));

        int port = resolvePort(configManager);
        NetworkTcpServerConfig serverConfig = new NetworkTcpServerConfig(port);
        NettyTcpServer nettyTcpServer = new NettyTcpServer(serverConfig, gameHandler,
                new ServerConnectionIdGenerator(100), new CustomTcpPipelineConfigurator());
        nettyTcpServer.start();

        // 端口就绪后再注册到注册中心，避免被发现却连不上；失败要停掉 netty，
        // 否则非 daemon 的事件循环线程会让 JVM 挂着不退出
        RegistryBundle registryBundle;
        try {
            registryBundle = startRegistry(configManager, port);
        } catch (RuntimeException e) {
            nettyTcpServer.stop();
            throw e;
        }

        scheduleOnlineReport(playerSessionManager);

        // 停机是一个有顺序的序列，收敛到单一 hook，不拆成多个并行 hook（JVM 并行执行会乱序）
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdown(registryBundle, nettyTcpServer, jpaContext, configManager, applicationContext),
                "game-shutdown"));
        logger.info("game server started, port={}", port);
    }

    /**
     * 停机序列：先下线注册中心（不再被发现、不再有新流量路由过来）→ 停定时器（不再产生新任务）→
     * 停网络（关连接、不再收请求）→ 排空执行器组（在途业务写完缓存/发起落库）→
     * 关 jpa（刷异步写队列到库）→ 关配置管理器 → 关容器。
     */
    private static void shutdown(RegistryBundle registryBundle, NettyTcpServer server, GameJpaContext jpaContext,
                                 GameConfigManager configManager,
                                 AnnotationConfigApplicationContext applicationContext) {
        logger.info("game server shutting down");
        try {
            // close 时 memory 实现按临时节点语义自动注销本进程注册的实例
            registryBundle.close();
        } catch (RuntimeException e) {
            logger.error("close registry failed", e);
        }
        TimingWheel.getInstance().shutdown();
        try {
            server.stop();
        } catch (RuntimeException e) {
            logger.error("stop netty server failed", e);
        }
        ExecutorGroupRegistry.getInstance().shutdownAll(5_000);
        try {
            jpaContext.close();
        } catch (RuntimeException e) {
            logger.error("close jpa context failed", e);
        }
        try {
            configManager.close();
        } catch (RuntimeException e) {
            logger.error("close config manager failed", e);
        }
        applicationContext.close();
        logger.info("game server shutdown complete");
    }

    private static AnnotationConfigApplicationContext createApplicationContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // 业务注解定义在 game-runtime，不带 @Component；宿主用 include filter 把它们纳入组件扫描，
        // 这样 controller/handler 也由容器统一管理（依赖注入 + 生命周期），无需手动 new。
        ClassPathBeanDefinitionScanner beanScanner = new ClassPathBeanDefinitionScanner(context, true);
        beanScanner.addIncludeFilter(new AnnotationTypeFilter(GameController.class));
        beanScanner.addIncludeFilter(new AnnotationTypeFilter(EventHandler.class));
        beanScanner.scan(BASE_PACKAGE);
        // refresh 延后到 game-jpa Repository 单例注册之后（见 main），保证注入时 bean 已就绪。
        return context;
    }

    /** 注册标准执行器组：登陆/玩家组按 routerKey 串行、适合虚拟线程。 */
    private static void registerExecutorGroups() {
        ExecutorGroupRegistry registry = ExecutorGroupRegistry.getInstance();
        registry.register(DefaultExecutorGroup.virtualThreads(ExecutorGroups.LOGIN, "login", 4, 10_000));
        registry.register(DefaultExecutorGroup.virtualThreads(ExecutorGroups.PLAYER, "player",
                Runtime.getRuntime().availableProcessors(), 10_000));
    }

    /** 扫描业务注解并注册到对应运行时注册表（须在 server 开始收流量前完成）。 */
    private static void registerAnnotatedBeans(AnnotationConfigApplicationContext context) {
        AnnotationScanner scanner = new AnnotationScanner(context);
        scanner.process(GameController.class, controller -> CommandRegistry.getInstance().register(controller));
        scanner.process(EventHandler.class, handler -> EventBus.getInstance().register(handler));
    }

    /**
     * 定时在线人数打点：展示 TimingWheel 的周期任务写法——一次性 schedule + 任务内自续期。
     * 任务在时间轮 worker 线程上执行，只做轻量打点；重活要投递到执行器组。
     */
    private static void scheduleOnlineReport(PlayerSessionManager playerSessionManager) {
        TimingWheel.getInstance().schedule(ONLINE_REPORT_INTERVAL_MS, new Runnable() {
            @Override
            public void run() {
                logger.info("online players: {}", playerSessionManager.onlineCount());
                TimingWheel.getInstance().schedule(ONLINE_REPORT_INTERVAL_MS, this);
            }
        });
    }

    /**
     * 装配分层配置管理器。源优先级：命令行 --key=value / -Dkey=value > JVM 系统属性 >
     * 环境变量 > 本地 config/application.properties（相对工作目录，缺失时静默忽略）。
     * 默认值不进配置栈、留在各读取点（见 cfg），保证旧式大写环境变量键仍能兜底。
     */
    private static GameConfigManager createConfigManager(String[] args) {
        return GameConfigStarter.builder()
                .args(args)
                .systemProperties(true)
                .environmentVariables(true)
                .localFile(GameConfigStarter.DEFAULT_LOCAL_FILE)
                .start();
    }

    /**
     * 创建注册中心并注册本服务实例。类型默认 memory（game-registry-memory 提供：进程内共享、
     * 无外部依赖，endpoints 只当 namespace 用）；切 zookeeper/etcd/nacos/consul 时
     * 配置 game.registry.type 并把 endpoints 换成真实地址即可。
     */
    private static RegistryBundle startRegistry(GameConfigManager config, int port) {
        String type = cfg(config, "game.registry.type", "GAME_REGISTRY_TYPE", RegistryType.MEMORY.type());
        String endpoints = cfg(config, "game.registry.endpoints", "GAME_REGISTRY_ENDPOINTS", "local");
        RegistryBundle bundle = GameRegistryStarter.builder()
                .type(type)
                .endpoints(endpoints)
                .start();
        ServiceInstance instance = ServiceInstance.builder()
                .name(cfg(config, "game.service.name", "GAME_SERVICE_NAME", "game-dev"))
                .address(cfg(config, "game.server.address", "GAME_SERVER_ADDRESS", "127.0.0.1"))
                .port(port)
                .build();
        bundle.getRegistry().register(instance);
        logger.info("registered to {} registry: {}", type, instance);
        return bundle;
    }

    /** 创建 MySQL 数据源：连接参数走配置管理器；密码无默认值，缺失即启动失败。 */
    private static DataSource createDataSource(GameConfigManager config) {
        return MysqlDataSourceFactory.builder()
                .jdbcUrl(cfg(config, "game.db.url", "GAME_DB_URL", "jdbc:mysql://localhost:3306/test"))
                .username(cfg(config, "game.db.username", "GAME_DB_USERNAME", "root"))
                .password(requiredCfg(config, "game.db.password", "GAME_DB_PASSWORD"))
                .build();
    }

    /** 读取配置：先按标准键查配置管理器（命令行/-D/本地文件已分层合并），再兜底旧式大写环境变量键。 */
    private static String cfg(GameConfigManager config, String key, String envKey, String defaultValue) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            value = config.get(envKey);
        }
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    /** 读取必填配置：密码等敏感项不给默认值，缺失时报错退出而不是带着弱口令跑起来。 */
    private static String requiredCfg(GameConfigManager config, String key, String envKey) {
        String value = cfg(config, key, envKey, null);
        if (value == null) {
            throw new IllegalStateException("missing required config: " + key
                    + " (config/application.properties, -D" + key + " or env " + envKey + ")");
        }
        return value;
    }

    /** 监听端口：game.server.port，默认 8080。 */
    private static int resolvePort(GameConfigManager config) {
        return Integer.parseInt(cfg(config, "game.server.port", "GAME_SERVER_PORT", "8080"));
    }
}
