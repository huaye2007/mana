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

Peer recreation within one local Node no longer resets ID allocation; see R-CALL-04 in the RPC Specification. This continuity changes no field, byte order or version. Wire v1 has no Node-incarnation field; full-wrap late replies and saved replies across local Node replacement require the documented deployment/application boundary.

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

<a id="router-profile-v1"></a>
<a id="router-profile-v2"></a>

## Router Profile v2 (opt-in RPC payload)

[Router semantics](ogbs/OGBS-Router-1.0.md) and [Java binding](ogbs/OGBS-Router-Java-25-Specification-1.0.md) define roles/ownership. This section is the sole Router byte-layout definition. RPC framing, message types and handshake version remain unchanged. Router traffic uses Request.command **0x80000288** (Java **-2147483000**), reserved within routing-enabled Nodes; ordinary business commands must not use it there. Outer Metadata/business identity are empty/zero. Data Requests copy the inner request affinity; control Requests use routeKey=1.

Every Router request body starts with `profileVersion:uint8=2`, then `operation:uint8`. Other versions, unsupported operations, truncated/trailing control data, bad owners or snapshot counts are invalid. All integers are big-endian. nodeId/Router ID are nonzero uint32; epochs nonzero uint64; serviceId is uint32 restricted to 1..2147483647; bindingKey is unrestricted uint64.

Node entry = `nodeId:uint32 + nodeEpoch:uint64 + serviceId:uint32` (16 bytes). Binding entry = `serviceId:uint32 + bindingKey:uint64 + nodeId:uint32 + nodeEpoch:uint64` (24 bytes).

| Op | Name | Fields after common two-byte header |
| --- | --- | --- |
| 1 | RouterHandshake | routerEpoch:uint64 |
| 2 | SnapshotBegin | routerEpoch:uint64, nodeCount:uint32, bindingCount:uint32, revision:uint64 |
| 3 | SnapshotNodes | routerEpoch:uint64, count:uint16, count × Node entry |
| 4 | SnapshotBindings | routerEpoch:uint64, count:uint16, count × Binding entry |
| 5 | SnapshotEnd | routerEpoch:uint64 |
| 6 | NodeAdd | routerEpoch:uint64, revision:uint64, Node entry |
| 7 | NodeRemove | routerEpoch:uint64, revision:uint64, nodeId:uint32, nodeEpoch:uint64 |
| 8 | RouteBind | routerEpoch:uint64, revision:uint64, Binding entry |
| 9 | RouteUnbind | routerEpoch:uint64, revision:uint64, Binding entry |
| 10 | NodeRegister | Node entry |
| 11 | NodeBind | nodeEpoch:uint64, serviceId:uint32, bindingKey:uint64 |
| 12 | NodeUnbind | nodeEpoch:uint64, serviceId:uint32, bindingKey:uint64 |
| 13 | NodeDetach | nodeEpoch:uint64 |
| 14 | RoutedData | Data envelope below |
| 15 | RoutedError | routerEpoch:uint64, targetNodeId:uint32, targetNodeEpoch:uint64, requestId:uint32, errorCode:uint32 |
| 16 | Verify | expectedRouterEpoch:uint64, senderEpoch:uint64, senderRevision:uint64 |

Router identity is the direct RPC Peer ID; handshake epoch plus the current connection relationship scopes its bucket. Ops 2..9/15 require that identity/epoch. Ops 10..13 are direct service-Node controls; NodeRegister.nodeId must equal the direct Peer. Snapshot Nodes precede bindings, which must reference a registered matching service/epoch. Counts at end must exactly match distinct staged entries. Chunk count is 1..256; Java count/capacity limits are in the Java specification.

HELLO may restart routing synchronization on the same RPC connection. It discards incomplete incoming staging and requires a fresh BEGIN/chunks/END, preserving the last committed bucket until END. A fully synchronized recipient also starts its local snapshot; a recipient already exchanging snapshots does not repeatedly echo HELLO. This resets routing protocol state, never the RPC handshake, Peer or unrelated calls. This exchange uses the existing HELLO/snapshot operations and introduces no RPC message type.

Control ops use ordinary nonzero-ID RPC Calls. Successful NodeRegister and Verify native RPC Responses carry exactly routerEpoch:uint64 (eight body bytes, no Router header); other successful controls have an empty body. Positive errorCode means rejection. NodeRegister returns NOT_REGISTERED for a discovery-removed incarnation. Verify checks receiver epoch and sender state: a Router sender requires a committed bidirectional relationship with the same senderEpoch and senderRevision; a service sender uses nodeEpoch, senderRevision=0 and requires matching registration. A mismatch/missing state returns NOT_REGISTERED; Verify never registers, changes authority or proves service presence. One control call per Router Peer is in flight, next control follows its response. Both initialization streams exchange their own snapshots. No broadcast or business request is correlated by these controls. NodeBind's response still confirms only its local Router, not the other Routers' control responses.

SnapshotBegin captures the source revision. Ops 6..9 advance it once per accepted local mutation (including idempotent controls), modulo 2^64; their revision must be the committed source revision plus one. End commits the captured revision together with the staged bucket. A gap rejects the stream and requires a fresh snapshot; no replay log is defined. Verify compares revisions to detect omitted deltas even when a short reconnect was not observed.

Profile v2 is incompatible with the earlier v1 draft because snapshot/delta fields and NodeRegister ACK changed. Reject v1; upgrade routing peers together. RPC Wire v1 framing/handshake and ordinary RPC messages are unchanged. The old Router-profile anchor is retained only for links.

### RoutedData envelope

| Field | Width / meaning |
| --- | --- |
| mode | uint8: 1 exact Node request, 2 dynamic request, 3 service broadcast, 4 response |
| sourceNodeId | uint32, original sending service Node |
| sourceNodeEpoch | uint64, original source attachment |
| targetNodeId | uint32, exact destination; zero before dynamic resolution or for broadcast |
| targetNodeEpoch | uint64, exact resolved/return attachment; zero before resolution |
| serviceId | uint32, required for modes 2/3; unused zero in response |
| bindingKey | uint64, used by mode 2 |
| hops | uint8: 0 initial service-to-Router envelope, 1 resolved/forwarded envelope |
| innerFrame | Complete RPC Request or Response frame, **including its own uint32 length and type** |

Including the common two bytes, Router data overhead is 40 bytes before the inner frame. Its inner declared length must exactly cover the remaining bytes. Mode 4 requires an inner Response; modes 1..3 require a Request. Inner Metadata, command, identity, body and routeKey follow the ordinary RPC format; no business codec is added. Broadcast inner requestId must be zero. Dynamic mode is accepted only at the source Router, resolved once and becomes exact mode 1. Remote Routers never relay to another remote Router.

Initial service requests have inner requestId=0; if the outer request is a Call, the source Router copies that outer assigned ID into the inner request. Forwarded/delivered envelopes are outer Notifies (ID=0) with the unchanged inner business ID. Initial source identity must match the direct service attachment. A synchronized trusted Router vouches for a forwarded source; a destination service checks its own exact target ID/epoch. Hops=1 marks forwarded/delivered state, not the number of physical network connections.

Service reply uses mode 4 and exact original source ID/epoch as target. Its source fields identify the responding service. Intermediate traffic remains Notify. At the source Router, the final native RPC Response uses the original requestId, **outer errorCode=0 and empty Metadata**, with a full Router version/op=14/mode=4 envelope in its body. The origin unwraps the inner response's actual business/framework error and Metadata after RPC claims pending completion. Router-generated failures instead use native positive outer errorCode with empty body; op 15 carries such an error between Routers when needed. Error requestId must be nonzero and errorCode positive. Neither form changes base RPC error encoding.

Handshake payload golden vector (epoch=0x0102030405060708): `01 01 0102030405060708`. [RouterWireTest](../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java) checks this vector, envelope length, field preservation and borrowed ownership. Mixed old/non-Router endpoints do not implement this opt-in command; no Router interoperability is implied by ordinary RPC v1 support. Cross-language Router interoperability remains unverified.
