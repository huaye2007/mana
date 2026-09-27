<a id="本仓库-rpc-wire-profile-v1--2026-09-rpc-binding"></a>

# Repository RPC Wire Profile v1 — 2026-09 RPC binding

**[English](rpc-wire.md)** | [简体中文](rpc-wire.zh-CN.md)

This document is the sole byte-layout source. This implemented binding replaces the old unimplemented draft: nodeId changes from uint64 to uint32, Slot from uint16 to uint8, and errorCode loses its high-bit marker. The old draft never shipped an implementation; layouts cannot be mixed. Breaking changes to future deployed versions must increment the protocol version.

Semantics: [RPC Specification](ogbs/OGBS-RPC-1.0.md). Java integration: [RPC Java specification](ogbs/OGBS-RPC-Java-25-Specification-1.0.md). Metadata/errors: [Core](ogbs/OGBS-Core-1.0.md).

All multibyte integers are big-endian. Java int/long hold raw bits; negative nodeId, command, and requestId ints are valid uint32 values. errorCode permits only 0..2147483647.

## Frame

| Field | Width | Meaning |
| --- | --- | --- |
| frameLength | uint32 | Includes type and payload, excludes itself |
| messageType | uint8 | 1 Handshake, 2 Heartbeat, 3 Request, 4 Response |
| payload | bytes | Below |

Minimum frameLength=1. Java maxFrameSize **includes the four-byte length prefix**; defaults/ranges are in the Java specification. Validate declared length first; support fragmented/coalesced frames. Zero length, oversize, and unknown type are connection-level errors.

<a id="handshaketype1"></a>

## Handshake (type=1)

| Field | Width | Value |
| --- | --- | --- |
| magic | uint32 | 0x474e5352, retaining repository magic |
| version | uint16 | 1 |
| nodeId | uint32 | Nonzero and different from local ID |
| slotId | uint8 | 0..slotCount-1 |
| slotCount | uint8 | 1..255 |

frameLength=13; full frame=17 bytes; no trailing data. Active side sends first. Passive side creates/reuses Peer and returns local identity with identical Slot information. Active side checks expected identity. Duplicate handshake, Slot conflict, count mismatch, or pre-handshake business/heartbeat frames close the candidate.

Handshake is not authentication. V1 RpcNode uses internal TCP; deployment network boundaries establish trust.

<a id="heartbeattype2"></a>

## Heartbeat (type=2)

No payload; frameLength=1, full frame=5 bytes. Triggered by Write Idle after READY; no reply required.

<a id="requesttype3"></a>

## Request (type=3)

| Field | Width |
| --- | --- |
| command | uint32, nonzero |
| requestId | uint32 |
| routeKey | uint64 |
| businessIdType | uint8 |
| businessId | uint64 |
| metadataLength | uint16 |
| metadata | metadataLength bytes |
| body | All remaining bytes |

Fixed overhead including type, excluding prefix: 28 bytes. requestId=0 means Notify, otherwise Call. routeKey=0 means no affinity. businessIdType=0 means unspecified identity. RPC does not interpret businessId or business meaning of nonzero command.

<a id="responsetype4"></a>

## Response (type=4)

| Field | Width |
| --- | --- |
| requestId | uint32, nonzero |
| errorCode | uint32, highest bit must be 0 |
| metadataLength | uint16 |
| metadata | metadataLength bytes |
| body | All remaining bytes |

Fixed overhead including type, excluding prefix: 11 bytes. 0 means success; 1..9999 are reserved for framework errors; 10000..2147483647 are business errors. Core defines all numbers. Every valid response reaches unified RpcHandler; RPC does not translate remote errorCode into local onFail.

Read nonzero requestId first and claim PendingCall completion. Drop the rest of unmatched late/duplicate frames without parsing errorCode/Metadata. For matched responses, validate the remainder; corruption reports PROTOCOL_ERROR and closes the connection without losing completion notification.

## Metadata

metadataLength is at most 65535. Repeat:

```text
key:uint16 (1..65535)
valueLength:uint16
value:bytes[valueLength]
```

No count/type. Duplicate/zero keys and truncated headers/values are invalid; unknown keys are valid. Core defines value encoding. Structural validation does not call business MetadataCodec. Empty body has no separate null marker.

<a id="黄金向量"></a>

## Golden vectors

Handshake: nodeId=0x01020304, slotId=2, slotCount=3:

```text
0000000d 01 474e5352 0001 01020304 02 03
```

Heartbeat:

```text
00000001 02
```

Request: command=0x01020304, requestId=9, routeKey=10, businessIdType=2, businessId=11, empty Metadata, body=0x0c:

```text
0000001d 03 01020304 00000009 000000000000000a 02 000000000000000b 0000 0c
```

Response: requestId=9, errorCode=2006, empty Metadata/body:

```text
0000000b 04 00000009 000007d6 0000
```
