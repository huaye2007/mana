# OGBS 容器集成规范 1.0

**[English](OGBS-Spring-1.0.md)** | [简体中文](OGBS-Spring-1.0.zh-CN.md)

本规范定义可选应用容器集成的语言无关职责，仓库由 game-spring 实现；配套 [Java 开发规范](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)。Runtime/Data 契约仍是行为依据，核心组件不要求容器。

本文件只覆盖容器额外带来的东西：发现业务声明、提供配置、注入已初始化对象、持有生命周期回调。组件之间的拼装——RPC 到 Runtime 的适配器、关闭预算、可观测性收集、业务路径前缀——属于[服务器组合](OGBS-Boot-1.0.zh-CN.md)，没有容器也存在；本集成只负责启用并接线它们，并且按 [B-COMPOSE-01](OGBS-Boot-1.0.zh-CN.md#1-这一层是什么)**不得**在其之上叠加自己的可观察行为。

## 1. 发现与装配

**S-REGISTER-01**：启动时在应用指定范围发现业务 Handler、事件监听器、HTTP 入口、周期任务对象及 Repository，在 Runtime/Data 构建时冻结。不根据类名推断鉴权、Domain 身份、Route Key、业务身份或序列化，均由应用策略明确提供。签名、重复注册或作用域非法时启动失败，不能静默忽略非法声明。

**S-REGISTER-02**：按对象身份将每个发现的单例注册一次，不为判断周期任务归属而初始化无关对象。初始化前无法识别类型的产物需明确注册可识别类型。对象不能同步依赖正在构造的同一个 Runtime，此循环依赖应拒绝启动。构建后的动态发现不属于此 Profile。

**S-DATA-01**：容器注入必须暴露 Data 持有的同一已初始化 Repository，存储初始化先于注入回调及业务访问，保留应用名称和限定符，沿用 Data 构造器/类型/身份要求。应用数据源由容器持有，Data 借用；已接纳 Runtime 工作仍需 Repository 时不能关闭 Data。

## 2. 生命周期与失败

**S-HTTP-01**：容器管理的 HTTP 装配将发现的 HTTP 端点接入普通业务消息相同的 Runtime 接纳、Domain 和 Route 规则。应用配置监听端口和可选的公共路径前缀；前缀映射本身属于 [B-HTTP-01](OGBS-Boot-1.0.zh-CN.md#5-business-path-prefix)，本集成只提供配置值并把映射安装在业务分发之前。配置提供大小/时间限制；无效配置或绑定失败导致应用启动失败，不静默禁用监听。不新增业务执行器。

**S-HTTP-02**：在业务注册和容器单例初始化完成后启动托管监听器。默认需要发现 HTTP 端点所属对象才启用，显式配置可以启用或禁用。容器持有监听器及其自行创建的资源，显式传入的网络线程组仍由调用方持有。在 S-CLOSE-01 的 Runtime 排空屏障之后、存储销毁之前关闭。执行排空不保证响应投递完成，也不覆盖未登记为 Runtime 工作的延迟响应；需要此边界的应用应登记其续接。启动失败释放托管监听器及容器持有的 Runtime 资源。底层 Network HTTP API 仍供自行装配传输的应用使用。

[HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java) 验证监听生命周期、路径和引用所有权，Java 绑定定义配置项及默认值；映射规则本身由 [HttpContextPathTest](../../game-boot/src/test/java/cn/managame/boot/http/HttpContextPathTest.java) 覆盖。

**S-CLOSE-01**：管理上下文停机时先停止 Runtime 接纳和未来调度，等待已登记任务及后续回调，再关闭 Runtime 及网络/数据依赖。保持 [RT-DRAIN](OGBS-Runtime-1.0.zh-CN.md#103-停止接纳等待排空与观察统计) 边界：送达、未登记回调与持久化是独立阶段。等待是有上界的：上界到达时（例如登记的回调永远没有完成或业务挂起），记录未完成的工作数量后仍关闭 Runtime，停机不会无限等待。此时仍在运行的工作被放弃，而不是被取消。

**S-CLOSE-02**：容器拥有触发点，而不是各个阶段。管理上下文的关闭事件启动 [B-CLOSE-01](OGBS-Boot-1.0.zh-CN.md#3-关闭预算) 的序列，S-CLOSE-01 的上界就是该预算推导出的排空时间，而不是另行配置的超时。集成**必须**同步运行这个序列，位于普通关闭监听器之前、以及排空所依赖资源被销毁之前，并且**必须**只处理自己的上下文。它**不得**重排、缩短或跳过某个阶段；需要不同切分的部署去配置预算。

例如已接纳的玩家 Handler 可在开始停机后继续更新 Repository，必须等待它及登记回调完成才能销毁 Repository，随后 Data 最终回写。玩家新请求在 Runtime 接纳处拒绝。禁止在该 Handler 自己的 Route 上关闭容器，否则会等待自身。

启动失败时，调用方持有资源仍由容器清理；Runtime 成功构建后按其规范转移执行器生命周期。容器集成不统一发送 TCP/RPC 响应，不安装业务调度器。默认事件绑定的有效期是整个进程，而容器工厂每个上下文执行一次，因此集成不做这件绑定；由应用在进程启动时绑定。

## 3. 验证与边界

Java 验证入口：[CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java)、[RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java)、[Repository 集成测试](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java)。代理策略和具体停机通知在 Java 绑定规定。这些测试未验证生产资源耗尽、驱动挂起、任意容器实现、跨进程停机或热注册。

<a id="4-optional-rpc-integration"></a>

## 4. 可选 RPC 接入

**S-RPC-01**：RPC 接入是选择性启用的，内容是用容器提供的输入装配 [B-RPC-01 至 B-RPC-03](OGBS-Boot-1.0.zh-CN.md#2-rpc-adapter) 的组合适配器：一个 Runtime、一个业务 codec、从配置读到的传输设置，以及从已声明扩展取得的有序组合钩子列表。集成在容器初始化之后启动 Node，在 S-CLOSE-01 排空之后停止它，使入站请求绝不会到达一个已消失的 Runtime。关于适配器的其他一切——codec 与协议查找、分发、call 与 reply 语义、钩子顺序、attach 失败后的清理——都属于组合，没有容器时完全相同。codec 的并发安全与拓扑策略由应用负责。集成不新增服务发现、重试、认证、可靠投递或统一 TCP/RPC send。

容器顺序是本条唯一新增的东西，而且它是可观察的：钩子按容器声明的顺序运行，所以必须位于最外层的路由扩展把自己声明在最后。Java API、配置与真实 TCP 验证见 [Java 规范](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)。

<a id="5-可观测性组合"></a>
<a id="5-observability-composition"></a>

## 5. 可观测性组合

**S-OBS-01**：可观测性接入是选择性启用的，内容是三个接线决定：哪些受管组件存在，于是为每个存在的组件在其固定前缀下发布一个读数源；存在哪些就绪条件，做法是为每个"会停止接受工作"的存在组件各贡献一条，并按顺序收集应用自己声明的条件；以及端点安装在哪里，即业务分发之前、任何已配置路径前缀之前。这三者的行为——前缀、具名条件、饱和时仍能作答、关闭期间的可见性、暴露格式——属于 [B-OBS-01 至 B-OBS-05](OGBS-Boot-1.0.zh-CN.md#4-observability-composition)，没有容器也不改变。

没有本接入时，组件仍然保留自己的计数，应用通过它们各自的快照 API 读取，或者自行发布。应用在容器之外构造的组件由它自己发布。

## 6. 已确认的取舍

| 决定 | 原因 | 重新考虑的触发条件 |
| --- | --- | --- |
| 容器层只承载容器关心的事 | 存在于某一个容器接线内部的拼装，对其他所有应用都不可用；把拼装移出去使它们变得可达，也让这一层薄到可以读完 | 出现第二个容器集成，并且需要本层仍然混在一起的某样东西 |
| 容器拥有关闭的触发点而非阶段 | 只有容器知道自己的上下文何时关闭；阶段本身与此毫无关系 | 仅凭此理由不再重新考虑 |
| 钩子顺序来自容器，钩子语义不来自容器 | 为已声明的扩展排序恰恰是容器擅长的事，也是适配器中唯一一处容器比列表表达得更好的部分 | 某个集成需要一种组合层无法表达为列表的顺序 |
