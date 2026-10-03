# OGBS Container Integration Specification 1.0

**[English](OGBS-Spring-1.0.md)** | [简体中文](OGBS-Spring-1.0.zh-CN.md)

Language-independent responsibilities for optional application-container integration. The repository implements these in game-spring; companion: [Java development specification](OGBS-Spring-Java-25-Specification-1.0.md). Runtime/Data contracts remain authoritative and framework cores do not require a container.

## 1. Discovery and assembly

**S-REGISTER-01**: Discover business handlers, event listeners, HTTP endpoints, recurring task owners and repositories at startup within application-selected scope. Freeze registrations at Runtime/Data build. Discovery does not infer authentication, Domain identity, Route Key, business identity or serialization from class names. Application policy provides them explicitly. Invalid signatures, duplicate registrations and invalid scopes fail startup rather than silently omitting invalid declarations.

**S-REGISTER-02**: Register each discovered singleton owner once by object identity. An unrelated object must not be instantiated merely to determine whether it owns recurring tasks. Products whose type cannot be determined before initialization require explicit identifiable registration. Owners cannot synchronously require the same Runtime being constructed; this circular dependency rejects startup. Dynamic post-build discovery is outside this profile.

**S-DATA-01**: Container injection must expose the same initialized Repository instance owned by Data. Storage initialization precedes injection callbacks and business access. Preserve application names and qualifiers. Existing Data constructor/type/identity requirements apply. The container owns the application's data source; Data borrows it. Data cannot be closed while admitted Runtime work still needs repositories.

## 2. Lifecycle and failure

**S-HTTP-01**: Container-managed HTTP assembly connects discovered HTTP endpoints to the same Runtime admission, Domain and Route rules as ordinary business messages. Applications configure a listener port and an optional common context path; endpoint declarations remain relative to that prefix. Prefix matching uses a complete raw path segment, preserves the query, and never silently decodes or redirects a different path. Requests outside the prefix receive 404 without business execution. Configuration supplies size/time limits; invalid configuration and bind failure reject application startup rather than silently disabling the listener. No extra business executor is introduced.

**S-HTTP-02**: Start the managed listener after business registration and container singleton initialization. Default activation requires a discovered HTTP endpoint owner; explicit configuration can enable or disable it. The container owns the listener and resources it creates, while explicitly supplied network groups remain caller-owned. Stop it after the S-CLOSE-01 Runtime drain barrier, before storage destruction. Execution drain does not promise completion of response delivery or a deferred response outside registered Runtime work. Applications needing that boundary must register their continuations. Failed startup releases the managed listener and container-owned Runtime resources. The low-level Network HTTP API remains available for applications that assemble transport themselves.

For example, prefix `/game` maps `/game/status?id=7` to the registered `/status` endpoint with the same `id=7` query; `/games/status` and `/game%2Fstatus` do not match. The Handler observes the prefix-relative request URI. Listener lifecycle and path/reference validation are exercised by [HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java); the Java binding defines property names and defaults.

**S-CLOSE-01**: On management-context shutdown, first stop Runtime admission and future scheduling, then wait for registered work and continuations, then close Runtime and dependent network/data resources. Preserve [RT-DRAIN](OGBS-Runtime-1.0.md#103-admission-stop-drain-and-observational-diagnostics) boundaries: delivery, unregistered callbacks and persistence are separate. Lost callbacks or hung business work can keep shutdown pending; this profile does not forcibly discard work after a deadline.

For example, an admitted player Handler may still update its Repository after shutdown begins. Repository destruction waits until the Handler and its registered callbacks finish, then Data performs final write-back. New player requests reject at Runtime admission. Closing from that Handler's own Route is forbidden because it would wait for itself.

On startup failure, caller-owned resources remain subject to container cleanup; successful Runtime construction transfers executor lifecycle as specified by Runtime. Default event binding conflicts fail startup and close the newly constructed Runtime. Container integration neither sends unified TCP/RPC responses nor installs a business scheduler.

## 3. Validation and limits

Java validation entries: [CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java), [RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java), [repository integration tests](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java). Proxy policy and concrete shutdown delivery are defined in the Java binding. Production resource exhaustion, hung drivers, arbitrary container implementations, cross-process shutdown and hot registration are not verified by these tests.
