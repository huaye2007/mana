package cn.managame.dev;

import cn.managame.dev.bootstrap.AnnotationScanner;
import cn.managame.dev.bootstrap.ForyMessageRegistrar;
import cn.managame.dev.bootstrap.GameJpaRepositoryRegistrar;
import cn.managame.dev.server.CustomTcpPipelineConfigurator;
import cn.managame.dev.server.GameHandler;
import cn.managame.dev.server.GameRouterManager;
import cn.managame.dev.server.GameTaskFailureReplier;
import cn.managame.dev.server.PlayerSessionManager;
import cn.managame.network.connection.ServerConnectionIdGenerator;
import cn.managame.network.server.NettyTcpServer;
import cn.managame.network.server.NetworkTcpServerConfig;
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
        AnnotationConfigApplicationContext applicationContext = createApplicationContext();

        // 扫描 game-jpa Repository 接口并注册为容器单例。必须在 refresh 之前完成，
        // 否则 @GameController 在 refresh 阶段注入 Repository 时容器里还没有对应 bean。
        // 模块顺序：先 MysqlSchemaModule 按实体补建表（UPDATE：只增不删），再 RdbCacheModule
        // 提供 RDB + 主键缓存；两者共用同一个 DataSource。
        DataSource dataSource = createDataSource();
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

        int port = resolvePort();
        NetworkTcpServerConfig serverConfig = new NetworkTcpServerConfig(port);
        NettyTcpServer nettyTcpServer = new NettyTcpServer(serverConfig, gameHandler,
                new ServerConnectionIdGenerator(100), new CustomTcpPipelineConfigurator());
        nettyTcpServer.start();

        scheduleOnlineReport(playerSessionManager);

        // 停机是一个有顺序的序列，收敛到单一 hook，不拆成多个并行 hook（JVM 并行执行会乱序）
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdown(nettyTcpServer, jpaContext, applicationContext), "game-shutdown"));
        logger.info("game server started, port={}", port);
    }

    /**
     * 停机序列：停定时器（不再产生新任务）→ 停网络（关连接、不再收请求）→
     * 排空执行器组（在途业务写完缓存/发起落库）→ 关 jpa（刷异步写队列到库）→ 关容器。
     */
    private static void shutdown(NettyTcpServer server, GameJpaContext jpaContext,
                                 AnnotationConfigApplicationContext applicationContext) {
        logger.info("game server shutting down");
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

    /** 创建 MySQL 数据源：连接参数优先 -D 系统属性，其次环境变量；密码无默认值，缺失即启动失败。 */
    private static DataSource createDataSource() {
        return MysqlDataSourceFactory.builder()
                .jdbcUrl(prop("game.db.url", "GAME_DB_URL", "jdbc:mysql://localhost:3306/test"))
                .username(prop("game.db.username", "GAME_DB_USERNAME", "root"))
                .password(requiredProp("game.db.password", "GAME_DB_PASSWORD"))
                .build();
    }

    /** 读取配置：优先 -D 系统属性，其次环境变量，最后默认值。 */
    private static String prop(String sysKey, String envKey, String defaultValue) {
        String value = System.getProperty(sysKey);
        if (value == null || value.isBlank()) {
            value = System.getenv(envKey);
        }
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    /** 读取必填配置：密码等敏感项不给默认值，缺失时报错退出而不是带着弱口令跑起来。 */
    private static String requiredProp(String sysKey, String envKey) {
        String value = prop(sysKey, envKey, null);
        if (value == null) {
            throw new IllegalStateException(
                    "missing required config: -D" + sysKey + " or env " + envKey);
        }
        return value;
    }

    /** 监听端口：优先 -Dgame.server.port，其次环境变量 GAME_SERVER_PORT，默认 8080。 */
    private static int resolvePort() {
        String value = System.getProperty("game.server.port");
        if (value == null || value.isBlank()) {
            value = System.getenv("GAME_SERVER_PORT");
        }
        return (value == null || value.isBlank()) ? 8080 : Integer.parseInt(value.trim());
    }
}
