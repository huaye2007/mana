English | [中文](README.zh-CN.md)

# managame-example

A small game, a world server and a game server, that joins every component in its own code, as an application does:
no framework project joins them. Copy from it. Nothing depends on it, and it is never installed.

## Run it

```bash
mvn install                                  # at the workspace root
java -jar managame-example/target/example.jar
```

Then play over telnet, one line per request, `<seq> <command> [arguments]`:

```
$ telnet 127.0.0.1 9001
1 login 1001 ann
1 ok welcome ann, gold 100
2 buy 5001
2 ok bought 5001, gold 70
3 bag
3 ok 5001x1
4 rank
4 ok rank 1 of 1
5 buy 9999
5 err 10002
```

Every minute the game server logs who is online and how much data waits to be written. Ctrl+C stops both servers in
order, and every change is written first.

## What each part shows

| File | Shows |
| --- | --- |
| `GameServer` | Every component joined: data, runtime, RPC, timers, then the gateway. It starts from the bottom up and stops from the top down, the data last; that order is what keeps shutdown correct, as no component counts work in flight. |
| `Gateway`, `Messages`, `Session` | The game's own protocol: lines decoded into messages, the session kept on the connection, every message handed to the `Dispatcher` with callbacks that answer in the game's format, and a logout when a player's connection closes. |
| `PlayerHandlers` | Handlers on the player's own route, one request at a time, changing data without locks. A non-null result is the answer; `fail` answers with the game's own codes. `rank` calls the world server and continues on the player's route with `runtime.execute(request, ...)` before touching the player. |
| `WorldServer` | RPC and runtime joined: the `RpcHandler` reads a request on the EventLoop and hands the work to the runtime, whose one world route keeps the board without locks. |
| `Model`, `Schema` | Entities, a bag as a group, logs, all in MySQL tables the game creates itself; item ids from a `SnowflakeIdGenerator` whose data part is the server id. |
| `Jobs` | A `@Cron` method on the system domain. |
| `ExampleMain` | The game's time zone set once on `GameTime`, and a shutdown hook that closes the game server before the world it calls. |
| `ExampleTest` | The game played over real connections, then the rows the game server saved on closing. |

The `pom.xml` takes the components as an application taking several does: it imports `managame-bom` and names them
without versions.

## On MySQL

The example keeps its data in H2, in memory and in MySQL mode, so it runs anywhere. A game on MySQL gives
`GameServer` a pooled `DataSource` over MySQL's driver instead, with the URL settings the data-mysql README lists, and
creates the tables from `Schema`, which is MySQL syntax.
