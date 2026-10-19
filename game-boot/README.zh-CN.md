# game-boot

[English](README.md) | **[简体中文](README.zh-CN.md)**

组合层：把能力组件变成一个可运行服务器的那些拼装，不依赖任何容器。Java 25，依赖 game-runtime 与 game-data，game-rpc 为可选依赖。

能力组件不应了解自己的邻居——Runtime 不该知道 RPC 线格式，RPC 不该知道 Route，Network 不该知道读数契约——所以必须有人来拼装它们，而本模块是唯一被允许同时依赖多个组件的模块。这里的一切都是普通对象，由应用自己构造、持有并关闭。[game-spring](../game-spring/README.zh-CN.md) 正是这些类型之上的一层薄 Spring 适配，因此不使用容器的应用用同一份代码得到同样的行为。

## RPC 到 Runtime

显式添加 game-rpc，实现线程安全的 `GameRpcCodec`，然后构造适配器：

```java
var builder = RpcNode.builder().nodeId(1).bindAddress(new InetSocketAddress("127.0.0.1", 9100));
try (var runtime = GameRuntimeBuilder.builder()./* … */build();
     var rpc = new GameRpc(runtime, codec, builder, List.of(routingConfigurer))) {
    rpc.node().start();
    rpc.node().addPeer(2, peerAddress, 1);
}
```

构造器就是完整的拼装：它自己按列表顺序调用每个 `GameRpcConfigurer` 钩子——`configure`、`decorate`、build、`attach`——`close()` 则按逆序 detach 后关闭 Node。`attach` 失败时 detach 已 attach 的部分并关闭这个尚未启动的 Node。入站帧经 Runtime 协议注册表解码后进入 `dispatchRpc`，由 Handler 声明选择 Domain。`rpc.reply(response)` 回答当前 RPC 上下文；在 Route 上调用 `rpc.call(..., RouteCallback)` 会把完成回调带回同一条 Route 并参与排空。完整契约见 [Boot §2](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#2-rpc-adapter)。

## 就绪与读数

```java
var registry = new MetricsRegistry();
registry.publish("runtime", FrameworkMetrics.of(runtime));
var endpoints = new ObservabilityEndpoints(registry, checks, "/metrics", "/readiness");
httpBuilder.asyncHandler(endpoints.wrap(new HttpContextPath("/game", runtime.http()))::dispatch);
```

`FrameworkMetrics.of(...)` 把各组件的快照记录适配为 Core 的 `MetricsSource`，于是组件永不了解读数契约，game-network 也保持不依赖任何其他框架模块。把每个存在的组件发布在固定前缀 `runtime`、`data`、`rpc`、`network` 之下，一块看板就能读取本框架的任意部署。`/metrics` 把每条读数渲染为一行 `name value`；`/readiness` 返回 200 `ready`，或 503 并指名未成立的 `ReadinessCheck` 条件。两者都在任何路径前缀之前匹配，并在 HTTP 线程上、所有 Route 执行器之外作答，因此饱和或正在排空的实例仍能回答。完整契约见 [Boot §4](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#4-可观测性)。

## 关闭预算

`ShutdownBudget.of(totalMillis, leadMillis, flushReserveMillis)` 是一份声明好的预算，切分为关闭按顺序运行的各阶段——退出轮转、排空已准入工作、刷写最后的变更。总预算被配置、排空时间由推导得出，总和因此不会漂移；使排空无剩余时间的切分在配置处就失败。默认总预算 25 000 毫秒落在 Kubernetes 宽限期默认的 30 秒之下。`drain()` 是算出来的而不是存起来的，因此没有调用方能构造出各部分与总和矛盾的预算。无论由谁运行这个序列——容器的关闭事件、信号处理器，还是你自己的 `main`——观察到的阶段都相同。完整契约见 [Boot §3](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#3-关闭预算)。

## 业务路径前缀

`HttpContextPath` 把一个公共前缀映射到并不知道它的端点声明上：按完整原始路径段匹配，保留查询串，绝不解码或重定向，并且不修改传输所拥有的那个请求。完整契约见 [Boot §5](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#5-业务路径前缀)。

## 契约与构建

[服务器组合规范](../docs/ogbs/OGBS-Boot-1.0.zh-CN.md)、[Java 开发规范](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.zh-CN.md)、[OGBS 索引](../docs/ogbs/README.zh-CN.md)。公开包为 `cn.managame.boot.rpc`、`.metrics`、`.lifecycle` 与 `.http`；其中每个类型都可构造。

构建 `mvn -pl game-boot -am clean verify`。测试在没有容器的情况下经真实 TCP 拼装 RPC 适配器，覆盖钩子顺序、attach 失败后的清理、就绪与读数（含排空中的 Runtime）、前缀映射与引用所有权，以及预算推导。生产环境资源耗尽、卡死的存储驱动、TLS 与负载下的 RPC 容量仍未验证。
