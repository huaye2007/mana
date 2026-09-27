# game-runtime

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Runtime Specification](../docs/ogbs/OGBS-Runtime-1.0.md) | [OGBS Runtime Java Development Specification](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md) |

组件行为与 Java 实现分别维护在上述两份规范中，本文提供使用入口。

Java 25 业务运行时。Maven 坐标为 `cn.managame:game-runtime`，按包划分内部功能模块。

## 目录与职责

```text
cn.managame.runtime
├── GameRuntime / GameRuntimeBuilder  公开入口与配置
├── context                          Context 体系、默认实现、只读 Contexts
├── route                            Domain、Key 提取与注册、RouteCallback
├── executor                         RouteExecutor SPI、绑定、平台/虚拟线程实现
├── protocol                         协议描述、注册及 Req → Res 关联
├── handler                          Handler 注解
├── event                            本地 Event、EventBus 及注解
├── timer                            一次性 Timer、Cron 注解及管理接口
├── time                             可替换的业务墙钟 GameTime
├── error                            Runtime 错误、处理器与分发异常
└── internal                         注册编译、Context 绑定、分发和时间调度实现
```

API 子包承载对应职责的类型；internal 负责组装，不属于应用支持接口。GameRuntimeBuilder 收集配置后交给 RuntimeCompiler 校验和编译，不再混放整个运行期实现。Context 绑定与 Timer/Cron 管理也独立于业务分发。

本次是包结构调整，未增加 Maven 发布单元。原来的 `cn.managame.runtime.HandlerContext` 等导入需改为 `cn.managame.runtime.context.HandlerContext`；`import cn.managame.runtime.*` 不会导入子包。仓库示例和测试已同步迁移。

## GameTime 与调度

```java
import cn.managame.runtime.time.GameTime;
import java.time.*;

GameTime.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
long now = GameTime.currentTimeMillis();
LocalDateTime local = GameTime.now(ZoneId.of("Asia/Shanghai"));
runtime.cron().rescheduleAll();
GameTime.resetClock();
```

GameTime 是进程级业务墙钟；setClock/resetClock 不自动改变任何现存调度。测试应在 finally 或 AfterEach 恢复时钟。RuntimeTimer 仍按真实经过的 delay 触发。绝对业务 deadline 由应用自行换算成 Duration，需要重算时由业务 cancel + schedule。

Cron 基于当前 GameTime 计算下一次时间，再使用 RuntimeTimer。按声明类与方法名管理：

```java
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
runtime.cron().rescheduleAll();
```

SystemCron 是应用定义的 Cron 类。cancel 停止后续周期；reschedule 可以恢复取消的项，rescheduleAll 包含全部注册项。已经开始的方法可以完成，旧代任务不会覆盖新调度。Cron 每轮方法结束后计算下一轮，异常或接纳失败不重试当轮，但继续未来周期。

## 文档与验证

- [OGBS Runtime 规范](../docs/ogbs/OGBS-Runtime-1.0.md)
- [Java 25 开发规范](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md)
- [GameTime](src/main/java/cn/managame/runtime/time/GameTime.java)
- [CronScheduler](src/main/java/cn/managame/runtime/timer/CronScheduler.java)
- RPC / Runtime 完整集成示例尚未实现，当前接入示例见 Java 开发规范。

根目录运行 `mvn -pl game-runtime -am test`；拆包后的完整接入验证运行 `mvn clean verify`。
