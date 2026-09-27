# OGBS Core Specification 1.0

文档类型：**标准规范（语言无关）**。状态：仓库规范草案。范围：跨组件共享 Metadata 和框架错误码。配套：[Core Java 开发规范](OGBS-Core-Java-25-Specification-1.0.md)。

## 1. 职责

Core 只承载跨组件共享的 Metadata 和 FrameworkErrorCode，不承担连接、业务身份认证、协议注册、路由执行或通用工具库职责。

本文的 Metadata 与错误码是 [RPC](OGBS-RPC-1.0.md) 和 [Runtime](OGBS-Runtime-1.0.md) 的规范性依赖。

## 2. Metadata 语义

**C-META-01** Metadata 必须是构建完成后按不可变方式使用的编码字节序列。不同 Context、RPC 对象可以共享同一份数据，不得修改其底层编码存储。

**C-META-02** 每个条目为 `key:uint16 + valueLength:uint16 + value:bytes`，多字节整数使用 big-endian。Key 范围为 1..65535；0 无效。容器总长度最大 65535 字节，不包含 RPC header 中的 metadataLength 字段。

**C-META-03** 不保存 type 或 entry count。同一个 Key 的编码语义必须由应用/组件双方稳定约定，不得在同一协议范围中将相同 Key 分别解释为不兼容类型。

**C-META-04** 一个容器内 Key 必须唯一；未知 Key 和零长度 value 合法。Key 为 0、重复 Key、截断 header、value 越界、总长度超限必须拒绝。空容器合法，不等价于含 null 的条目。

**C-META-05** 接收端应先校验结构，仅在访问某个 Key 时调用该 Key 的 codec；校验整个容器不应强制解码所有值。不要求存储 Map 或建立完整索引。

调用身份由 `businessIdType + businessId` 表达，不由框架自动塞入 Metadata。Metadata 中任何业务字段的可信性都取决于接入方的校验，容器本身不提供认证能力。

### 2.1 内置值编码

**C-META-06** 双方为某个 Key 选择以下内置编码时，必须遵守对应的值格式；容器仍不保存 type，不根据内容自动推断类型。

| 值类型 | 编码约定 |
| --- | --- |
| boolean | 恰好 1 字节：0x00 为 false，0x01 为 true；其他字节或长度非法 |
| int32 | 恰好 4 字节，big-endian，二进制补码 |
| int64 | 恰好 8 字节，big-endian，二进制补码 |
| string | UTF-8 |
| bytes | 原始字节序列 |

值格式在通过对应 Key 读取时校验。结构合法但含非法 boolean 值的容器可以被包装和转发，读取该 boolean 时必须失败；不得将任意非零值宽松解释为 true。

Key 缺失与 boolean false 是不同状态。沿用旧 Key 时不得更改其既有编码类型；新增工厂不分配预置业务 Key，也不改变 Metadata 或 RPC 的外层字节布局。

## 3. 语言实现绑定

具体语言的公共类型、构造方式、异常和内部算法由对应开发规范定义。Java 实现见 [Core Java 开发规范](OGBS-Core-Java-25-Specification-1.0.md)。

其他语言必须遵守本标准的 Metadata 编码、所有权、惰性解码和共享错误码语义，不要求复制 Java 泛型、byte[]、Builder 类或线性扫描算法。
## 4. FrameworkErrorCode

**C-ERR-01** 框架错误码在本地 API 中必须为正整数，0 表示成功。统一按区间分配：

| 范围 | 归属 |
| --- | --- |
| 1..999 | 共享基础能力预留 |
| 1000..1999 | game-network 预留 |
| 2000..2999 | game-rpc |
| 3000..3999 | game-runtime |
| 4000..4999 | game-data |
| 5000..9999 | 后续框架组件预留 |
| 10000..2147483647 | 业务错误 |

**C-ERR-02** RPC Response 的 errorCode 直接使用非负整数：0 成功；1..9999 框架预留；10000..2147483647 业务错误；最高位为 1 的值非法。取消旧草案的高位封装，框架与业务不能占用相同编号。远端任何合法 errorCode 都交给 RPC 统一响应处理器解释，不自动转为本地失败。

**C-ERR-03** Runtime 本地失败和 RPC 本地失败使用同一正整数框架编号。Network 的写入状态、建连失败和连接异常遵循 Network 标准，不强制分配共享数字错误码，也不把每次关闭自动翻译为 RPC Response。

### RPC 常量

| 值 | 名称 |
| --- | --- |
| 2001 | RPC_PEER_NOT_FOUND |
| 2002 | RPC_UNAVAILABLE |
| 2003 | RPC_TIMEOUT |
| 2004 | RPC_PEER_REMOVED |
| 2005 | RPC_NODE_CLOSED |
| 2006 | RPC_HANDLER_ERROR |
| 2007 | RPC_PROTOCOL_ERROR |

此分配按最新 RPC 设计替代尚未实现的旧 RPC 草案。旧常量 RPC_NOT_WRITABLE、RPC_HANDSHAKE_FAILED、RPC_INTERNAL_ERROR 及高位标记不再使用；不能与旧错误编号/布局混用。Runtime/Data 已有编号保持不变。后续已部署编号不得再重新分配。

### Runtime 常量

| 值 | 名称 |
| --- | --- |
| 3001 | RUNTIME_CLOSED |
| 3002 | HANDLER_NOT_FOUND |
| 3003 | HANDLER_CONTEXT_MISMATCH |
| 3004 | ROUTE_DOMAIN_MISMATCH |
| 3005 | INVALID_ROUTE_KEY |
| 3006 | ROUTE_EXECUTOR_OVERLOADED |
| 3007 | ROUTE_EXECUTOR_CLOSED |
| 3008 | ROUTE_CALL_EXECUTION_ERROR |
| 3009 | RUNTIME_EXECUTION_ERROR |
| 3010 | ROUTE_CALLBACK_DISPATCH_FAILED |

### Data 常量

| 值 | 名称 |
| --- | --- |
| 4001 | DATA_SAVE_FAILED |
| 4002 | DATA_LOG_SAVE_FAILED |

Data 错误码用于后台持久化失败上下文；同步加载/操作/关闭异常形式见 [Data Java 开发规范](OGBS-Data-Java-25-Specification-1.0.md)。

具体哪个 API 返回或报告错误，由对应组件规范说明。常量存在不表示任何场景都会产生该错误；例如 RPC 握手失败主要关闭连接并走 Throwable 诊断通道。

## 5. 实现与验证

源码：[Metadata](../../game-core/src/main/java/cn/managame/core/Metadata.java)、[Metadatas](../../game-core/src/main/java/cn/managame/core/Metadatas.java)、[FrameworkErrorCodes](../../game-core/src/main/java/cn/managame/core/FrameworkErrorCodes.java)。

测试：[MetadataTest](../../game-core/src/test/java/cn/managame/core/MetadataTest.java) 覆盖共享数组、惰性 codec、结构损坏、重复 Key、边界、大小端向量、Builder 快照、boolean 黄金字节/非法值/缺失默认值，以及 int32 有符号边界。

