<a id="本仓库-rpc-wire-profile-v1--2026-09-rpc-binding"></a>

# Repository RPC Wire Profile v2 — 2026-10 RPC binding

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

## Router Profile v3 (opt-in RPC payload)

[Router semantics](ogbs/OGBS-Router-1.0.md) and [Java binding](ogbs/OGBS-Router-Java-25-Specification-1.0.md) define roles/ownership. This section is the sole Router byte-layout definition. RPC framing, message types and handshake version remain unchanged. Router traffic uses Request.command **0x80000288** (Java **-2147483000**), reserved within routing-enabled Nodes; ordinary business commands must not use it there. Outer Metadata/business identity are empty/zero. Data Requests copy the inner request affinity; other Router Requests use routeKey=1.

Every Router request body starts with `profileVersion:uint8=3`, then `operation:uint8`. Other versions, unsupported operations, truncated/trailing data, bad owners or snapshot counts are invalid. All integers are big-endian. nodeId/Router ID are nonzero uint32; epochs nonzero uint64; serviceId is uint32 restricted to 1..2147483647; bindingKey is unrestricted uint64.

Node entry = `nodeId:uint32 + nodeEpoch:uint64 + serviceId:uint32` (16 bytes). Binding entry = `serviceId:uint32 + bindingKey:uint64 + nodeId:uint32 + nodeEpoch:uint64` (24 bytes). Stream header = `routerEpoch:uint64 + session:uint64 + seq:uint64` (24 bytes); Begin omits seq (16 bytes).

| Op | Name | Fields after the common two-byte header |
| --- | --- | --- |
| 1 | Sync | requesterEpoch:uint64, knownEpoch:uint64, brokenSession:uint64 |
| 2 | Begin | routerEpoch:uint64, session:uint64, nodeCount:uint32, bindingCount:uint32 |
| 3 | Nodes | stream header, count:uint16, count × Node entry |
| 4 | Bindings | stream header, count:uint16, count × Binding entry |
| 5 | End | stream header |
| 6 | Delta | stream header, count:uint16, count × delta operation |
| 7 | State | stream header (seq = last sequence sent in the session) |
| 10 | NodeRegister | Node entry |
| 11 | NodeBind | nodeEpoch:uint64, serviceId:uint32, bindingKey:uint64 |
| 12 | NodeUnbind | nodeEpoch:uint64, serviceId:uint32, bindingKey:uint64 |
| 13 | NodeDetach | nodeEpoch:uint64 |
| 14 | RoutedData | Data envelope below |
| 15 | RoutedError | targetNodeId:uint32, targetNodeEpoch:uint64, requestId:uint32, errorCode:uint32 |
| 16 | Verify | expectedRouterEpoch:uint64, nodeEpoch:uint64, revision:uint64=0 |

Delta operation = `kind:uint8` followed by: 1 NodeAdd → Node entry; 2 NodeRemove → nodeId:uint32 + nodeEpoch:uint64; 3 Bind → Binding entry; 4 Unbind → Binding entry. Chunk and Delta counts are 1..1024. Java capacity limits are in the Java specification.

### Router synchronization stream (ops 1–7)

Ops 1–7 travel only between Routers that declared each other, as RPC Notifies (outer requestId=0) on the single Router Slot. A receiver ignores them from an undeclared Peer and rejects them as a Call or over a Peer with another Slot count. Router identity is the direct RPC Peer ID; routerEpoch identifies its incarnation. Nothing in the stream is acknowledged: one Slot delivers frames in order, so loss shows up only as a sequence gap.

The authority (the Router that owns the bucket) sends one session per peer: Begin, Nodes chunks, Bindings chunks, End, then Deltas and, while idle, State. Session numbers are nonzero and increase within one routerEpoch. Begin has no sequence; every later frame of the session carries the next sequence number starting at 1, and State repeats the last number used. The snapshot is captured atomically against local changes and every later change follows it as a Delta. Nodes precede Bindings; each binding must reference a staged Node with the same epoch and service. At End the staged counts must equal the Begin counts exactly; only then does the staged bucket replace the committed one.

The receiver tracks the current (epoch, session, expected seq). Frames of an older session of the same epoch are ignored. Begin starts staging unless it names the session the receiver already gave up (same epoch, session not newer than the broken one). Any other mismatch, a sequence gap, a State whose seq is not the last applied one, a malformed frame, connection loss, a staging snapshot that stops arriving or a silent stream breaks the replica: the committed bucket stays visible, and the receiver sends Sync with requesterEpoch = its own Router epoch and knownEpoch/brokenSession = the session it gave up (zero when it never had one). The authority answers Sync by starting a new session; it MAY ignore a Sync naming an older session when its current session started moments ago. The authority also starts a session by itself whenever the Slot is usable and it has none, and a receiver that is not live repeats Sync periodically, so recovery needs neither message to arrive first. A pending-change bound overflow also restarts the session. There is no replay log.

### Service controls (ops 10–13, 16)

Service controls are ordinary nonzero-ID RPC Calls from a directly attached service to its home Router; their fields are unchanged from Profile v2. NodeRegister.nodeId must equal the direct Peer. Successful NodeRegister and Verify responses carry exactly routerEpoch:uint64 (eight body bytes, no Router header); other successful controls have an empty body. A positive errorCode is a rejection; NodeRegister returns NOT_REGISTERED for a discovery-removed incarnation. Verify succeeds only when expectedRouterEpoch is the receiver's epoch and the sender is registered with that nodeEpoch; revision must be zero. Verify never registers, changes authority or proves service presence. NodeBind's response confirms only the local Router. Routers do not send Verify to each other.

### RoutedError (op 15)

RoutedError is an RPC Notify (outer requestId=0) from the Router that detected a failure to the origin Router of the request, sent independently of the synchronization stream. requestId is the original nonzero business call ID and errorCode is positive. The receiver accepts it only from a declared Router and answers its local Node with a native positive-error Response only when targetNodeId/targetNodeEpoch is still registered there. It creates no intermediary PendingCall, requires no acknowledgement and is not retried; failed admission leaves the original call to its existing timeout.

### RoutedData envelope

| Field | Width / meaning |
| --- | --- |
| mode | uint8: 1 exact Node request, 2 dynamic request, 3 service broadcast, 4 response |
| originRouterId | uint32, Router the calling service is attached to; zero in a service's initial request |
| sourceNodeId | uint32, original sending service Node |
| sourceNodeEpoch | uint64, original source attachment |
| targetNodeId | uint32, exact destination; zero before dynamic resolution or for broadcast |
| targetNodeEpoch | uint64, exact resolved/return attachment; zero before resolution |
| serviceId | uint32, required for modes 2/3; unused zero in response |
| bindingKey | uint64, used by mode 2 |
| hops | uint8: 0 initial service-to-Router envelope, 1 resolved/forwarded envelope |
| innerFrame | Complete RPC Request or Response frame, **including its own uint32 length and type** |

Including the common two bytes, Router data overhead is 44 bytes before the inner frame. Its inner declared length must exactly cover the remaining bytes. Mode 4 requires an inner Response; modes 1..3 require a Request. Inner Metadata, command, identity, body and routeKey follow the ordinary RPC format; no business codec is added. Broadcast inner requestId must be zero. Dynamic mode is accepted only at the source Router, resolved once and becomes exact mode 1. Remote Routers never relay to another remote Router.

Initial service requests have inner requestId=0; if the outer request is a Call, the source Router copies that outer assigned ID into the inner request and sets originRouterId to itself. Forwarded/delivered envelopes are outer Notifies (ID=0) with the unchanged inner business ID and a nonzero originRouterId. Initial source identity must match the direct service attachment. A declared Router vouches for a forwarded source; a destination service checks its own exact target ID/epoch.

Service reply uses mode 4, the exact original source ID/epoch as target and the originRouterId of the request it answers. Its source fields identify the responding service. The responder's Router forwards the reply directly to that origin Router (or delivers it locally); an originRouterId of zero makes it locate the target Router in its own view. At the origin Router, the final native RPC Response uses the original requestId, **outer errorCode=0 and empty Metadata**, with a full Router version/op=14/mode=4 envelope in its body. The caller unwraps the inner response's actual business/framework error and Metadata after RPC claims pending completion. Router-generated failures instead use a native positive outer errorCode with an empty body; op 15 carries such an error between Routers. Neither form changes base RPC error encoding.

Golden vectors (from [RouterWireTest](../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)):

| Frame | Bytes |
| --- | --- |
| Sync(requester 1, known 2, broken 3) | `03 01 0000000000000001 0000000000000002 0000000000000003` |
| Begin(epoch 1, session 2, nodes 3, bindings 4) | `03 02 0000000000000001 0000000000000002 00000003 00000004` |
| Delta(epoch 1, session 2, seq 3, Bind (5,6)→(7,8)) | `03 06 0000000000000001 0000000000000002 0000000000000003 0001 03 00000005 0000000000000006 00000007 0000000000000008` |
| State(epoch 1, session 2, seq 9) | `03 07 0000000000000001 0000000000000002 0000000000000009` |

The test also checks the envelope origin, the inner length prefix at offset 44, field preservation and borrowed ownership. Profile v3 is incompatible with v1 and v2: the stream operations, RoutedError and the data envelope changed. Reject other versions and upgrade routing participants together. RPC Wire framing and ordinary RPC messages are unchanged; the older anchors are kept only for links. Mixed old/non-Router endpoints do not implement this opt-in command, and cross-language Router interoperability remains unverified.
