# 项目协作规则

[English](AGENTS.md) | **[简体中文](AGENTS.zh-CN.md)**

本文件适用于 mana3 整个仓库。后续处理框架需求、设计意见、问题修复和优化时，必须主动判断它们属于哪一层约定，并同步维护对应文档；不要求用户每次指定文档位置。

## 项目基线

- OGBS = Open Game Backend Specification，规范入口为 [OGBS 文档索引](docs/ogbs/README.zh-CN.md)。
- Java 实现使用 JDK 25，Maven groupId 为 `cn.managame`，包名为 `cn.managame.*`，源码目录为 `cn/managame/`。
- 类按职责划分子包，公开 API 与内部实现分开。是否拆成 Maven 模块由独立发布、依赖边界和实际需求决定，不为每个子包机械创建 artifact。
- Java Network 的连接接口与 Netty 实现在 `game-network` 内统一发布，按 connection、connector、error、netty 以及独立 http 包组织；属性直接使用 Netty AttributeKey。NetworkServer/NetworkClient 及其包级内部实现位于 netty，HttpServer/HttpServerBuilder 与包级 HTTP 处理位于 http，不包装长连接入口，不使用 ConnectionHandler。不再保留自定义 attribute 或 Acceptor/Connector 抽象。
- Java RPC 按 node、message、call、transport、error、netty 分包；保留内部类的包级封装，不为拆包而扩大公共 API。
- Java RPC 的 Netty 编解码与适配位于 `game-rpc` 的 `cn.managame.rpc.netty` 包，与 RPC 核心在同一 artifact 发布。
- Java Data 在 `game-data` 内统一发布，包含 Repository、MySQL/MongoDB 适配和 MySQL 日志；语义规范与 Java 开发规范 分层维护。未实现、未验证或尚未确定的能力必须明确标注状态。

## 每个组件必须配套两层规范

每个游戏服务器框架组件（包括 game-core、game-network、game-runtime、game-data、game-rpc 以及未来新增组件）都必须维护两份独立的规范正文：

1. **标准规范（Specification）**：语言无关的职责、模型、可观察行为、顺序、生命周期、所有权、错误、取消、时间与兼容性。
2. **Java 开发规范（Java Development Specification）**：承接标准的 Java 公开类型/API、默认配置、异常形式、包与 Maven 依赖、线程和资源机制、内部实现约束、扩展接入及验证要求。

两份文档必须互相链接，并同时列入 docs/ogbs/README.md、项目 README 和组件 README。不能用一份 API 列表、模块 README 或外部记录代替 Java 开发规范；也不能在通用标准中混入仅限 Java 的实现要求。共享 Core 同样遵守两层分离。

路径统一为 docs/ogbs/OGBS-<Component>-1.0.md 和 docs/ogbs/OGBS-<Component>-Java-25-Specification-1.0.md。在已有文档上维护唯一契约及其英文、中文正文，不另建相互竞争的设计版本。

新增组件或修改公开契约时，代码、两层规范、示例和相关测试应在同一次任务中同步完成。尚未实现的组件也需要两份设计规范，但必须标注待实现，不能声明已提供或已验证。纯内部优化按下文归属规则处理，无契约变化时不要求机械改写规范。
## 规范详细程度与设计记忆

规范应足以让不了解设计背景的维护者继续实现和评审，不能只列条款或方法签名。每个涉及公开行为的设计应按适用范围写清：

- 前置条件、输入/输出、正常流程及可观察顺序。
- 拒绝、异常、取消、关闭和并发竞争时的结果；结果是否意味着已经执行或持久化。
- 对象与资源所有权、线程/上下文、默认值及容量/时间边界。
- 至少一个能说明易错边界的具体例子，以及对应的源码/验证入口。
- 已确认的取舍、采用理由和重新评估的触发条件；未实现、未验证、非目标与待决定事项分别标明。

已确认规则以现有条款 ID 和正文作为后续工作的基线。补充细节时只讨论真正新增或与基线冲突的部分，不要求用户重新确认全部组件设计。已被替代的设计提议不得作为新需求恢复；已有设计被用户明确改变时，同步改正文、理由、示例、实现及相关验证。

文档必须独立可读，直接说明组件职责、现行契约与设计理由；依据应引用仓库内规范、源码或测试，不得以外部讨论记录作为规范依据。

理由和流程说明放在相应规范章节，不另建平行版 Spec。理由解释条款，不自行增加 MUST；不能把尚未确定的可选方案写成已生效要求。文档长度不是完整性指标，实际边界和失败路径才是。


## 自主判断文档归属

先判断“其他语言实现是否也必须遵守这项要求”，再判断“是否影响可观察行为或互操作”。按下表决定归属，不必仅为文档分类向用户再次确认。

| 变更性质 | 应更新的位置 |
| --- | --- |
| 与语言无关的组件职责、数据模型、顺序、生命周期、所有权、背压、错误、取消、时间或兼容性语义 | 对应组件的 OGBS Specification |
| Java 公共类型、方法签名、注解、异常形式、默认配置、线程机制、Netty 接入、Maven 依赖或包结构 | 对应组件的 Java 开发规范 / 实现标准 |
| 通用行为变化，同时需要 Java 开发规范 或实现承接 | 同时更新 Specification 和 Java 实现标准，并保持两层一致 |
| 跨进程字段、字节序、长度、标识、编码或协议版本 | RPC Wire Profile；必要时同步相关组件规范和 Java 编解码说明 |
| 多组件共享 Metadata、错误码或共享约定 | OGBS Core 的对应章节；组件文档引用它，不重复定义 |
| 不改变公开契约的内部重构、算法替换或性能优化 | 修改实现及必要测试；只有产生值得长期维护的实现约定时才更新 Java 文档 |
| 模块组合、构建、使用入口或目录调整 | 同步架构总览、README、依赖图和相关链接 |

例如：

- “同 Route 必须串行”属于 Runtime Specification；“使用 ScopedValue 绑定上下文”属于 Java 实现标准。
- “改业务时间不自动重排任务”属于 Runtime Specification；`GameTime.setClock(Clock)` 的签名属于 Java 实现标准。
- “RPC Netty 代码放在 game-rpc 中发布”属于 Java 模块布局，不能据此要求其他语言实现依赖 Netty。
- “减少分配但行为不变”通常是实现优化；如果改变队列容量、拒绝行为或回调顺序，就必须重新审视契约及文档归属。

不要把某个 Java 实现技巧提升成跨语言要求。也不要把通用行为只写在 Java 文档里，导致其他实现缺少约束。

## 文档位置与单一来源

| 内容 | 文档 |
| --- | --- |
| Network 语义 | [OGBS Network Specification](docs/ogbs/OGBS-Network-1.0.zh-CN.md) |
| Network Java 实现 | [Network Java 开发规范](docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md) |
| RPC 语义 | [OGBS RPC Specification](docs/ogbs/OGBS-RPC-1.0.zh-CN.md) |
| RPC Java 实现 | [RPC Java 开发规范](docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md) |
| Runtime 语义 | [OGBS Runtime Specification](docs/ogbs/OGBS-Runtime-1.0.zh-CN.md) |
| Runtime Java 实现 | [Runtime Java 开发规范](docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md) |
| Data 语义 | [OGBS Data Specification](docs/ogbs/OGBS-Data-1.0.zh-CN.md) |
| Data Java 实现 | [Data Java 开发规范](docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md) |
| Metadata 与共享错误码标准 | [OGBS Core](docs/ogbs/OGBS-Core-1.0.zh-CN.md) |
| Core Java 开发 | [Core Java 开发规范](docs/ogbs/OGBS-Core-Java-25-Specification-1.0.zh-CN.md) |
| RPC 字节布局 | [RPC Wire Profile](docs/rpc-wire.zh-CN.md) |
| 组件组合与构建入口 | [架构总览](docs/architecture.zh-CN.md)、[项目 README](README.zh-CN.md) |

同一规则维护一个主要定义位置，其他文档引用它。Core 标准与 Java 开发规范已分离，修改时分别维护共享语义和 Java 绑定。不要创建平行版本的规范来回避修改现有文档。

## 处理后续需求的流程

1. 读取涉及组件的规范、语言实现标准和相关代码，核对用户当前要求与已确定的最新结论。已被后续设计结论取代的方案不得继续沿用。
2. 自主判断涉及的文档层次。已明确要求的修改直接落实；探索性意见先评估，不把未确定的提议写成已经生效的 MUST。
3. 用户明确改变既有设计时，同步修改受影响的规则、实现、示例和测试。发现实现违反现有契约时优先修正实现，不为掩盖缺陷而反向修改规范。
4. 真正影响业务行为、兼容性或范围且无法从上下文判断的歧义，简要说明后再澄清；文档归属、常规实现细节和可推断的选择自行处理。
5. 移除失效表述，修正交叉引用、源码链接、模块依赖及示例。说明默认值、失败路径、边界与兼容性影响，避免只记录理想成功流程。
6. 验证后简要报告实现结果、文档归属和验证结论。没有实际完成的工作不得写成已实现或已验证。

## 验证要求

- 行为变化使用能验证契约的测试，关注顺序、拒绝、异常、关闭及并发边界；不为机械改名编写只复述实现的测试。
- 涉及包迁移、模块合并、Maven 依赖或跨组件接入时，从仓库根目录运行 `mvn clean verify`，避免旧 class 文件掩盖问题。
- 局部实现修改可先运行相关模块及依赖测试，再按受影响范围执行必要的集成验证。
- 仅文档修改检查命名、内容一致性和本地链接；修改完整可运行示例时验证示例可编译、可运行，无须因此反复运行无关测试。

## 双语文档

- 仓库每份文档都必须有完整英文版和简体中文版。现有不带语言后缀的 .md 路径为默认英文入口；中文版在同目录使用 .zh-CN.md，包括 README 和 AGENTS。
- 每页顶部附近提供 English / 简体中文切换。同一语言内链接到其他文档的对应语言版本；源码和外部链接保持不变。
- 两种语言表达同一契约，不是独立设计版本。变更必须在同一任务同步两份正文，保留条款 ID、API 名称、默认值、约束、示例、失败路径及实现/验证状态。
- 英文优先展示不意味着可以改变语义或删减中文细节。翻译有差异时依据已确认设计和代码核对，再修正两份正文。
- 新增或重命名文档、标题时，检查语言配对、切换链接、本地文件链接与章节锚点；必要时保留已有入站链接的兼容锚点。
