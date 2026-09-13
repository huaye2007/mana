# game-runtime-disruptor

Optional RouteDispatcher implementation using com.lmax:disruptor:4.0.0. It lives outside the core runtime artifact and root reactor; build it explicitly.

~~~powershell
mvn -pl game-runtime install
mvn -f game-runtime/adapters/disruptor/pom.xml verify
~~~

Applications can install the adapter with the second command changed to install, then depend on cn.managame:game-runtime-disruptor:0.1.0-SNAPSHOT.

~~~java
import cn.managame.runtime.execution.ExecutionDomain;
import cn.managame.runtime.disruptor.DisruptorRouteDispatcher;

var rooms = ExecutionDomain.custom("rooms",
        () -> new DisruptorRouteDispatcher("rooms", 4, 2048))
    .tasksPerTurn(32)
    .maxTasks(1000)
    .maxTasksPerRoute(64)
    .callbackReserve(100, 8)
    .build();

builder.executionDomain(GameRoutes.Room.class, rooms);
~~~

The example numbers are illustrative. The constructor takes a name, worker count and power-of-two ring size per worker.

Each worker owns one multi-producer RingBuffer, one platform consumer thread and a consumer-local ready queue. The full route key is hashed to a stable partition. Runtime owns same-Route FIFO, exclusivity and batch size. BlockingWaitStrategy allows idle consumers to wait without busy spinning.

The ring accepts first activations of entities through tryPublishEvent. A full ring rejects that new activation immediately without blocking the producer. Once the consumer copies a batch to its ready queue, it clears the slot reference and reports its sequence before running business, releasing ingress capacity promptly.

An admitted entity's next batch uses reschedule and joins the consumer-local queue directly. It never competes for ring capacity, so a saturated ring cannot fail already accepted business. Each entity has at most one ready continuation; total ready capacity is bounded by the Runtime's admitted entity count. A consumer takes batches from the front and puts continuations at the back, admitting new ring events between turns. This allows hot and newly active entities to make progress without recursive execution or a separate timer.

reschedule must be called by the same partition consumer while finishing its current batch, as guaranteed by Runtime. It is not a public ingress substitute for dispatch.

shutdown halts consumers only after Runtime has drained admitted entity work; awaitTermination joins those platform threads within the remaining close deadline. Direct adapter users must follow the same lifecycle contract.

Tests cover 3,200 ordered tasks from concurrent producers, stable room threads, full-ring ingress rejection, accepted FIFO continuations across 40 wraps with a size-two ring, hot/new entity fairness and timed shutdown. No throughput advantage over built-in scheduling is claimed without a representative benchmark.

See [Runtime SPI and shutdown contract](../../docs/dispatchers.md) and the [official Disruptor user guide](https://lmax-exchange.github.io/disruptor/user-guide/index.html).
