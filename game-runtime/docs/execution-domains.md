# Execution domains and entity queues / 执行域与实体队列

Route identity remains `Class<? extends RouteType> + long key`. Each entity has an independent FIFO queue. A route type is bound to an execution domain, which owns its workers, admission counters and ready queue. Types sharing a domain share resources, not entity state or serialization.

每个实体拥有独立 FIFO 队列。Route 类型绑定执行域，执行域管理工作线程、任务额度和就绪队列。多个类型可以共用执行域，但实体的串行边界仍由完整的类型与 key 决定。

Custom RouteDispatcher backends and the optional Disruptor adapter are documented in [backend SPI and shutdown](dispatchers.md).

## Configure / 配置

~~~java
var login = ExecutionDomain.virtual("login")
    .maxConcurrentRoutes(128)
    .maxTasks(2000)
    .maxTasksPerRoute(32)
    .callbackReserve(128, 4)
    .tasksPerTurn(16)
    .build();

var gameplay = ExecutionDomain.platform("gameplay")
    .threads(4)
    .maxTasks(20000)
    .maxTasksPerRoute(256)
    .callbackReserve(2000, 32)
    .tasksPerTurn(32)
    .build();

var battle = ExecutionDomain.platform("battle")
    .threads(4)
    .maxTasks(20000)
    .maxTasksPerRoute(256)
    .callbackReserve(2000, 32)
    .tasksPerTurn(32)
    .build();

GameRuntime runtime = GameRuntime.builder()
    .executionDomain(GameRoutes.Account.class, login)
    .executionDomain(GameRoutes.Player.class, gameplay)
    .executionDomain(GameRoutes.Guild.class, gameplay)
    .executionDomain(GameRoutes.Battle.class, battle)
    .timerOptions(new TimerOptions(100_000, 1024))
    // protocols, parameter resolvers, handlers...
    .build();
~~~

Numbers are illustrative, not capacity recommendations. Marker types are declared by the game project.

数值仅用于演示，需根据业务和测量调整；GameRoutes 中的类型由游戏项目声明。

- A platform domain has its own fixed-size pool and uses that executor's work queue directly. Battle workers never execute work from a different domain.
- A virtual domain starts a fresh virtual thread per admitted batch. maxConcurrentRoutes bounds running batches (including I/O waits and short handoffs); queued entities do not each create a waiting virtual thread. Virtual domains still use the JVM's shared virtual-thread scheduler.
- Each turn processes at most tasksPerTurn messages (default 64). A nonempty entity queue goes back to its ready queue. BALANCED has no physical-thread affinity or forced migration; KEY_AFFINITY retains its fixed worker. Neither interrupts a running business method.
- The routeShards setting selects only a short-lived queue-management lock; worker affinity, when enabled, uses a separate key hash. Different entities can run concurrently even if their queue metadata shares a shard.
- BALANCED 策略下，同一实体在不同批次之间可以换工作线程；KEY_AFFINITY 策略下保持固定工作线程。两者都保证同一时刻只有一个业务任务执行。任务数预算用于批次轮转，不是强制抢占或耗时上限。
- 平台线程 Handler 等待未完成的 RouteTask 会被拒绝，虚拟线程 Handler 等待当前域内未完成任务同样被拒绝；业务也不应在平台线程域内阻塞等待 I/O；有界工作线程全部等待时，即使没有循环依赖也可能无法推进。使用异步完成后回到原 Route 的方式，并处理状态版本变化。

Capacity can also be supplied as a value:

~~~java
var gameplay = ExecutionDomain.platform("gameplay").threads(4)
    .limits(new DomainLimits(20000, 256, 2000, 32))
    .routeShards(64)
    .build();
~~~

`limits` 设置当前域的任务容量与 Callback 保留槽位；`routeShards` 独立设置队列管理分片。Timer 容量和每次轮询预算只通过 `GameRuntime.Builder.timerOptions` 配置，执行域不接受会被忽略的 Timer 字段。

## Scheduling policy / 调度策略

Thread mode and scheduling policy are separate. A platform domain can choose either policy:

| Policy | Ready queues | Worker selection | Suitable use |
| --- | --- | --- | --- |
| BALANCED (default) | One domain queue of runnable entity batches | Any available domain worker | Player/guild workloads with changing active populations |
| KEY_AFFINITY | One ready queue per platform worker | Stable hash of the long route key | Rooms/scenes requiring stable thread ownership |

~~~java
var rooms = ExecutionDomain.platform("rooms")
    .threads(roomThreads)
    .scheduling(ExecutionDomain.Scheduling.KEY_AFFINITY)
    .tasksPerTurn(32)
    .build();

var players = ExecutionDomain.platform("players")
    .threads(playerThreads)
    .scheduling(ExecutionDomain.Scheduling.BALANCED)
    .tasksPerTurn(32)
    .build();

builder.executionDomain(GameRoutes.Room.class, rooms)
       .executionDomain(GameRoutes.Player.class, players);
~~~

BALANCED does not bind an entity to a thread. It dispatches ready entity batches, rather than permanently assigning player IDs to workers. As players leave or arrive, remaining ready work can be taken by available workers. It does not split one entity's business task or guarantee perfectly equal CPU load.

KEY_AFFINITY mixes the full 64-bit key and assigns it to one fixed worker partition. The worker count and mapping do not change during a Runtime's lifetime; empty entity queue removal does not change the mapping. Distinct types sharing one affinity domain and the same numeric key choose the same worker but retain separate entity queues. Queue metadata routeShards is independent of this worker partition count.

固定映射基于完整 long key 的混合哈希，不直接依赖 ID 的低位。Room 的 Command、Event、Timer、Cron 和 Callback 都使用同一映射；路由 ID 的来源和参数位置仍由绑定规则决定。正常运行时保持同一平台工作线程；不提供跨进程重启或工作线程故障后的线程对象身份保证。

Both strategies keep FIFO, non-overlapping business execution for the same complete Route. Both use finite entity batches. Affinity batches only rotate among entities on the same worker; idle workers do not steal another partition's rooms. A blocked/hot room therefore affects other rooms in its partition. Balanced batches may be picked up by a different worker.

同一个实体仍然独立串行，改变的只是它的下一批任务由谁领取。固定分片可提供线程亲和性，但房间热点仍会造成分片负载不均；动态调度提高可用线程承接任务的灵活性，也有调度成本。尚未对这两种策略做正式性能对比，不能把固定 hash 视为所有负载下必然最快。

KEY_AFFINITY is rejected for virtual domains: the public API must not promise platform-thread affinity for virtual threads. Scheduling policy is immutable after build, and execution-domain metric snapshots include the configured policy.


## Route ID is independent of parameters / 路由 ID 与方法参数解耦

All decoded protocols use the same entry: runtime.command(decodedRequest, connection). The adapter does not select a handler, route ID or worker for each protocol.

~~~java
@Handler(routeType = GameRoutes.Guild.class)
public class GuildHandler {
    @HandlerMethod
    public void donate(DonateReq request, RoleId actor, GuildId guild) {
        // The third parameter supplies a business value; position does not select routing.
    }

    @HandlerMethod
    public void refresh(RefreshReq request) {
        // GuildId need not appear in the signature at all.
        long guildId = HandlerContexts.current().routeKey();
    }
}

// Register once at startup. guildIdentityResolver belongs to the game project.
var parameters = ParameterResolverRegistry.builder()
    .registerRouteSource(GuildId.class,
        invocation -> guildIdentityResolver.resolve(invocation.request(), invocation.connection()))
    .register(RoleId.class,
        invocation -> roleResolver.resolve(invocation.connection()))
    .build();

// Alongside protocols, execution-domain mapping and handler registration:
builder.parameters(parameters)
    .defaultRoute(GameRoutes.Guild.class, GuildId.class, GuildId::value);
~~~

ParameterResolver<T> receives CommandHandlerInvocation only; the registry binds exact semantic types and rejects duplicate registrations immediately. The selected route identity is resolved once before admission, using thread-safe, nonblocking identity access. Other semantic parameters are resolved inside the owning route. If GuildId is present in the method, the already-resolved value is reused. No first-parameter convention or per-message annotation scan is involved.

路由 ID 可以位于任何参数位置，也可以完全省略。Runtime 按预绑定的源类型解析一次身份；方法需要该语义参数时复用解析值，其余参数按声明顺序注入。身份解析阶段不能访问受实体队列保护的可变状态。

Commands, cross-route events, timers, crons and callbacks use the destination route's domain. Same-route inline events remain inside the current invocation. An explicit callback route override selects that destination's domain.

## Admission and lifecycle / 准入与生命周期

maxTasks counts both running and queued messages, shared across all types in that domain. maxTasksPerRoute limits one entity. Callbacks can use additional domain/per-route reserves; ordinary tasks cannot. Explicit domains report DOMAIN_CAPACITY rather than GLOBAL_CAPACITY. There is no shared task quota that login can consume from gameplay. The sum of configured domain maxima bounds total accepted task count.

每个执行域拥有独立任务额度和 Callback 保留槽位。就绪队列按实体登记，最多受已接受任务量约束；没有满载时在调用线程执行业务的回退策略。配置的域总容量之和必须在 int 范围内。

ExecutionDomain is an immutable configuration, not an open executor. Reuse the same configuration instance to share a domain across types inside a Runtime. Different Runtime instances always own different workers. Different configuration objects may not use the same domain name within one Runtime. Binding is immutable after build.

Shutdown stops admission to all domains before waiting for accepted entity queues to drain. Mandatory dispatcher reschedule preserves continuation capacity; an admitted entity cannot fail merely because an ingress queue is full. Workers close only after queues drain. Backend faults in shutdown or termination checks are isolated per domain and appear in ShutdownReport.backendFailures(). A never-returning business method is reported by bounded close(Duration); no-argument close uses a configurable 30-second deadline. Completion listeners registered but not yet submitted do not belong to the admitted drain set and may be rejected once shutdown starts.

调用 runtime.executionDomains() 可查看各域调度策略、运行批次、就绪实体以及任务计数和耗时；runtime.metrics() 提供聚合指标。域内 tasks 快照的 timer 字段为 0，定时器统计只在 Runtime 聚合指标中提供。

## Compatibility and limits / 兼容与边界

Without executionDomain registrations, Runtime uses a default virtual domain (256 concurrent batches, 64 messages per turn). GameRuntime.Builder.domainLimits(DomainLimits) and routeShards(int) configure this default domain; they supply no business resource isolation.

Once any explicit domain is configured, every used route type requires a mapping. Missing mappings for Command/Event/Cron fail at build; dynamic dispatch, timer and explicit callback destinations are checked at their entry points. There is no silent fallback for unconfigured battle routes.

兼容模式的默认虚拟线程域现在同样有并发和批次上限。要隔离登录、在线业务和战斗，必须显式配置执行域。

ExecutionDomain.limits(DomainLimits) accepts only domain/per-route task capacities and callback reserves. Configure queue-management shards separately with routeShards(int), default 64. TimerOptions(maxTimers, maxTimersPerPoll) belongs exclusively to GameRuntime.Builder.timerOptions(...). Explicit domains no longer accept RuntimeLimits; the deprecated GameRuntime.Builder.limits(RuntimeLimits) bridge applies every old field to default-domain capacity, shards or timers. Pending business timers remain Runtime-wide.

执行域隔离了业务工作线程和任务额度；定时器容量、定时扫描线程、异常观察器、CPU、堆和 GC 仍可能共享。路由身份解析、网络解码也发生在域内执行之前。这不是进程级隔离或零相互影响保证；尤其不要在身份解析或异常观察器中执行耗时操作。

Historical benchmarks predate this scheduler change. No previous throughput or latency number should be treated as a measurement of this version.
