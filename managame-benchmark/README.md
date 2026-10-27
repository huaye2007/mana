English | [中文](README.zh-CN.md)

# managame-benchmark

JMH benchmarks for every framework component, with a recorded baseline for each, so that a change that makes a
component faster or slower, or makes it allocate more, shows at once. It is a tool, not a component: nothing
depends on it, and its benchmarks of one component never join it with another.

| Package | Benchmarks | What they measure |
| --- | --- | --- |
| `cn.managame.benchmark.common` | `MetadataBenchmark` | Building metadata, reading a value, wrapping received bytes (ns/op) |
| `cn.managame.benchmark.network` | `EchoThroughputBenchmark`, `EchoLatencyBenchmark` | Length-framed messages echoed over loopback: per second with many in flight, and the round trip of one |
| `cn.managame.benchmark.runtime` | `ExecuteThroughputBenchmark`, `DispatchThroughputBenchmark` | Tasks through `GameRuntime.execute`, and player requests through the `Dispatcher` to an annotated handler, per second |
| `cn.managame.benchmark.rpc` | `CallThroughputBenchmark`, `CallLatencyBenchmark`, `NotifyThroughputBenchmark` | Calls answered and notifies handled per second with many in flight, and the round trip of one call |

The network echo benchmarks put the same load on the transport as the rpc ones, so the difference between them is
what RPC adds.

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
| `common`, `network`, `runtime`, `rpc`, `all` | The components to run |
| `--save` | Record the results as the new baseline |
| `--include=<regex>` | Only the benchmarks whose name matches, such as `--include=CallLatency` |
| `--threshold=<percent>` | How far a result must move to count as better or worse; 10 by default |
| `--results=<dir>` | Where the baselines are; `results` by default |

`jdk.net.unixdomain.tmpdir` is passed on to the benchmark JVMs; on Windows the JDK's selectors need it to name a
writable directory. Every benchmark JVM runs with a 1 GiB heap and Netty's leak detection off. A component takes
from one to four minutes.

## Baselines

`results/<component>.csv` holds one line per benchmark and parameter set, under a line naming the machine and
date it was recorded on:

| Column | Meaning |
| --- | --- |
| `benchmark`, `params`, `mode` | Which benchmark, with which parameters, measured how (`thrpt`, `avgt`, `sample`) |
| `score`, `error`, `unit` | The result and its 99.9% confidence error |
| `alloc_bytes_per_op` | Bytes allocated per operation, from JMH's GC profiler |
| `p50`, `p99` | The median and 99th percentile, for sampled latencies |

Each run prints every result next to its baseline:

```
== rpc: against the baseline 2026-10-28 on Windows 11 amd64, 18 CPUs, Java 25.0.3
CallThroughputBenchmark.call   connections=1 payload=64 window=1024   612,000 -> 598,000 ops/s  -2.3% same  alloc 690 B/op
```

A result is `better` or `WORSE` when it moved by more than the threshold and by more than the two errors
together, otherwise `same`; allocation is flagged when it moved by more than the threshold and 32 bytes. The exit
code is 1 when anything got worse.

## Workflow

1. Before changing a component, run its benchmarks once to see where it stands on your machine.
2. After the change, run them again and read the comparison. A worse result is a reason to look at the change,
   for example with `-prof gc` or a profiler through JMH's own main class:
   `java -cp target/benchmarks.jar org.openjdk.jmh.Main <regex> -prof stack`.
3. When a change makes a component faster or slower on purpose, or adds a benchmark, save the new baseline with
   `--save` and commit `results/` together with the change, so the history of the files is the history of the
   component's performance.

Numbers only compare on the same machine: the baselines here were recorded on the machine their first line names.
Compare runs on another machine with each other, not with them. Close other heavy work while measuring.

## Adding a benchmark

- Put it in the package of the component it measures, and use only that component (and those below it).
- Use `@Fork(1)`, `@Warmup(iterations = 3, time = 2)`, `@Measurement(iterations = 5, time = 2)` (1-second
  iterations for nanosecond benchmarks), so that every component takes minutes, not hours.
- Prepare what is not measured in `@Setup`: payloads are duplicates of one buffer, contexts are built once.
- For asynchronous work, bound what is in flight with a window (a `Semaphore` the completion releases) and let
  the iteration drain in `@TearDown(Level.Iteration)`; fail the benchmark when a call fails, so a broken path does
  not pass for a fast one.
- Run it with `--save` and commit the new baseline lines.

## Build

```bash
mvn -o clean package
```

Install managame-common, managame-network, managame-runtime and managame-rpc first.
