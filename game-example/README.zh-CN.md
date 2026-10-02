# game-example

[English](README.md) | **[简体中文](README.zh-CN.md)**

仓库框架组件的独立可运行示例模块，使用 Java 25，Maven 坐标为 cn.managame:game-example。所有可运行示例类及其执行测试统一放在这里，按 `cn.managame.example.<component>` 分包。组件契约测试仍保留在各框架模块中。

本模块演示已有契约，不定义新的框架组件或规范对。框架 artifact 不包含这些示例类，也不依赖 game-example。当前示例依赖 game-network、game-runtime 与 game-rpc；其他组件在实现实际示例时再添加依赖。

| 示例 | 行为 | 标准规范 | Java 开发规范 |
| --- | --- | --- | --- |
| [NetworkEchoExample](src/main/java/cn/managame/example/network/NetworkEchoExample.java) | 本机随机端口、带长度 framing 的 TCP 字符串 echo | [Network](../docs/ogbs/OGBS-Network-1.0.zh-CN.md) | [Network Java 25](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md) |
| [HttpServerExample](src/main/java/cn/managame/example/network/HttpServerExample.java) | 独立 HTTP/1.1 health/echo 服务端、请求 body retain、JDK 示例调用方 | [HTTP Profile](../docs/ogbs/OGBS-Network-1.0.zh-CN.md#http-server-profile) | [HTTP Java 绑定](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md#native-http-server-api) |
| [HttpAsyncServerExample](src/main/java/cn/managame/example/network/HttpAsyncServerExample.java) | 应用执行器上的 HTTP 回调完成、不可变 UTF-8 输入快照 | [HTTP Profile](../docs/ogbs/OGBS-Network-1.0.zh-CN.md#http-server-profile) | [HTTP 回调 API](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md#http-async-response) |
| [RuntimeHttpExample](src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java) | 业务 DTO JSON 结果与跨 Route 对象回调、GET/query 与 POST/body Key | [Runtime HTTP](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#runtime-http-profile) | [Runtime HTTP API](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#runtime-http-api) |
| [RpcEchoExample](src/main/java/cn/managame/example/rpc/RpcEchoExample.java) | 两个本机 TCP 节点、call/reply、借用 body 的 retain、应用字符串解码 | [RPC](../docs/ogbs/OGBS-RPC-1.0.zh-CN.md) | [RPC Java 25](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md) |

在 IDE 中以 JDK 25 导入根 Maven 项目，从 game-example 运行 main 方法，并将其依赖加入 classpath。预期输出为 hello game-network、hello HTTP/1.1 、hello Runtime HTTP 和 hello game-rpc。各示例都只绑定 loopback 地址和随机端口，将响应等待限制为五秒，向调用方传播失败，并通过 try-with-resources 关闭自有资源。这些等待时限仅用于演示，不是生产配置。

Network 示例交换不可变字符串；若 echo 引用计数 buffer，则需要独立 retain 发送引用。RPC 示例先 retain 借用的入站 body，再将该引用转交 reply；入站引用的 retain 与响应解码是独立职责。

HTTP 示例直接使用 HttpServer，不使用 ConnectionHandler，通过 pipeline(...) 按需添加原生 HttpContentCompressor。应用选择 /health 和 /echo，将借用 echo body 的 retain 引用交给响应，JDK HttpClient 仅作演示调用方。[HttpServerExampleTest](src/test/java/cn/managame/example/network/HttpServerExampleTest.java) 校验 UTF-8 echo。由于已有 RPC 测试 API 和 RpcEchoExample.maxPendingCalls 与恢复后的 RPC 实现不匹配，HTTP 源码已单独编译并运行；这些无关错误目前阻断下述标准模块/根目录命令。

从仓库根目录构建和验证：

```shell
mvn -pl game-example -am test
mvn clean verify
```

[NetworkEchoExampleTest](src/test/java/cn/managame/example/network/NetworkEchoExampleTest.java) 与 [RpcExampleTest](src/test/java/cn/managame/example/rpc/RpcExampleTest.java) 通过本机 TCP 执行完整示例。共享测试设置限制 Netty 线程数、启用泄漏检测，并使用 Windows Selector 的 TCP 唤醒兼容设置。可运行示例本身不修改 JVM 属性。

示例原来位于 cn.managame.network.example 和 cn.managame.rpc.example。调用方应将 import 更新为 cn.managame.example.network 和 cn.managame.example.rpc，并使用 game-example 模块；不保留旧包别名。

RPC→Runtime 集成与 DataMemoryDemo 尚未实现。生产容量、公网部署与跨语言互操作仍未验证。依赖边界见 [架构概览](../docs/architecture.zh-CN.md)。

[RuntimeHttpExampleTest](src/test/java/cn/managame/example/runtime/RuntimeHttpExampleTest.java) 通过真实 HttpServer 检查 UTF-8 echo 与延迟 lookup。示例分别拥有 Server 和 Runtime，Runtime 不负责关闭 Server。POST /echo 使用默认方法，GET /lookup 显式使用 HttpRequestMethod.GET。POST JSON 字段 playerId 与 GET query 覆盖字段 lookupId 选择 Key，均为路由输入，不证明已认证身份。默认 HttpContext 无需工厂或业务身份/Metadata。Runtime 对已接纳请求 retain 到方法执行与自动结果编码结束；方法返回 EchoResult，延迟回调提交 PlayerResult，只捕获不可变 RouteKey，不读取已释放请求。Runtime 序列化对象，业务代码不构造传输响应或 HTTP 版本。已有 RPC API 不匹配阻塞 reactor 验证期间，HTTP 示例独立编译运行。
