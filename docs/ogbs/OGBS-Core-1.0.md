# OGBS Core Specification 1.0

**[English](OGBS-Core-1.0.md)** | [简体中文](OGBS-Core-1.0.zh-CN.md)

Document type: **Specification (language-independent)**. Status: repository specification draft. Scope: cross-component Metadata and framework error codes. Companion: [Core Java Development Specification](OGBS-Core-Java-25-Specification-1.0.md).

<a id="1-职责"></a>

## 1. Responsibilities

Core contains only shared Metadata and FrameworkErrorCode contracts. It does not own connections, business authentication, protocol registration, Route execution, or a general-purpose utility library.

Metadata and error codes here are normative dependencies of [RPC](OGBS-RPC-1.0.md) and [Runtime](OGBS-Runtime-1.0.md).

<a id="2-metadata-语义"></a>

## 2. Metadata semantics

**C-META-01** Metadata MUST be an encoded byte sequence used immutably after construction. Context and RPC objects may share it; its underlying encoded storage MUST NOT be modified.

**C-META-02** Each entry is `key:uint16 + valueLength:uint16 + value:bytes`; multi-byte integers are big-endian. Keys range from 1..65535; 0 is invalid. Total container length is at most 65535 bytes, excluding the RPC header's metadataLength field.

**C-META-03** No type or entry count is stored. Both applications/components MUST maintain stable encoding semantics for each Key and MUST NOT interpret one Key as incompatible types within the same protocol scope.

**C-META-04** Keys MUST be unique within a container. Unknown Keys and zero-length values are valid. Zero Keys, duplicate Keys, truncated headers, out-of-bounds values, and excessive total length MUST be rejected. An empty container is valid and is not an entry containing null.

**C-META-05** Receivers SHOULD validate structure first and invoke a Key's codec only when that Key is accessed. Whole-container validation should not force decoding of every value. A Map or complete index is not required.

Invocation identity is expressed by `businessIdType + businessId`; the framework does not automatically insert it into Metadata. Trust in business fields depends on integration-layer validation; the container itself provides no authentication.

<a id="21-内置值编码"></a>

### 2.1 Built-in value encodings

**C-META-06** When both sides select a built-in encoding below for a Key, they MUST follow its value format. The container still stores no type and does not infer one from content.

| Value type | Encoding |
| --- | --- |
| boolean | Exactly 1 byte: 0x00=false, 0x01=true; other bytes or lengths are invalid |
| int32 | Exactly 4 bytes, big-endian, two's complement |
| int64 | Exactly 8 bytes, big-endian, two's complement |
| string | UTF-8 |
| bytes | Raw byte sequence |

Value format is validated when reading through the corresponding Key. A structurally valid container with an invalid boolean may be wrapped and forwarded, but reading that boolean MUST fail; arbitrary nonzero values MUST NOT be loosely interpreted as true.

A missing Key differs from boolean false. Existing Keys MUST retain their encoding types. New factories allocate no predefined business Keys and do not change the outer Metadata or RPC byte layout.

<a id="3-语言实现绑定"></a>

## 3. Language bindings

Language-specific public types, construction, exceptions, and internal algorithms belong in the corresponding development specification. See the [Core Java Development Specification](OGBS-Core-Java-25-Specification-1.0.md).

Other languages MUST obey Metadata encoding, ownership, lazy decoding, and shared error semantics. They need not replicate Java generics, byte[], Builder classes, or linear scanning.

## 4. FrameworkErrorCode

**C-ERR-01** Framework errors in local APIs MUST use positive integers; 0 means success. Allocate ranges consistently:

| Range | Owner |
| --- | --- |
| 1..999 | Reserved for shared foundational capabilities |
| 1000..1999 | Reserved for game-network |
| 2000..2999 | game-rpc |
| 3000..3999 | game-runtime |
| 4000..4999 | game-data |
| 5000..9999 | Reserved for future framework components |
| 10000..2147483647 | Business errors |

**C-ERR-02** RPC Response.errorCode directly uses nonnegative integers: 0 success, 1..9999 framework-reserved, 10000..2147483647 business errors; values with the top bit set are invalid. The old draft's high-bit wrapping is removed. Framework and business codes cannot share the same number. All valid remote codes go to RPC's unified response handler for interpretation, without automatic conversion to local failure.

**C-ERR-03** Runtime and RPC local failures use the same positive framework-code allocation. Network write status, connection failure, and exceptions follow the Network standard; shared numeric codes are not mandatory, and closures are not automatically translated into RPC Responses.

<a id="rpc-常量"></a>

### RPC constants

| Value | Name |
| --- | --- |
| 2001 | RPC_PEER_NOT_FOUND |
| 2002 | RPC_UNAVAILABLE |
| 2003 | RPC_TIMEOUT |
| 2004 | RPC_PEER_REMOVED |
| 2005 | RPC_NODE_CLOSED |
| 2006 | RPC_HANDLER_ERROR |
| 2007 | RPC_PROTOCOL_ERROR |

This allocation follows the latest RPC design and replaces the unimplemented draft. RPC_NOT_WRITABLE, RPC_HANDSHAKE_FAILED, RPC_INTERNAL_ERROR, and high-bit markers are no longer used; do not mix the old allocation/layout with this one. Existing Runtime/Data allocations remain unchanged. Deployed allocations must not subsequently be reassigned.

<a id="runtime-常量"></a>

### Runtime constants

| Value | Name |
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

<a id="data-常量"></a>

### Data constants

| Value | Name |
| --- | --- |
| 4001 | DATA_SAVE_FAILED |
| 4002 | DATA_LOG_SAVE_FAILED |

Data codes describe background persistence failures. See the [Data Java Development Specification](OGBS-Data-Java-25-Specification-1.0.md) for synchronous load/operation/shutdown exceptions.

Each component specifies which APIs return or report errors. A defined constant does not imply that every related event emits it; for example, RPC handshake failure primarily closes the connection and uses Throwable diagnostics.

<a id="5-实现与验证"></a>

## 5. Implementation and validation

Sources: [Metadata](../../game-core/src/main/java/cn/managame/core/Metadata.java), [Metadatas](../../game-core/src/main/java/cn/managame/core/Metadatas.java), [FrameworkErrorCodes](../../game-core/src/main/java/cn/managame/core/FrameworkErrorCodes.java).

[MetadataTest](../../game-core/src/test/java/cn/managame/core/MetadataTest.java) covers shared arrays, lazy codecs, malformed structure, duplicate Keys, boundaries, endian vectors, Builder snapshots, boolean golden bytes/invalid values/missing defaults, and signed int32 boundaries.
