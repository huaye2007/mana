# Application-defined route types

Route identity is now `Class<? extends RouteType> + long key`. This supersedes the fixed RouteGroup section in the archived [v0.1 specification](OGBS%20Game%20Runtime%20Specification%20v0.1.md).

路由身份现在由游戏项目定义的类型标记与 long key 组成。本说明替代原始 v0.1 草案中的固定 RouteGroup 约定；草案原文保留用于追溯。

~~~java
public final class GameRoutes {
    private GameRoutes() {}
    public interface Player extends RouteType {}
    public interface Guild extends RouteType {}
    public interface Battle extends RouteType {}
}

@Handler(routeType = GameRoutes.Player.class)
public class Items {
    @HandlerMethod
    public void use(RoleId roleId, UseItemReq request) {
        // Player state belongs to GameRoutes.Player.class + roleId.value().
    }
}

// Route key resolution is configured per exact application type.
GameRuntime runtime = GameRuntime.builder()
    .protocols(protocols)
    .parameters(parameters)
    .defaultRoute(GameRoutes.Player.class, RoleId.class, RoleId::value)
    .handler(new Items())
    .build();

runtime.dispatch(GameRoutes.Guild.class, guildId, () -> updateGuild());
~~~

## Contract / 约定

- Within one GameRuntime, tasks admitted to the same exact type and key execute serially in FIFO order. Different types or keys may execute concurrently. Existing same-route inline Event semantics remain unchanged.
- 同一个 GameRuntime 内，同一精确类型与 key 的已准入任务严格 FIFO 串行；不同类型或 key 可以并发。同 Route 的同步 Event 仍按原规则内联执行。
- Types are class literals, not instances or strings. Marker interfaces or classes may be used; Runtime does not instantiate them. No separate type registration, name lookup or runtime bytecode generation is required.
- 类型使用 class 字面量，可声明为标记接口或类；不需要实例化或单独注册。Runtime 不按字符串名称查找类型。默认路由解析器配置只绑定解析规则，不是类型白名单。
- Identity uses exact Class identity, including its class loader. Equal simple names do not merge routes; inheritance does not merge parent and child types. Reloading a type with another class loader creates a different namespace.
- 类型身份包含类加载器，同名类型、父子类型不会自动合并。类型标记只在本地 Runtime 内使用，不是跨进程协议 ID，也不提供跨 Runtime 或跨服务器的串行保证。
- All mutable state must have a clear owning route. Route timers, callbacks, events and commands which access it through that same type/key. Move login-to-player or scene-to-scene ownership explicitly in application logic.
- 同一份可变状态的所有修改应归属同一 Route；账号登录到玩家状态、场景切换等所有权移交由游戏逻辑明确处理。
- A route type supplies no priority, thread affinity, Tick budget or real-time guarantee. Execution policies remain separate from identity.
- 类型仅表示状态所属的命名空间，不自带优先级、固定线程、Tick 预算或实时保证。

## API migration / API 迁移

| Previous | Current |
| --- | --- |
| Runtime-owned RouteGroup enum | Application-defined marker types extending/implementing RouteType |
| @Handler(routeGroup = RouteGroup.PERSONAL) | @Handler(routeType = GameRoutes.Player.class) |
| @EventHandler / @Cron routeGroup | @EventHandler / @Cron routeType |
| dispatch(group, key, action) | dispatch(GameRoutes.Player.class, key, action) |
| schedule(group, key, delay, callback) | schedule(GameRoutes.Player.class, key, delay, callback) |
| defaultRoute(group, source, resolver) | defaultRoute(GameRoutes.Player.class, source, resolver) |
| new Route(group, key), Route.group() | new Route(GameRoutes.Player.class, key), Route.type() |
| HandlerContext.routeGroup() | HandlerContext.routeType() |
| register(CommandHandlerInvocation.class, Value.class, resolver) | register(Value.class, resolver) |
| registerRouteSource(CommandHandlerInvocation.class, Id.class, resolver) | registerRouteSource(Id.class, resolver) |
| ParameterResolver<I,T> | ParameterResolver<T>, receiving CommandHandlerInvocation |
| ExecutionDomain.limits(RuntimeLimits) | limits(DomainLimits) and routeShards(int) |
| GameRuntime.Builder.limits(RuntimeLimits) | domainLimits(DomainLimits), routeShards(int), timerOptions(TimerOptions) |

These changes require recompiling 0.1.0-SNAPSHOT clients. The old Runtime-wide limits bridge remains deprecated and applies all fields; explicit domains accept only DomainLimits. CallbackDefinition still accepts a Route; implicit callbacks still capture the current route. RouteTask.onComplete(listener) captures the calling handler's Route in the same Runtime; its explicit Route overload is usable externally. The returned task describes notification execution.

这是 0.1.0-SNAPSHOT 的源码与二进制变更，调用方迁移后需要重新编译。Runtime 全局旧容量桥暂保留并弃用，会完整应用所有字段；显式域只接受 DomainLimits。CallbackDefinition 仍接受 Route，普通 callback 仍捕获当前 Route。RouteTask.onComplete(listener) 捕获调用方在同一 Runtime 内的当前 Route；外部调用使用显式 Route 重载，返回任务表示通知本身执行完成。

Do not mechanically replace every SHARED use with one Guild type: guilds, rankings, activities and other independent state owners should use their own namespaces. Likewise choose Account or Player for former LOGIN work according to the state actually accessed.

旧 SHARED 应按实际状态归属拆成 Guild、Rank、Activity 等类型；旧 LOGIN 应根据实际访问的状态选择 Account 或 Player。这些名称均由游戏项目决定，Runtime 不内置业务类型。
