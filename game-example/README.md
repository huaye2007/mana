# game-example

**[English](README.md)** | [简体中文](README.zh-CN.md)

Standalone runnable examples for the repository's framework components, built with Java 25 as cn.managame:game-example. All runnable example classes and their execution tests belong here, under `cn.managame.example.<component>`. Component contract tests remain in their framework modules.

This module demonstrates existing contracts and defines no new framework component or specification pair. Framework artifacts do not include these example classes and do not depend on game-example. The current examples require game-network, game-runtime, and game-rpc; add other component dependencies when their examples are implemented.

| Example | Behavior | Specification | Java Development Specification |
| --- | --- | --- | --- |
| [NetworkEchoExample](src/main/java/cn/managame/example/network/NetworkEchoExample.java) | Local framed TCP string echo on a dynamic port | [Network](../docs/ogbs/OGBS-Network-1.0.md) | [Network Java 25](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) |
| [HttpServerExample](src/main/java/cn/managame/example/network/HttpServerExample.java) | Independent HTTP/1.1 health/echo server, retained request body, JDK example caller | [HTTP profile](../docs/ogbs/OGBS-Network-1.0.md#http-server-profile) | [HTTP Java binding](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md#native-http-server-api) |
| [HttpAsyncServerExample](src/main/java/cn/managame/example/network/HttpAsyncServerExample.java) | HTTP callback completion on an application-owned executor, immutable UTF-8 input snapshot | [HTTP profile](../docs/ogbs/OGBS-Network-1.0.md#http-server-profile) | [HTTP callback API](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md#http-async-response) |
| [RuntimeHttpExample](src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java) | Business DTO JSON result and deferred cross-Route object completion, GET/query and POST/body Keys | [Runtime HTTP](../docs/ogbs/OGBS-Runtime-1.0.md#runtime-http-profile) | [Runtime HTTP API](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#runtime-http-api) |
| [RpcEchoExample](src/main/java/cn/managame/example/rpc/RpcEchoExample.java) | Two local TCP nodes, call/reply, retained borrowed body, application string decoding | [RPC](../docs/ogbs/OGBS-RPC-1.0.md) | [RPC Java 25](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.md) |

Import the root Maven project into an IDE with JDK 25 and run a main method from game-example with its dependencies on the classpath. Expected outputs are hello game-network, hello HTTP/1.1, hello Runtime HTTP, and hello game-rpc. Each example binds only loopback addresses with random ports, bounds its response wait to five seconds, propagates failures, and closes its owned resources through try-with-resources. These waits are demonstration bounds, not production configuration.

The Network example exchanges immutable strings; an echo of a reference-counted buffer would instead require an independent retained send reference. The RPC example retains its borrowed inbound body before transferring it to reply; retaining the inbound reference is independent of response decoding.

The HTTP example uses HttpServer directly without ConnectionHandler and opts into native HttpContentCompressor through pipeline(...). Its application selects /health and /echo, retains the borrowed echo body for the response, and uses JDK HttpClient only as a demonstration caller. [HttpServerExampleTest](src/test/java/cn/managame/example/network/HttpServerExampleTest.java) checks UTF-8 echo. The HTTP source was compiled/run separately because existing RPC test APIs and RpcEchoExample.maxPendingCalls do not match the restored RPC implementation; those unrelated mismatches currently block the standard full module/root commands below.

Build and validate from the repository root:

```shell
mvn -pl game-example -am test
mvn clean verify
```

[NetworkEchoExampleTest](src/test/java/cn/managame/example/network/NetworkEchoExampleTest.java) and [RpcExampleTest](src/test/java/cn/managame/example/rpc/RpcExampleTest.java) execute the complete examples over local TCP. Their shared test setup limits Netty threads, enables leak detection, and uses the Windows Selector TCP wakeup compatibility setting. Runnable examples themselves do not change JVM properties.

The examples formerly lived in cn.managame.network.example and cn.managame.rpc.example. Update imports to cn.managame.example.network and cn.managame.example.rpc and use the game-example module; no old-package aliases are provided.

RPC→Runtime integration and DataMemoryDemo are unimplemented. Production capacity, public-network deployment, and cross-language interoperability remain unverified. See the [architecture overview](../docs/architecture.md) for dependency boundaries.

[RuntimeHttpExampleTest](src/test/java/cn/managame/example/runtime/RuntimeHttpExampleTest.java) checks UTF-8 echo and deferred lookup over a real HttpServer. The example owns its server and Runtime separately; Runtime does not close the server. POST JSON field playerId and GET query override lookupId select the Key; both are routing input rather than authenticated identity. The default HttpContext needs no factory or business identity/Metadata. Runtime retains each admitted request through method execution and automatic result encoding. The method returns EchoResult and the deferred callback submits PlayerResult, capturing the immutable RouteKey and never reading the released request. Runtime serializes the objects; business code constructs no transport response or HTTP version. The HTTP examples are compiled/run independently while the existing RPC API mismatches block reactor validation.
