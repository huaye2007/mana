package cn.managame.rpc.transport;

/** Local transport admission only; ACCEPTED does not imply delivery or remote execution. */
public enum RpcSendStatus {
    ACCEPTED, PEER_NOT_FOUND, UNAVAILABLE
}

