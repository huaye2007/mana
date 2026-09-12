# Mana

[English](README.md) | 简体中文

用于游戏服务器网络通信和 RPC 的 Java 组件，基于 JDK 25 和 Netty 4.2.15.Final 构建。

## 项目

| 项目 | 说明 | 文档 |
|---|---|---|
| game-network | TCP、WebSocket、加密 WebSocket 和 HTTP 服务端，以及 TCP 和 WebSocket 客户端。 | [English](game-network/README.md) · [简体中文](game-network/README.zh-CN.md) |
| game-rpc | 基于 game-network 的 RPC，支持节点连接、调用、通知、显式回复、超时和 Metadata。 | [English](game-rpc/README.md) · [简体中文](game-rpc/README.zh-CN.md) |

`game-network` 包含仅依赖 JDK 的 `game-network-api` 模块及 `game-network-netty` 实现。`game-rpc` 包含 `game-rpc-core` 和 `game-rpc-netty` 传输集成。

## 构建

使用 JDK 25 和 Maven 3.9，在仓库根目录运行：

~~~sh
mvn verify
~~~

使用示例、配置及其他验证命令见各项目的 README。

## 文档语言

各项目默认的 `README.md` 使用英文，对应的 `README.zh-CN.md` 提供简体中文版。更新项目文档时，请同步维护两个版本。
