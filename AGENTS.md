# Repository development rules

- Production modules and published libraries must not depend on Spring or contain Spring integration code. Spring is allowed only in tests or demos; keep its dependencies test-scoped and its adapter code out of production source sets and published JARs.

- Production code, tests and examples must not use `java.util.concurrent.CompletableFuture` or `CompletionStage`. Use explicit callbacks, lifecycle state and narrowly scoped latches/conditions for blocking coordination. Do not reintroduce completion chains through wrappers or implicit asynchronous JDK APIs.
- RPC core claims completion, removes pending state and cancels the timeout before invoking the application dispatcher. The dispatcher chooses the route group/key and any captured context. RPC core must not choose business threads or retain response payloads after callback return.
- Keep game-data's direct asynchronous encoding and buffered saving model. Do not add mandatory snapshots, WAL, durable-response waits or cross-process player ownership without a new explicit request.
- Player login, authentication and server placement policies belong to the game application.
- Retain bounded write admission before EventLoop submission, reference ownership and exactly-once accepted-write completion.
- Run relevant module tests and `mvn verify` for cross-module API changes. Check all Java source/test files for prohibited async types before finishing.
