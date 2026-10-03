# OGBS 容器集成规范 1.0

**[English](OGBS-Spring-1.0.md)** | [简体中文](OGBS-Spring-1.0.zh-CN.md)

本规范定义可选应用容器集成的语言无关职责，仓库由 game-spring 实现；配套 [Java 开发规范](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)。Runtime/Data 契约仍是行为依据，核心组件不要求容器。

## 1. 发现与装配

**S-REGISTER-01**：启动时在应用指定范围发现业务 Handler、事件监听器、HTTP 入口、周期任务对象及 Repository，在 Runtime/Data 构建时冻结。不根据类名推断鉴权、Domain 身份、Route Key、业务身份或序列化，均由应用策略明确提供。签名、重复注册或作用域非法时启动失败，不能静默忽略非法声明。

**S-REGISTER-02**：按对象身份将每个发现的单例注册一次，不为判断周期任务归属而初始化无关对象。初始化前无法识别类型的产物需明确注册可识别类型。对象不能同步依赖正在构造的同一个 Runtime，此循环依赖应拒绝启动。构建后的动态发现不属于此 Profile。

**S-DATA-01**：容器注入必须暴露 Data 持有的同一已初始化 Repository，存储初始化先于注入回调及业务访问，保留应用名称和限定符，沿用 Data 构造器/类型/身份要求。应用数据源由容器持有，Data 借用；已接纳 Runtime 工作仍需 Repository 时不能关闭 Data。

## 2. 生命周期与失败

**S-HTTP-01**：容器管理的 HTTP 装配将发现的 HTTP 端点接入普通业务消息相同的 Runtime 接纳、Domain 和 Route 规则。应用配置监听端口和可选统一 context path，端点声明仍使用此前缀下的相对路径。前缀按完整原始路径段匹配，保留 query，不静默解码或重定向其他路径。前缀外请求返回 404，不执行业务。配置提供大小/时间限制；无效配置或绑定失败导致应用启动失败，不静默禁用监听。不新增业务执行器。

**S-HTTP-02**：在业务注册和容器单例初始化完成后启动托管监听器。默认需要发现 HTTP 端点所属对象才启用，显式配置可以启用或禁用。容器持有监听器及其自行创建的资源，显式传入的网络线程组仍由调用方持有。在 S-CLOSE-01 的 Runtime 排空屏障之后、存储销毁之前关闭。执行排空不保证响应投递完成，也不覆盖未登记为 Runtime 工作的延迟响应；需要此边界的应用应登记其续接。启动失败释放托管监听器及容器持有的 Runtime 资源。底层 Network HTTP API 仍供自行装配传输的应用使用。

例如前缀 `/game` 将 `/game/status?id=7` 映射至已注册的 `/status`，保留相同的 `id=7` query；`/games/status` 和 `/game%2Fstatus` 不匹配。Handler 观察到前缀内的相对请求 URI。[HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java) 验证监听生命周期、路径和引用所有权，Java 绑定定义配置项及默认值。

**S-CLOSE-01**：管理上下文停机时先停止 Runtime 接纳和未来调度，等待已登记任务及后续回调，再关闭 Runtime 及网络/数据依赖。保持 [RT-DRAIN](OGBS-Runtime-1.0.zh-CN.md#103-停止接纳等待排空与观察统计) 边界：送达、未登记回调与持久化是独立阶段。遗失回调或业务挂起会使停机继续等待，本 Profile 不在固定期限后强制丢弃工作。

例如已接纳的玩家 Handler 可在开始停机后继续更新 Repository，必须等待它及登记回调完成才能销毁 Repository，随后 Data 最终回写。玩家新请求在 Runtime 接纳处拒绝。禁止在该 Handler 自己的 Route 上关闭容器，否则会等待自身。

启动失败时，调用方持有资源仍由容器清理；Runtime 成功构建后按其规范转移执行器生命周期。默认事件绑定冲突使启动失败，并关闭新构建 Runtime。容器集成不统一发送 TCP/RPC 响应，不安装业务调度器。

## 3. 验证与边界

Java 验证入口：[CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java)、[RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java)、[Repository 集成测试](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java)。代理策略和具体停机通知在 Java 绑定规定。这些测试未验证生产资源耗尽、驱动挂起、任意容器实现、跨进程停机或热注册。
