<a id="项目协作规则"></a>

# Project Collaboration Rules

**[English](AGENTS.md)** | [简体中文](AGENTS.zh-CN.md)

This file applies to the entire mana3 repository. For framework requirements, design feedback, fixes, and optimizations, proactively identify the appropriate contract layer and update its documentation. The user need not specify the document location each time.

<a id="项目基线"></a>

## Project baseline

- OGBS = Open Game Backend Specification. Start at the [OGBS documentation index](docs/ogbs/README.md).
- Java uses JDK 25, Maven groupId `cn.managame`, packages `cn.managame.*`, and source directories under `cn/managame/`.
- Organize subpackages by responsibility and separate public APIs from internal implementation. Create Maven modules for independent releases, dependency boundaries, or actual requirements, not mechanically for every subpackage.
- Java Network publishes connection interfaces and Netty implementation together in `game-network`, organized into connection, connector, error, netty, and an independent http package. Attributes use Netty AttributeKey directly. NetworkServer/NetworkClient and their package-private implementation live in netty; HttpServer/HttpServerBuilder and package-private HTTP processing live in http without wrapping the long-connection entries or using ConnectionHandler. Do not retain custom attribute or Acceptor/Connector abstractions.
- Java RPC uses node, message, call, transport, error, and netty packages. Preserve package-private internals; do not expand public APIs merely to split packages.
- Java RPC Netty codecs and integration live in `cn.managame.rpc.netty` inside `game-rpc`, published in the same artifact as the RPC core.
- Java Data publishes repositories, MySQL/MongoDB adapters, and MySQL logs in one `game-data` artifact. Keep semantic and Java development specifications separate. Clearly label unimplemented, unverified, and undecided capabilities.

<a id="每个组件必须配套两层规范"></a>

## Every component requires two specification layers

Every framework component, including game-core, game-network, game-runtime, game-data, game-rpc, and future components, must maintain two distinct specification documents:

1. **Specification**: language-independent responsibilities, models, observable behavior, ordering, lifecycle, ownership, errors, cancellation, time, and compatibility.
2. **Java Development Specification**: Java public types/APIs, defaults, exceptions, packages and Maven dependencies, threading and resource mechanisms, internal constraints, extension integration, and validation requirements implementing the standard.

Both documents must link to each other and appear in docs/ogbs/README.md, the project README, and the component README. An API list, module README, or external record cannot replace a Java development specification. Do not mix Java-only requirements into the general standard. Shared Core follows the same separation.

Use docs/ogbs/OGBS-<Component>-1.0.md and docs/ogbs/OGBS-<Component>-Java-25-Specification-1.0.md. Maintain one contract with English and Chinese texts in the existing documents, not competing design versions.

When adding a component or changing a public contract, update code, both specification layers, examples, and relevant tests in the same task. Unimplemented components also need both design documents, clearly marked as pending implementation; do not claim availability or verification. Follow the ownership rules below for internal-only optimizations; unchanged contracts do not require mechanical specification edits.

<a id="规范详细程度与设计记忆"></a>

## Specification depth and design memory

Specifications must enable maintainers unfamiliar with the design background to continue implementation and review. Lists of clauses or method signatures are insufficient. For each public behavior, document as applicable:

- Preconditions, inputs/outputs, normal flow, and observable ordering.
- Rejection, exception, cancellation, shutdown, and race outcomes; whether results imply execution or persistence.
- Object/resource ownership, threads/contexts, defaults, and capacity/time boundaries.
- At least one concrete example of an easily misunderstood boundary, with source and validation entry points.
- Confirmed tradeoffs, rationale, and reconsideration triggers. Distinguish unimplemented, unverified, out-of-scope, and undecided items.

Use existing confirmed clause IDs and text as the baseline. Discuss only new details or conflicts, without asking the user to reconfirm entire component designs. Do not revive design proposals superseded by later conclusions. When the user explicitly changes a design, update the rules, rationale, examples, code, and validation together.

Documents must be self-contained and directly describe component responsibilities, current contracts, and design rationale. Cite repository specifications, source, or tests as supporting evidence; external discussion records must not serve as normative authority.

Keep rationale and flow explanations in the relevant specification sections, not parallel Specs. Rationale explains clauses and does not independently add MUST requirements. Do not present undecided options as active requirements. Length does not establish completeness; real boundaries and failure paths do.

<a id="自主判断文档归属"></a>

## Decide documentation ownership autonomously

First ask whether other language implementations must obey the requirement, then whether observable behavior or interoperability is affected. Use this table; do not ask the user merely to classify documentation.

| Change | Update |
| --- | --- |
| Language-independent responsibilities, models, ordering, lifecycle, ownership, backpressure, errors, cancellation, time, compatibility | The component's OGBS Specification |
| Java public types, signatures, annotations, exceptions, defaults, threading, Netty integration, Maven dependencies, packages | The component's Java development / implementation specification |
| General behavior changes needing Java implementation support | Both layers, consistently |
| Cross-process fields, byte order, lengths, identifiers, encoding, protocol versions | RPC Wire Profile; related component and Java codec documentation when needed |
| Shared Metadata, error codes, or conventions | The relevant OGBS Core section; component documents reference it |
| Internal refactoring, algorithm replacement, optimization without public contract changes | Code and necessary tests; Java documentation only for durable implementation conventions |
| Module composition, build/usage entry points, directories | Architecture overview, READMEs, dependency diagrams, links |

Examples:

- “Same-Route execution must be serial” belongs in the Runtime Specification; “bind contexts using ScopedValue” belongs in the Java implementation specification.
- “Changing business time does not automatically reschedule tasks” belongs in the Runtime Specification; `GameTime.setClock(Clock)` belongs in the Java implementation specification.
- Publishing RPC Netty code in game-rpc is a Java layout decision, not a requirement for other languages to depend on Netty.
- Reducing allocations without changing behavior is usually an implementation optimization. Changes to queue capacity, rejection, or callback order require revisiting contracts and documentation ownership.

Do not elevate Java techniques into cross-language requirements. Do not document general behavior only in Java documents, leaving other implementations unconstrained.

<a id="文档位置与单一来源"></a>

## Document locations and single source of truth

| Content | Document |
| --- | --- |
| Network semantics | [OGBS Network Specification](docs/ogbs/OGBS-Network-1.0.md) |
| Network Java implementation | [Network Java Development Specification](docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) |
| RPC semantics | [OGBS RPC Specification](docs/ogbs/OGBS-RPC-1.0.md) |
| RPC Java implementation | [RPC Java Development Specification](docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.md) |
| Runtime semantics | [OGBS Runtime Specification](docs/ogbs/OGBS-Runtime-1.0.md) |
| Runtime Java implementation | [Runtime Java Development Specification](docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md) |
| Data semantics | [OGBS Data Specification](docs/ogbs/OGBS-Data-1.0.md) |
| Data Java implementation | [Data Java Development Specification](docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md) |
| Shared Metadata and error-code standard | [OGBS Core](docs/ogbs/OGBS-Core-1.0.md) |
| Core Java development | [Core Java Development Specification](docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md) |
| RPC byte layout | [RPC Wire Profile](docs/rpc-wire.md) |
| Component composition and build entry points | [Architecture overview](docs/architecture.md), [Project README](README.md) |

Maintain one primary definition per rule and reference it elsewhere. Core's standard and Java development specification are separate: maintain shared semantics and Java bindings in their respective documents. Do not create parallel specifications to avoid editing existing ones.

<a id="处理后续需求的流程"></a>

## Workflow for subsequent requirements

1. Read the affected component's standard, language implementation specification, and code. Compare the request with the latest confirmed conclusions; do not use superseded design proposals.
2. Decide documentation layers autonomously. Implement explicit requests directly; evaluate exploratory suggestions before making them active MUST requirements.
3. For explicit design changes, update affected rules, code, examples, and tests. Fix code that violates an existing contract instead of rewriting the specification to hide the defect.
4. Briefly explain and clarify only unresolved ambiguities materially affecting business behavior, compatibility, or scope. Decide document ownership, routine details, and inferable choices yourself.
5. Remove obsolete wording and repair cross-references, source links, dependencies, and examples. Explain defaults, failure paths, boundaries, and compatibility effects, not just successful flows.
6. After validation, briefly report results, documentation ownership, and verification. Never label unfinished work as implemented or verified.

<a id="验证要求"></a>

## Validation requirements

- Test behavioral contracts, focusing on ordering, rejection, exceptions, shutdown, and concurrency boundaries. Do not test mechanical renames by restating implementation.
- For package moves, module merges, Maven dependencies, or cross-component integration, run `mvn clean verify` from the root to prevent stale class files from hiding issues.
- For local implementation changes, test the module and dependencies first, then integration appropriate to the impact.
- For documentation-only changes, check naming, consistency, and local links. If complete runnable examples change, verify compilation and execution without repeatedly running unrelated tests.

<a id="双语文档"></a>

## Bilingual documentation

- Every repository document requires complete English and Simplified Chinese versions. The existing unsuffixed `.md` path is the default English entry; its Chinese counterpart uses `.zh-CN.md` in the same directory, including README and AGENTS.
- Put an English / Simplified Chinese language switch near the top. Link to matching-language documents within each language. Keep source-code and external links unchanged.
- Both languages express the same contract, not separate designs. Update both in the same task, preserving clause IDs, API names, defaults, constraints, examples, failure paths, and implementation/validation status.
- English-first display does not permit semantic changes or removal of Chinese detail. Resolve translation discrepancies against confirmed design and code, then fix both texts.
- When adding or renaming documents/headings, check pairs, switches, local files, and section anchors. Preserve compatibility anchors for existing inbound links when needed.
