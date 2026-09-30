# game-example

**[English](README.md)** | [简体中文](README.zh-CN.md)

Standalone runnable examples for the repository's framework components, built with Java 25 as cn.managame:game-example. All runnable example classes and their execution tests belong here, under `cn.managame.example.<component>`. Component contract tests remain in their framework modules.

This module demonstrates existing contracts and defines no new framework component or specification pair. Framework artifacts do not include these example classes and do not depend on game-example. The current examples require game-network and game-rpc; add other component dependencies when their examples are implemented.

| Example | Behavior | Specification | Java Development Specification |
| --- | --- | --- | --- |
| [NetworkEchoExample](src/main/java/cn/managame/example/network/NetworkEchoExample.java) | Local framed TCP string echo on a dynamic port | [Network](../docs/ogbs/OGBS-Network-1.0.md) | [Network Java 25](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) |
| [RpcEchoExample](src/main/java/cn/managame/example/rpc/RpcEchoExample.java) | Two local TCP nodes, call/reply, retained borrowed body, application string decoding | [RPC](../docs/ogbs/OGBS-RPC-1.0.md) | [RPC Java 25](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.md) |

Import the root Maven project into an IDE with JDK 25 and run either main method from game-example with its dependencies on the classpath. Expected outputs are hello game-network and hello game-rpc, respectively. Each example binds only loopback addresses with random ports, bounds its response wait to five seconds, propagates failures, and closes its owned resources through try-with-resources. These waits are demonstration bounds, not production configuration.

The Network example exchanges immutable strings; an echo of a reference-counted buffer would instead require an independent retained send reference. The RPC example retains its borrowed inbound body before transferring it to reply; retaining the inbound reference is independent of response decoding.

Build and validate from the repository root:

```shell
mvn -pl game-example -am test
mvn clean verify
```

[NetworkEchoExampleTest](src/test/java/cn/managame/example/network/NetworkEchoExampleTest.java) and [RpcExampleTest](src/test/java/cn/managame/example/rpc/RpcExampleTest.java) execute the complete examples over local TCP. Their shared test setup limits Netty threads, enables leak detection, and uses the Windows Selector TCP wakeup compatibility setting. Runnable examples themselves do not change JVM properties.

The examples formerly lived in cn.managame.network.example and cn.managame.rpc.example. Update imports to cn.managame.example.network and cn.managame.example.rpc and use the game-example module; no old-package aliases are provided.

RPC→Runtime integration and DataMemoryDemo are unimplemented. Production capacity, public-network deployment, and cross-language interoperability remain unverified. See the [architecture overview](../docs/architecture.md) for dependency boundaries.
