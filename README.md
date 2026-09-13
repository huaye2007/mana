# Mana

[Package layout, responsibilities and import migration (Chinese)](docs/package-layout.md)

English | [简体中文](README.zh-CN.md)

**Networking, RPC, and business execution components for Java game servers.**

Mana provides transport components for client-facing gateways, RPC communication between game services, and ordered business execution through game-runtime. Applications supply their own message codecs, service discovery, business route resolvers, and game logic.

The repository contains four projects: **game-network**, **game-rpc**, **game-runtime**, and **game-demo**.

## Projects

| Project | Purpose | Documentation |
|---|---|---|
| **game-network** | TCP, WebSocket, secure WebSocket, and HTTP servers; TCP and WebSocket clients, with access to native Netty pipelines. | [English](game-network/README.md) · [简体中文](game-network/README.zh-CN.md) |
| **game-rpc** | RPC over game-network, with peer connections, calls, notifications, explicit replies, timeouts, and metadata. | [English](game-rpc/README.md) · [简体中文](game-rpc/README.zh-CN.md) |
| **game-runtime** | JDK-only OGBS business runtime with ordered routes, commands, events, cron, timers, callbacks, metadata, and a replaceable clock. | [English](game-runtime/README.md) · [简体中文](game-runtime/README.zh-CN.md) |
| **game-demo** | Direct game-network clients, internal game-rpc server calls, and ordered game-runtime player commands. | [English](game-demo/README.md) · [简体中文](game-demo/README.zh-CN.md) |

### game-network

- Separate server and client implementations for each supported protocol.
- Multiple listening addresses per server and multiple connection targets per client.
- Shared network resources with explicit startup and shutdown.
- Native Netty handlers, codecs, channel options, and TLS configuration.
- HTTP context paths and dedicated HTTP pipelines.

Use it to build a TCP/WS/WSS gateway or expose HTTP endpoints. Application protocols define their own framing and serialization.

### game-rpc

- Logical peers identified by NodeId, with one or more physical TCP connections.
- Request/response calls, one-way notifications, and explicit replies.
- Call timeouts, metadata, and application error codes with string arguments.
- Heartbeats, per-connection-slot reconnection backoff, and optional read-idle closure.
- Custom codecs and transports, plus a default Netty transport provider.

Use it for communication between directly connected services. Service discovery, next-hop routing, and forwarding remain application responsibilities.

### game-runtime

- Strict FIFO execution per RouteGroup + long RouteKey, retained during virtual-thread blocking.
- Command and event bindings validated at initialization, with pre-bound parameter and route resolvers.
- Same-route inline events, cross-route dispatch, cron jobs, dynamic timers, and callback re-entry.
- Dynamically scoped context, immutable typed metadata, a replaceable clock, and unified failures.

Build and run its correctness tests with `mvn -pl game-runtime verify`. See the [runtime example](game-runtime/src/test/java/cn/managame/runtime/execution/RuntimeExample.java) and [usage guide](game-runtime/README.md).

## Requirements

| Component | Version |
|---|---|
| JDK | 25 |
| Maven | 3.9 |
| Netty | 4.2.15.Final, managed by the project |
| Project artifacts | 0.1.0-SNAPSHOT |

Python and Docker with a Linux engine are used for the optional RPC benchmark and container stress-test workflows. They are not required for the standard Maven build.

## Quick start

Clone the repository and build all modules:

~~~sh
git clone https://github.com/huaye2007/mana.git
cd mana
mvn verify
~~~

Run commands below from this repository root.

To build and test only the network implementation and its dependencies:

~~~sh
mvn -pl game-network/game-network-netty -am verify
~~~

To build and test the RPC implementation and its dependencies:

~~~sh
mvn -pl game-rpc/game-rpc-netty -am verify
~~~

JARs are generated in each module's `target/` directory. To make all artifacts available to other local Maven projects:

~~~sh
mvn install
~~~

### Add a dependency

After installing locally, add the component you need to your application's `pom.xml`.

For networking:

~~~xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-network-netty</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
~~~

For RPC, including its network implementation:

~~~xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-rpc-netty</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
~~~

### Run an example

Build and run the integrated game demo (see the [demo guide](game-demo/README.md) for Windows JVM options and separate server/client processes):

~~~sh
mvn -pl game-demo -am verify
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar
~~~

[GatewayExample](game-network/game-network-netty/src/test/java/cn/managame/network/tests/GatewayExample.java) demonstrates a gateway with TCP, WebSocket, and HTTP listeners sharing network resources. Run its `main` method in an IDE with JDK 25; press Enter to stop. It also supports WSS when supplied with a PEM certificate chain and private key.

For RPC node setup, peer connections, calls, notifications, and replies, follow the [game-rpc examples](game-rpc/README.md).

## Repository layout

~~~text
mana/
├── pom.xml                         # Build all projects together
├── game-network/
│   ├── game-network-api/           # JDK-only public network interfaces
│   ├── game-network-netty/         # Netty transport implementations
│   ├── docs/                       # Specification, design, and validation
│   └── scripts/                    # Linux validation helpers
├── game-rpc/
│   ├── game-rpc-core/              # RPC APIs, calls, peers, and message handling
│   ├── game-rpc-netty/             # Default Netty transport integration
│   ├── docs/                      # Wire and Java implementation specifications
│   └── benchmarks/                # Local benchmarks and Docker stress tests
├── game-runtime/                  # JDK-only ordered business runtime, tests, and specification
└── game-demo/                     # Runnable game-network + game-rpc + game-runtime example
~~~

## Documentation and validation

| Topic | Reference |
|---|---|
| Network usage and configuration | [game-network README](game-network/README.md) |
| Network protocol specification | [Game Network Specification](game-network/docs/Game%20Network%20Specification.md) |
| Network operations and capacity testing | [Operations guide](game-network/docs/operations.md) |
| Network implementation and validation scope | [Implementation record](game-network/docs/implementation-status.md) |
| RPC usage, benchmarks, and Docker fault tests | [game-rpc README](game-rpc/README.md) |
| RPC behavior and wire format | [Game RPC Specification](game-rpc/docs/OGBS%20Game%20RPC%20Specification%20v1.md) |
| RPC implementation and memory ownership | [Java Implementation Specification](game-rpc/docs/java/Java%20Implementation%20Specification.md) |
| RPC wire test data | [Wire vectors](game-rpc/docs/OGBS%20Game%20RPC%20v1%20wire%20vectors.json) |
| Runtime usage and execution semantics | [game-runtime README](game-runtime/README.md) |

The standard `mvn verify` build runs the projects' regression tests. Capacity benchmarks and Docker endurance/fault tests are separate workflows described in the project documentation. Their measurements should be interpreted within the documented test environment and scope.

## Documentation languages

The default `README.md` is English. Each project also provides a Simplified Chinese `README.zh-CN.md`, linked at the top of its README. Keep both versions in sync when updating project documentation.
