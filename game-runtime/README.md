# game-runtime

**[English](README.md)** | [简体中文](README.zh-CN.md)

<a id="规范文档"></a>

## Specifications

| Specification (language-independent) | Java Development Specification |
| --- | --- |
| [OGBS Runtime Specification](../docs/ogbs/OGBS-Runtime-1.0.md) | [OGBS Runtime Java Development Specification](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md) |

Component behavior and Java implementation are maintained separately in these specifications. This document is the usage entry point.

Java 25 business runtime. Maven coordinates: `cn.managame:game-runtime`; internal functionality is organized by package.

<a id="目录与职责"></a>

## Layout and responsibilities

```text
cn.managame.runtime
├── GameRuntime / GameRuntimeBuilder  Public entry points and configuration
├── context                          Context hierarchy, defaults, read-only Contexts
├── route                            Domain, key extraction/registration, RouteCallback
├── executor                         RouteExecutor SPI, bindings, platform/virtual threads
├── protocol                         Protocol descriptions, registration, Req → Res mapping
├── handler                          Handler annotations
├── event                            Local Event, EventBus, annotations
├── timer                            One-shot Timer, Cron annotations, management interfaces
├── time                             Replaceable business wall clock: GameTime
├── error                            Runtime errors, handlers, dispatch exceptions
└── internal                         Registration compilation, context binding, dispatch, scheduling
```

API subpackages contain their respective public types. internal assembles the runtime and is not a supported application interface. GameRuntimeBuilder collects configuration, then delegates validation and compilation to RuntimeCompiler instead of containing the entire runtime implementation. Context binding and Timer/Cron management are separate from business dispatch.

This package reorganization added no Maven publication units. Imports such as `cn.managame.runtime.HandlerContext` must become `cn.managame.runtime.context.HandlerContext`; `import cn.managame.runtime.*` does not import subpackages. Repository examples and tests have been migrated.

<a id="gametime-与调度"></a>

## GameTime and scheduling

```java
import cn.managame.runtime.time.GameTime;
import java.time.*;

GameTime.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
long now = GameTime.currentTimeMillis();
LocalDateTime local = GameTime.now(ZoneId.of("Asia/Shanghai"));
runtime.cron().rescheduleAll();
GameTime.resetClock();
```

GameTime is the process-wide business wall clock. setClock/resetClock do not automatically change existing schedules. Tests should restore the clock in finally or AfterEach. RuntimeTimer still fires after real elapsed delay. Applications convert absolute business deadlines into Duration and use cancel + schedule when recalculation is needed.

Cron computes its next time using current GameTime, then uses RuntimeTimer. Manage entries by declaring class and method name:

```java
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
runtime.cron().rescheduleAll();
```

SystemCron is an application-defined class. cancel stops future cycles; reschedule can resume cancelled entries; rescheduleAll includes every registered entry. A method that has started may finish; old-generation tasks cannot overwrite new schedules. Cron computes its next cycle after the current method ends. Exceptions or admission failures do not retry that cycle, but future cycles continue.

<a id="文档与验证"></a>

## Documentation and validation

- [OGBS Runtime Specification](../docs/ogbs/OGBS-Runtime-1.0.md)
- [Java 25 Development Specification](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md)
- [GameTime](src/main/java/cn/managame/runtime/time/GameTime.java)
- [CronScheduler](src/main/java/cn/managame/runtime/timer/CronScheduler.java)
- A complete RPC / Runtime integration example is not implemented; current integration examples are in the Java specification.

Run `mvn -pl game-runtime -am test` from the root. After package changes, run `mvn clean verify` for full integration validation.
