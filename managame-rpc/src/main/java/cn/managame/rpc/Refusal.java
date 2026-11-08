package cn.managame.rpc;

/** Why a connection was refused: the words for the log, and the reason operators count it under. */
record Refusal(Reason reason, String text) {
    enum Reason {
        /** Frames out of order, or a handshake that contradicts itself. */
        PROTOCOL,
        /** The two nodes speak different protocol versions. */
        VERSION,
        /** The wrong node answered, a node claims this one's id, or two nodes dial each other. */
        IDENTITY,
        /** The {@link RpcAuthenticator} said no. */
        ADMISSION,
        /** An older instance dials while a newer one is connected, or one instance disagrees on its connections. */
        INSTANCE,
        /** The peer was removed, or this node closed. */
        CLOSED
    }
}
