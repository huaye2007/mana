English | [中文](README.zh-CN.md)

# managame-benchmark

JMH benchmarks for every framework component, with a recorded baseline for each, so that a change that makes a
component faster or slower, or makes it allocate more, shows at once. It is a tool, not a component: nothing
depends on it, and its benchmarks of one component never join it with another.

| Package | Benchmarks | What they measure |
| --- | --- | --- |
| `cn.managame.benchmark.common` | `MetadataBenchmark` | Building metadata, reading a value, wrapping received bytes (ns/op) |
| `cn.managame.benchmark.network` | `EchoThroughputBenchmark`, `EchoLatencyBenchmark`, and the same as `…ProcessBenchmark` | Length-framed messages echoed over loopback: per second with many in flight, and the round trip of one |
| `cn.managame.benchmark.runtime` | `ExecuteThroughputBenchmark`, `DispatchThroughputBenchmark` | Tasks through `GameRuntime.execute`, and player requests through the `Dispatcher` to an annotated handler, per second |
| `cn.managame.benchmark.rpc` | `CallThroughputBenchmark`, `CallLatencyBenchmark`, `NotifyThroughputBenchmark`, and the same as `…ProcessBenchmark` | Calls answered and notifies handled per second with many in flight, and the round trip of one call |
| `cn.managame.benchmark.data` | `RepositoryBenchmark`, `UpdateThroughputBenchmark`, `WriteThroughputBenchmark` | What business threads pay with entities in memory (reading a player or a bag, recording a change; ns/op), changes recorded per second by four threads at once, and changes a round writes per second through a mapper that keeps nothing: managame-data's own cost, without a database |
| `cn.managame.benchmark.mysql` | `SaveThroughputBenchmark` | Rows written per second through managame-data-mysql into an in-memory H2 database in MySQL mode: updates, and inserts with deletes |

The network echo benchmarks put the same load on the transport as the rpc ones, so the difference between them is
what RPC adds. Each runs twice: with both sides in one benchmark JVM, and, as its `…ProcessBenchmark`, with the
server in a process of its own; see [Two processes](#two-processes).

## Running

Install the components first, then build the benchmarks into one jar:

```bash
cd managame-benchmark && mvn -o clean package
```

Run components by name, or `all`, from this directory:

```bash
java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar rpc
```

| Argument | Meaning |
| --- | --- |
| `common`, `network`, `runtime`, `rpc`, `data`, `mysql`, `all` | The components to run (`mysql` is managame-data-mysql) |
| `--save` | Record the results as the new baseline |
| `--include=<regex>` | Only the benchmarks whose name matches, such as `--include=CallLatency` |
| `--threshold=<percent>` | How far a result must move to count as better or worse; 10 by default |
| `--results=<dir>` | Where the baselines are; `results` by default |
| `--against=<jar>` | Compare with another build of the benchmarks, run in turn with this one, instead of with the baseline; see [Against the code before a change](#against-the-code-before-a-change) |

`jdk.net.unixdomain.tmpdir` is passed on to the benchmark JVMs; on Windows the JDK's selectors need it to name a
writable directory. Every benchmark JVM runs with a 1 GiB heap and Netty's leak detection off. A component takes
from one to four minutes, four times that with `--against`.

## Baselines

`results/<component>.csv` holds one line per benchmark and parameter set, under a line naming the machine and
date it was recorded on. Besides the score, each run measures what it cost, with JMH's GC profiler and the
benchmarks' own `CostProfiler`, around every iteration in the benchmark JVM:

| Column | Meaning |
| --- | --- |
| `benchmark`, `params`, `mode` | Which benchmark, with which parameters, measured how (`thrpt`, `avgt`, `sample`) |
| `score`, `error`, `unit` | The result and its 99.9% confidence error |
| `alloc_bytes_per_op`, `alloc_mb_per_s` | Heap allocated per operation and per second |
| `p50`, `p99` | The median and 99th percentile, for sampled latencies |
| `gc_count`, `gc_ms` | Garbage collections during the measurement, and their time |
| `cpu_ns_per_op` | CPU time of the whole benchmark JVM per operation |
| `cpu_caller_ns_per_op` | ... of it on the benchmark's own threads, the ones calling the component |
| `cpu_eventloop_ns_per_op` | ... on Netty's threads: EventLoops, both sides of a connection |
| `cpu_other_ns_per_op` | ... on every other Java thread: timers, executors, virtual threads' carriers |
| `cpu_jvm_ns_per_op` | ... on the JVM's own work: garbage collection and compilation |
| `direct_mib` | Direct memory Netty holds at the end of an iteration, the highest of them |
| `sent_bytes_per_op`, `received_bytes_per_op` | Bytes on the wire per operation, without TCP and IP headers, counted by the dialing side's `CountingSocketChannel` |
| `busy_percent` | How much of the machine's CPU other programs used, the highest of the iterations; the peer process does not count |
| `peer_cpu_ns_per_op`, `peer_cpu_eventloop_ns_per_op`, `peer_cpu_other_ns_per_op`, `peer_cpu_jvm_ns_per_op` | For a `…ProcessBenchmark`, the server process's CPU per operation, split the same way |
| `peer_alloc_bytes_per_op`, `peer_gc_count`, `peer_gc_ms`, `peer_direct_mib` | ... and its heap allocated per operation, garbage collections and direct memory |

A thread's CPU time can only be read while it lives, and JMH stops a benchmark's fixture, and with it its
EventLoops, before the last iteration's measurement is taken; the profiler therefore samples every thread every
50 ms and counts one that ended with its last reading.

Each run prints every result next to its baseline, and what it cost on a second line; the bandwidth is the bytes
per operation times the operations per second:

```
== rpc: against the baseline 2026-10-28 on Windows 11 amd64, 18 CPUs, Java 25.0.3
CallThroughputBenchmark.call   connections=1 payload=64 window=1024   612,000 -> 598,000 ops/s  -2.3% same  alloc 1,010 B/op
    cpu 4,210 -> 4,305 ns/op (caller 610, eventloop 3,400, other 25, jvm 270)  |  gc 41 in 52 ms  |  direct 16.0 MiB  |  net 100+79 -> 100+79 B/op, 107.0 MB/s  |  busy 4%
```

A result is `better` or `WORSE` when it moved by more than the threshold and by more than the two errors
together, otherwise `same`. Allocation is flagged when it moved by more than the threshold and 32 bytes, and bytes
on the wire by the threshold and 4 bytes: neither depends on the machine, so `MORE ALLOCATION` and `MORE BYTES`
count as worse. CPU time per operation is shown, not judged, as a slower core raises it too.

Other programs that use more than a tenth of the machine move the scores: a score that got worse then is
`unsure (machine N% busy)`, and `--save` saves nothing of that component. The exit code is 1 when anything got
worse, 3 when nothing did but something is unsure, and 0 otherwise.

## Two processes

Game servers talk between processes, each with its own JVM, garbage collection and threads. A `…ProcessBenchmark`
runs the server side, the echo server or the world node, in a process of its own: `PeerProcess` starts it from the
same jar with the benchmark JVM's settings, and the benchmark JVM is the client, the game node. Around every
iteration the peer measures what it used itself, and the result shows the two sides apart:

```
CallThroughputProcessBenchmark.call   connections=1 payload=64 window=1024   598,000 ops/s  new  alloc 610 B/op
    client cpu 2,450 ns/op (caller 610, eventloop 1,600, other 40, jvm 200)  |  gc 4 in 15 ms  |  direct 3.4 MiB  |  net 100+79 B/op, 107.0 MB/s  |  busy 4%
    server cpu 1,700 ns/op (eventloop 1,550, other 0, jvm 150)  |  alloc 400 B/op  |  gc 2 in 8 ms  |  direct 2.0 MiB
```

Both processes still share the machine's CPUs. A world node in its own process cannot release the client's
window as the in-process one does, so for notifies it sends a notify back for every 64 it handled, and answers a
call at the end of each iteration with those not acknowledged yet. `SERVER ALLOCATION` is flagged like the client's.

## Against the code before a change

A baseline was recorded at another time, and the machine may not be the same now: on a laptop with performance
and efficiency cores, Windows moves a background benchmark onto the slower cores while foreground apps are busy,
and every result can drop by half for minutes, with the code unchanged. Allocation per operation does not move
with the machine, so a result that got worse while its allocation stayed is the case to check.

`--against` tells the machine from the code. Build the benchmarks from the code before the change into a jar kept
outside `target/`, then run this build against it:

```bash
git stash                                          # the code before the change
cd ../managame-rpc && mvn -o install -DskipTests
cd ../managame-benchmark && mvn -o clean package && cp target/benchmarks.jar D:/tmp/before.jar
git stash pop                                      # the change again
cd ../managame-rpc && mvn -o install -DskipTests
cd ../managame-benchmark && mvn -o clean package
java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar rpc --against=D:\tmp\before.jar
```

The two jars run in turn, the other first and twice each, so both meet the machine as it is during the session.
The other jar's output goes to log files in a temporary directory the run names at the end.

```
== rpc: against D:\tmp\before.jar, each run 2 times in turn, it first
CallThroughputBenchmark.call   connections=1 payload=64 window=1024   629,824/627,016 -> 621,989/618,503 ops/s  -1.3% same (it moved 0.4% itself)  alloc 1,010 -> 1,010 B/op
NotifyThroughputBenchmark.notifyPeer   connections=1 payload=64 window=1024   774,468/378,191 -> 798,003/791,224 ops/s  +38.0% unsure (it moved 69.0% itself)  alloc 404 -> 405 B/op
```

A result is `better` or `WORSE` only when it moved by more than the threshold, both runs of this build fall on the
same side of both runs of the other, and the means differ by more than the other jar moved between its own two
runs. One that moved by more than the threshold, but not that clearly, is `unsure`: the machine moved as much as
the result. Allocation is judged as against a baseline. Nothing is saved; the exit code is 1 when anything got
worse, 3 when nothing did but something is unsure, and 0 otherwise. On `unsure`, close what keeps the machine busy
or choose the best-performance power mode, and run again.

## Workflow

1. Before changing a component, run its benchmarks once to see where it stands on your machine.
2. After the change, run them again and read the comparison. A worse result whose allocation did not move may be
   the machine: confirm it with `--against` before looking for the cause in the code. A worse result that holds is
   a reason to look at the change, for example with `-prof gc` or a profiler through JMH's own main class:
   `java -cp target/benchmarks.jar org.openjdk.jmh.Main <regex> -prof stack`.
3. When a change makes a component faster or slower on purpose, or adds a benchmark, save the new baseline with
   `--save` and commit `results/` together with the change, so the history of the files is the history of the
   component's performance.

Numbers only compare on the same machine: the baselines here were recorded on the machine their first line names.
Compare runs on another machine with each other, not with them. Close other heavy work while measuring, and save
no baseline from a run taken while the machine was busy.

## Adding a benchmark

- Put it in the package of the component it measures, and use only that component (and those below it).
- Use `@Fork(1)`, `@Warmup(iterations = 3, time = 2)`, `@Measurement(iterations = 5, time = 2)` (1-second
  iterations for nanosecond benchmarks), so that every component takes minutes, not hours.
- Prepare what is not measured in `@Setup`: payloads are duplicates of one buffer, contexts are built once.
- For asynchronous work, bound what is in flight with a window (a `Semaphore` the completion releases) and let
  the iteration drain in `@TearDown(Level.Iteration)`; fail the benchmark when a call fails, so a broken path does
  not pass for a fast one.
- A benchmark that talks over sockets dials through `CountingSocketChannel` (`channel(CountingSocketChannel.class)`
  on a group from `CountingSocketChannel.newGroup`), so that its bytes on the wire are counted.
- One with a server side also runs it apart: its fixture starts the server with `PeerProcess.start(<main class>)`
  when asked to, that class's `main` starts the server and calls `PeerProcess.serve`, and a `…ProcessBenchmark`
  subclass turns it on by overriding `otherProcess()`.
- Run it with `--save` and commit the new baseline lines.

## Build

```bash
mvn -o clean package
```

Install managame-common, managame-network, managame-runtime, managame-rpc, managame-data and managame-data-mysql first.
