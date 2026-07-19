# AGENT.md

面向 AI 编码代理与贡献者的工作约定。项目背景、模块职责、常用入口见 [README.md](README.md)。

## 文档规范（重要）

- **模块的开发文档必须放在该模块目录下的 `docs/` 目录**，例如：
  - `game-registry/docs/API_CONTRACT.md`
  - `game-config/docs/USAGE_GUIDE.md`
  - `game-jpa/docs/PRODUCTION_GUIDE.md`
- 开发文档指设计文档、API 契约、生产就绪/上线检查清单、内部使用指南等非对外材料。新增此类文档时直接创建在 `<模块>/docs/` 下，**不要放在模块根目录或其它位置**。
- **`docs/` 目录暂时不公开上传**：已在根 [.gitignore](.gitignore) 中忽略，不会提交到公共仓库。不要将其中内容移出该目录，也不要通过 `git add -f` 强制提交。
- 模块根目录的 `README.md` 是公开文档，面向外部使用者；公开内容写 README，内部细节写 `docs/`。
- **公开 README 中不得出现指向 `docs/` 的链接**（该目录不上传，链接在公共仓库会 404）。
- **所有 README 必须中英双语两份**：`README.md` 为中文，`README.en.md` 为英文，两者内容保持同步，顶部互加语言切换链接（`[English](README.en.md) | 中文` / `[中文](README.md) | English`）。修改任一 README 时必须同步更新另一语言版本。

## 环境与构建

- **JDK 25（硬性要求）**，Maven 3.9+；Rust sidecar 模块需要 `cargo`。
- PowerShell 下 Maven 属性要加引号：

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

- 只跑单个模块：`mvn "-Dmaven.repo.local=.m2" -f game-jpa\pom.xml test`
- Etcd / Nacos / Consul 集成测试需要本机真实服务，默认关闭（`game-registry` 的 `registry-it-*` profile）；MySQL / MongoDB 集成测试走 `game-jpa` 的 `integration-tests` profile（需要 Docker）。
- Rust 模块检查：`cargo fmt --check`、`cargo test --locked`、`cargo clippy -D warnings`。

## 关键开发约定

- Maven 坐标：groupId `cn.managame`，父 POM artifactId 为 `mana`。
- Java 包名统一 `cn.managame` 前缀，各模块按职责分包。
- 测试统一 JUnit 5/Jupiter。
- 模块边界：core 层不依赖具体实现（Netty/MySQL/Mongo/各注册中心 provider 放独立实现模块）；`game-network` / `game-rpc` / `game-runtime` 互不依赖，桥接代码放宿主进程。
- 其余约定（运行时 routerKey 串行模型、依赖许可证要求等）见 [README.md](README.md) 的「开发约定」与「依赖说明」。
