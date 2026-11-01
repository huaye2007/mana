English | [中文](README.zh-CN.md)

# managame-example

A small game, a world server and a game server, built as a real game server is: protobuf messages in frames of the
game's own codec, every component joined in the game's own code with plain Spring (spring-context, no Spring Boot).
No framework project joins them, and the components know nothing of Spring or protobuf. Copy from it. Nothing depends
on it, and it is never installed.

## Run it

```bash
mvn install                                  # at the workspace root; the first build downloads protoc
java -jar managame-example/target/example.jar
```

From another terminal, play a few requests with the example client:

```
$ java -cp managame-example/target/example.jar cn.managame.example.ExampleClient 1001 ann
> LoginRequest player_id: 1001 name: "ann"
< LoginResponse name: "ann" gold: 100
> BuyRequest config_id: 5001
< BuyResponse config_id: 5001 gold: 70
> BuyRequest config_id: 5003
< error 10001
> BagRequest
< BagResponse items { config_id: 5001 amount: 1 }
> RankRequest
< RankResponse rank: 1 ranked: 1
```

Every minute the game server logs who is online and how much data waits to be written. Ctrl+C closes the Spring
context: the servers stop in order, and every change is written first. Ports and the database URL are in
`example.properties`. On JDK 25 protobuf-java warns that it uses `sun.misc.Unsafe`; the JVM option
`--sun-misc-unsafe-memory-access=allow` silences it.

## The protocol

Messages are defined in `src/main/proto/game.proto`, and the build generates their classes. Each travels in one frame:

```
| length int | message id int | seq int | code int | protobuf body |
```

network's `lengthFieldFraming` cuts the stream into frames; the game's `PacketCodec` turns a frame into a `Packet`
(seq, code, protobuf message) and back, the body read from and written into Netty's buffers without a byte array
between. `Protocol` gives each message type its id. A request has code 0; its answer echoes the seq, with the response
message, or no message and the error code. A frame the codec cannot read closes the connection.

## What each part shows

| File | Shows |
| --- | --- |
| `GameConfig` | Every component a bean: data, runtime, the world and RPC, the Dispatcher and the timer, then the gateway on length-field framing and the packet codec. Spring closes each bean before the beans it depends on, so the dependencies give the shutdown order (the gateway first, the data last; `@DependsOn` where no constructor says it). That order is what keeps shutdown correct, as no component counts work in flight. The Dispatcher and the timer each take every component as a collection and serve the methods annotated for them. |
| `Protocol`, `Packet`, `PacketCodec` | The game's own protocol over protobuf: message ids, the frame, and the Netty codec. |
| `Gateway`, `Session` | Each decoded message handed to the `Dispatcher` with callbacks that answer as packets under the request's seq, the session kept on the connection, and a logout when a player's connection closes. |
| `PlayerHandlers` | A component of handlers on the player's own route, one request at a time, changing data without locks: each takes the protobuf request and returns the protobuf response; `fail` answers with the game's own codes. `rank` calls the world server and continues on the player's route with `runtime.execute(request, ...)` before touching the player. |
| `WorldServer` | RPC and runtime joined, with protobuf bodies: the `RpcHandler` reads a request on the EventLoop and hands the work to the runtime, whose one world route keeps the board without locks. |
| `Model`, `Schema` | Entities, a bag as a group, logs, all in MySQL tables the game creates itself; item ids from a `SnowflakeIdGenerator` whose data part is the server id. |
| `OnlinePlayers`, `Jobs` | The connections of players logged in, kept by the game; a `@Cron` method on the system domain. |
| `GameClient`, `ExampleClient` | A blocking client of the protocol, and a main that plays it. |
| `ExampleMain` | The game's time zone set once on `GameTime`, the context started, and its shutdown hook. |
| `ExampleTest` | The context started on free ports, the game played over real connections, a client breaking the protocol closed, then the rows saved when the context closed. |

The `pom.xml` takes the components as an application taking several does: it imports `managame-bom` and names them
without versions. It generates the protobuf classes with `protobuf-maven-plugin`.

## On MySQL

The example keeps its data in H2, in memory and in MySQL mode, so it runs anywhere. A game on MySQL changes the
`dataSource` bean to a pool over MySQL's driver, with the URL settings the data-mysql README lists, and creates the
tables from `Schema`, which is MySQL syntax.
