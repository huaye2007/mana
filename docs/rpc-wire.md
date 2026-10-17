<a id="本仓库-rpc-wire-profile-v1--2026-09-rpc-binding"></a>

# Repository RPC Wire Profile v2 — 2026-10 RPC binding

**[English](rpc-wire.md)** | [简体中文](rpc-wire.zh-CN.md)

This document is the sole byte-layout source. This implemented binding replaces the old unimplemented draft: nodeId changes from uint64 to uint32, Slot from uint16 to uint8, and errorCode loses its high-bit marker. The old draft never shipped an implementation; layouts cannot be mixed. Breaking changes to future deployed versions must increment the protocol version.

Semantics: [RPC Specification](ogbs/OGBS-RPC-1.0.md). Java integration: [RPC Java specification](ogbs/OGBS-RPC-Java-25-Specification-1.0.md). Metadata/errors: [Core](ogbs/OGBS-Core-1.0.md).

All multibyte integers are big-endian. Java int/long hold raw bits; negative nodeId and command ints are valid uint32 values, while requestId is a positive 1..2147483647 value (0 for Notify) and a negative one is a corrupt frame. errorCode permits only 0..2147483647.

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
| version | uint16 | 2 |
| nodeId | uint32 | Nonzero and different from local ID |
| slotId | uint8 | 0..slotCount-1 |
| slotCount | uint8 | 1..255 |
| timestamp | int64 | Sender Unix milliseconds; 0 without a secret |
| nonce | 16 bytes | Sender random value; all zero without a secret |
| peerNonce | 16 bytes | All zero from the initiator; the reply echoes the initiator's nonce |
| mac | 32 bytes | With a secret, HMAC-SHA256(secret, every byte from magic through peerNonce); otherwise all zero |

frameLength=85; full frame=89 bytes; no trailing data. Only the side that called addPeer (the initiator) sends the first handshake. The passive side creates/reuses a passive Peer and returns local identity with identical Slot information. The initiator checks expected identity and requires the reply's peerNonce to equal the nonce it sent. Duplicate handshake, Slot conflict, count mismatch, pre-handshake business/heartbeat frames or a MAC mismatch close the candidate. An inbound handshake is also rejected when the receiver itself called addPeer for that nodeId: only one side of a pair may call addPeer.

**Authentication.** When a cluster's nodes share one secret, the passive side verifies the MAC, requires |local time − timestamp| within the allowed clock skew (Java default 60 seconds), and rejects a nonce seen again within that window; the initiator uses the echoed peerNonce to confirm the reply is fresh and answers this connection. A node without a secret accepts only all-zero MACs, so mismatched secret configuration fails to connect instead of silently skipping authentication. The MAC authenticates connection establishment only; it does not protect confidentiality or integrity of later frames. Use transport TLS when required.

The v2 handshake is incompatible with v1 (length and version differ); upgrade a cluster's nodes together. Request/Response/Heartbeat layouts are unchanged.

## Heartbeat (type=2)

No payload; frameLength=1, full frame=5 bytes. Triggered by Write Idle after READY; no reply required.

<a id="requesttype3"></a>

## Request (type=3)

| Field | Width |
| --- | --- |
| command | uint32, nonzero |
| requestId | int32, 0 for Notify, otherwise 1..2147483647 |
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
| requestId | int32, 1..2147483647 |
| errorCode | uint32, highest bit must be 0 |
| metadataLength | uint16 |
| metadata | metadataLength bytes |
| body | All remaining bytes |

Fixed overhead including type, excluding prefix: 11 bytes. 0 means success; 1..9999 are reserved for framework errors; 10000..2147483647 are business errors. Core defines all numbers. Every valid response reaches unified RpcHandler; RPC does not translate remote errorCode into local onFail.

Peer recreation within one local Node no longer resets ID allocation; see R-CALL-04 in the RPC Specification. This continuity changes no field, byte order or version. Wire has no Node-incarnation field; full-wrap late replies and saved replies across local Node replacement require the documented deployment/application boundary.

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
00000055 01 474e5352 0002 01020304 02 03 (timestamp, nonce, peerNonce, mac: 72 zero bytes)
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
<a id="router-profile-v3"></a>
<a id="router-profile-v4"></a>
<a id="router-profile-v5"></a>

## Router Profile v5 (opt-in RPC payload)

[Router semantics](ogbs/OGBS-Router-1.0.md) and [Java binding](ogbs/OGBS-Router-Java-25-Specification-1.0.md) define roles and ownership. This section is the sole Router byte-layout definition. RPC framing, message types and handshake stay unchanged. Router traffic uses Request.command **0x80000288** (Java **-2147483000**), reserved within routing-enabled Nodes; ordinary business commands must not use it there. Outer Metadata and business identity are empty/zero. Data Requests carry the inner request's routeKey as the outer routeKey; every control operation (Attach, Detach, Locations, Unlocate) uses routeKey 1, so all control traffic on one connection shares one Slot and arrives in the order sent.

Every Router request body starts with `profileVersion:uint8=5`, then `operation:uint8`. Other versions, unsupported operations and truncated or trailing data are invalid. All integers are big-endian. nodeId is a nonzero uint32, nodeEpoch a nonzero uint64, serviceId a uint32 restricted to 1..2147483647. Router Profile traffic flows between a service and the one Router it is attached to, and between two Routers of the mesh. The direction column is normative: a receiver MUST reject Attach or Detach arriving from a declared Router, and Locations or Unlocate arriving from anything other than a declared Router.

| Op | Name | Direction | Fields after the common two-byte header |
| --- | --- | --- | --- |
| 1 | Attach | service → Router, Notify | nodeEpoch:uint64, serviceId:uint32 |
| 2 | Detach | service → Router, Notify | nodeEpoch:uint64 |
| 3 | Data | service ↔ Router, Router ↔ Router | envelope below, then the inner frame |
| 4 | Locations | Router → Router, Notify | replace:uint8, entryCount:uint16, then entryCount × (nodeId:uint32, nodeEpoch:uint64, serviceId:uint32) |
| 5 | Unlocate | Router → Router, Notify | entryCount:uint16, then entryCount × (nodeId:uint32, nodeEpoch:uint64) |

Attach announces the Node on this connection; its Node ID is the direct RPC Peer. A Router keeps the latest announcement per Node while that Node is connected and replaces it when another nodeEpoch or serviceId arrives. Detach removes the announcement only when nodeEpoch matches. Neither is acknowledged: a service repeats Attach on every new connection and periodically, which re-announces it to a restarted Router, and it answers Data from a Router it is not using with another Detach. Attach or Detach sent as a Call is rejected.

### Locations and Unlocate

Locations reports the service Nodes attached to the **sending** Router; the sending Router is the direct RPC Peer, so it is not a field. `replace=1` makes the entry list the sender's complete set, replacing everything it previously reported, which is how a Node that vanished without an Unlocate is forgotten; `replace=0` upserts the listed entries only. Each entry locates one Node: a receiver stores `(nodeId → nodeEpoch, serviceId, reporting Router)` and replaces an existing entry for that nodeId reported by the same Router. Two Routers reporting the same nodeId is a misconfiguration; a receiver MAY keep either, and its own live attachment always wins over any report.

Unlocate removes reported entries, and only where both nodeId and nodeEpoch match the stored entry, so a removal that lost a race with a newer report does not delete the newer location.

A Data frame arriving from a Router also locates its own `sourceNodeId`/`sourceNodeEpoch`/`sourceServiceId` on that Router, as if it carried a one-entry `replace=0` Locations. Control and Data use different routeKeys and therefore different Slots, so a response must not depend on a report overtaking the request that needs it.

`entryCount` is 0..65535 and the payload must end exactly after the declared entries. `entryCount=0` is valid only with `replace=1`, meaning "I have no attachments"; a zero-entry Unlocate or `replace=0` Locations is invalid. A complete set must fit one RPC frame: with `maxFrameSize` 4 MiB the limit is the uint16 entry count, not the frame. Locations and Unlocate sent as a Call are rejected, and neither is acknowledged: the next periodic complete set repairs any loss.

Payload sizes: Locations is 5 + 16 × entryCount bytes, Unlocate 4 + 12 × entryCount, each including the common two bytes.

### Data envelope

| Field | Width / meaning |
| --- | --- |
| mode | uint8: 1 Node request, 2 service broadcast, 3 response |
| sourceNodeId | uint32, the originating service Node; equals the direct Peer when a service sent the frame, and stays the originator when a Router passes it on |
| sourceNodeEpoch | uint64, sending incarnation |
| sourceServiceId | uint32, service of the sending Node |
| targetNodeId | uint32: the request target, or the caller in a response; 0 in a broadcast sent by a service |
| targetNodeEpoch | uint64: 0 (current incarnation) in a service's request, the caller's incarnation in a response; always exact once a Router delivers the frame |
| targetServiceId | uint32: the broadcast service; 0 otherwise |
| innerFrame | complete RPC Request or Response frame, **including its own uint32 length and type** |

Including the common two bytes the envelope is 35 bytes, so with the outer Request header (length prefix and 28 fixed bytes) a Data request is 67 bytes longer than its inner frame and must fit the RPC frame limit. The inner declared length must exactly cover the remaining bytes. Mode 3 requires an inner Response; modes 1 and 2 require a Request whose inner requestId is 0. Inner Metadata, command, identity, body and routeKey follow the ordinary RPC format.

A service sends a Node request (mode 1) to its Router as a Notify, or as a Call when it expects a response. The Router also treats the frame as an Attach of its source, then resolves targetNodeId. When the target is attached to this Router with targetNodeEpoch (any epoch when 0), it is delivered as an outer Notify whose targetNodeEpoch is the attached epoch and, for a Call, whose inner requestId is the outer call ID. When another Router reported the target instead, the frame is passed once to that Router as a Notify with targetNodeEpoch set to the reported epoch, and that Router delivers it. Apart from those fields a Router passes the inner frame on byte for byte. A frame that arrived **from a Router** is only ever delivered to a locally attached target, never passed on again. When no usable location exists the Router answers a Call at once with a native Response whose errorCode is RPC PEER_NOT_FOUND, or UNAVAILABLE when the send to the target or to the next Router is refused; a Notify is dropped.

A broadcast (mode 2) is a Notify. The receiving Router delivers one copy to every Node of targetServiceId attached to itself, the sender included, each addressed to that Node's exact ID and epoch, and passes the frame once to every other connected Router of the mesh with targetNodeId and targetNodeEpoch left 0. A broadcast received from a Router is delivered locally only.

A response (mode 3) is a Notify from the responding service to the Router that delivered the request, targeting the caller's ID and epoch. That Router routes it by targetNodeId exactly like mode 1: when the caller is attached there it answers the caller's pending Call with a native RPC Response carrying the inner requestId, **outer errorCode 0 and empty Metadata**, whose body is the complete Router version/op=3/mode=3 payload, and the caller unwraps the inner response's actual error and Metadata; when another Router reported the caller, the frame is passed once to that Router, which completes the call. Because a service Node is attached to one Router, that resolution reaches the Router holding the pending call. If the caller is no longer attached with that epoch, or has no usable location, the response is dropped. A service accepts Data only from the Router it is using and only when targetNodeId and targetNodeEpoch are its own.

Golden vectors (from [RouterWireTest](../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)):

| Frame | Bytes |
| --- | --- |
| Attach(epoch 5, service 6) | `05 01 0000000000000005 00000006` |
| Detach(epoch 5) | `05 02 0000000000000005` |
| Locations(replace, one entry: node 7, epoch 8, service 9) | `05 04 01 0001 00000007 0000000000000008 00000009` |
| Unlocate(one entry: node 7, epoch 8) | `05 05 0001 00000007 0000000000000008` |
| Data(Node request 1/2/3 → 4/5, command 44, routeKey 9, no body) | `05 03 01 00000001 0000000000000002 00000003 00000004 0000000000000005 00000000 0000001c 03 0000002c 00000000 0000000000000009 00 0000000000000000 0000` |

Profile v5 is incompatible with v1–v4. Against v4 the Data envelope is byte-identical and the three original operations are unchanged, but Data now also flows between Routers, Locations and Unlocate are added, and a service attaches to one Router instead of all of them, so a v4 participant would neither report nor accept locations and would still fan its own traffic out across every Router. Against v1–v3, Router synchronization of business-key bindings, service registration and binding controls and RoutedError stay gone: v5 replicates Node locations only. Reject other versions and upgrade routing participants together. RPC Wire framing and ordinary RPC messages are unchanged; the older anchors are kept only for links. Cross-language Router interoperability remains unverified.
