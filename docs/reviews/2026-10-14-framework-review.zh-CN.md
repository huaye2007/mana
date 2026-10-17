# 全框架审查与修改记录（2026-10-14）

[English](2026-10-14-framework-review.md) | **[简体中文](2026-10-14-framework-review.zh-CN.md)**

> 非规范记录。现行契约以各组件规范为准；本文记录本次审查的发现、用户确认的决定、已做修改与暂缓项。

## 1. 范围

对 game-core、game-network、game-rpc、game-router、game-runtime、game-data、game-spring 按可维护性、可读性、简洁性与性能审查。审查清单分三类：需要修改（正确性或契约风险）、性能优化、可维护性与可读性。

## 2. 需要修改：决定与结果

| # | 发现 | 决定 | 结果 |
| --- | --- | --- | --- |
| 1 | 调用超时的 onFail 在计时器线程执行，Javadoc 与 Java 规范的说法互相矛盾 | 保持在计时器线程通知，由业务在 onFail 中把处理转到自己的线程 | 统一 RpcHandler Javadoc 与 RPC Java 规范 |
| 2 | Router 写死 4 MiB 帧上限，与 RpcNodeBuilder.maxFrameSize 脱节 | 只保留 maxFrameSize 一处配置 | 删除 RouterWire.MAX_FRAME/MAX_INNER；新增 `RpcNode.maxFrameSize()`，Router 由它计算内层上限 |
| 3 | Router 硬编码 RPC 帧偏移 | 修改 | RpcWire 公开帧布局常量，Router 引用 |
| 4 | 保存线程读取业务仍在修改的实体 | 不做快照；集合字段使用线程安全集合 | Data 规范与 Java 规范补充指引 |
| 5 | 日志队列无界，且 `size()` 为 O(n) | 修改 | `logQueueCapacity`（默认 100,000）；满时 insert 返回 false 并报告；计数器 O(1)；新增 DataStats.droppedLogs |
| 6 | flush 失败标记永久生效 | 修改 | flush 只报告本轮丢弃；close 仍报告生命周期内的丢弃 |
| 7 | 过载拒绝异常带完整栈 | 修改 | RuntimeDispatchException 不采集栈 |
| 8 | GameRpc 同步失败既抛异常又回调 UNAVAILABLE | 修改 | 新增 `CapturedRouteCallback.cancel()`，同步失败只抛异常 |
| 9 | RPC 待完成调用无上限 | 修改（按 Peer） | `maxPendingCalls`（默认 65,536），超出时 onFail(UNAVAILABLE)；R-SEND-04 由 Node 级改为 Peer 级，避免一个慢 Peer 占满所有容量 |
| 10 | 缓存在 CHM 锁内同步查库 | 修改 | 加载移到锁外并按 Key 合并；加载中修改先等待；淘汰竞争时重载。加载按设计保持同步（D-MODEL-01）；本行最初声称的异步访问方法从未加入，也不应加入（2026-10-17 更正） |
| 11 | HTTP 请求与结果的 Jackson 配置不一致 | 修改 | 共享 JsonSupport 配置 |
| 12 | RpcWire 注释写 v1，实际为 v2 | 修改 | 已更正 |

## 3. 性能优化

- `isPeerConnected` 去掉 Stream；ServiceRouting 选 Router 两遍扫描，不分配数组。
- RpcNode 的 Peer 表改为写时复制的 IntObjectHashMap，发送按原始 int 查找；去掉 hasCandidate 预扫描；被动 Peer 回收先做无锁判断。
- 新增 `NetworkTransports`：Linux 上自动使用 Epoll，channel 类型与 group 匹配；RpcNodeBuilder 支持外部 EventLoopGroup。
- VirtualThreadRouteExecutor 改为无锁 Mailbox，去掉 Caffeine 依赖与空闲缓存。
- Runtime 统计改用 LongAdder；`executionTiming(false)` 可关闭计时。
- Runtime 定时器改为 HashedWheelTimer（10 ms tick）。
- 无订阅者的事件不再提交 Route 任务。
- HTTP 请求体直接从 ByteBuf 解析；`GameRpcCodec` 增加 ByteBuf 默认方法。
- MySQL 日志表名后缀格式器预编译，标识符校验不用正则，INSERT 按表缓存。
- Data 写入准入由读写锁改为原子计数。
- 文档写明 `rewriteBatchedStatements=true`，demo 已设置。
- GameRouter 按服务建立索引，广播只访问目标服务的 Node。
- CronSchedule 用 nextSetBit 跳到下一个合法的时、分、秒。

未改：INSERT/UPDATE 合并为 upsert。现有契约要求 UPDATE 未命中行和重复 INSERT 都报告失败，改成 upsert 会掩盖丢失的行，因此保留。

## 4. 可维护性与可读性

- RpcNode 拆出 HandshakeGuard（鉴权与防重放）与 PeerTable；Peer 角色由 `outbound`/`detached` 明确表示，不再用 `target == null`；入站握手绑定不再在 CHM compute 内执行。
- RuntimeCompiler 拆为 HandlerCompiler、EventCompiler、HttpCompiler 与 Reflection，Builder 传入 RuntimeSettings。
- GameRuntime 便利重载改为接口默认方法，实现只保留一条分发路径。
- NetworkServer 与 HttpServer 共用 `ListenerLifecycle`；HttpServerTransport 的三个请求阶段标志改为 Exchange 枚举。
- 新增 `FrameworkErrorCodes.name/describe`；`RouteCallback.onFail(code, cause)` 可携带异常原因。
- 清理全限定类名，并为日志补充 Node 与对端信息。
- 构建：`quality` profile（仅主源码的 Checkstyle）、`errorprone` profile、GitHub Actions CI，以及 game-benchmark（JMH）。不引入自动格式化工具：palantir-java-format 试过后放弃，它会把几乎每个文件的密排布局重排。保留的 Checkstyle 规则都是排版之外的检查；LineLength、OneStatementPerLine、NewlineAtEndOfFile 为何不启用记在 `config/checkstyle.xml`。

暂缓：

- `Connection.write(message, flush)` 与 `flush()` 的批量写：加过又去掉了，因为框架和 demo 都没有一次 flush 写多条的调用点，没有使用者的公开 API 也无法验证。RPC pipeline 已用 Netty 的 FlushConsolidationHandler 合并 flush，应用需要批量时同样可以自己装一个。等到真有下行路径需要向单连接推送大量小包时再考虑。
- 未被引用的帧布局常量 `RpcWire.VERSION`、`RpcWire.REQUEST_COMMAND_OFFSET`，以及 `NetworkTransports.epollAvailable()`：按同样的理由删除；Router 使用的其余偏移保留，传输选择保持内部实现。
- 带类型的回调附件：Router 在同一 Handler 中同时处理两类回调，泛型化后仍需 instanceof，收益不足。
- Runtime 改为返回状态码的 API：与第 3 条的入口收敛冲突；先用无栈异常解决性能问题。
- 拆分 game-runtime-http 模块：去掉 Caffeine 后剩余依赖是 Jackson 与 network；拆分需要改动编译器扩展点与 Spring 装配，留作单独任务。
- 测试中的 sleep：剩余的用于时间相关行为（缓存过期、Router 预热、确认某事未发生），或是带截止时间的轮询，无法用 latch 替代。

## 5. 验证状态

审查环境中未能编译（安全策略不允许联网构建），改动在之后补做了编译与测试：game-core、game-network、game-rpc、game-router、game-runtime、game-data、game-spring、game-demo 的 `mvn test` 全部通过 —— 357 个测试，无失败，2 个跳过（DatabaseIntegrationTest 需要真实 MySQL）。game-benchmark 未覆盖，构建它需要 JMH 依赖。

尚未执行：样式闸门（`mvn -Pquality -DskipTests verify`）。其 Checkstyle 规则是对照当前源码挑选的，但 Checkstyle 本身还没有跑过。
