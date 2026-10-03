package cn.managame.demo.network;

import cn.managame.core.*;

/** Application-owned keys for the demo packet header; they are not reserved Core keys. */
public final class GamePacketMetadata {
    public static final MetadataKey<Integer> COMMAND = MetadataKeys.intKey(1);
    public static final MetadataKey<Integer> SEQ = MetadataKeys.intKey(2);
    public static final MetadataKey<Integer> CODE = MetadataKeys.intKey(3);
    private GamePacketMetadata() {}
    public static Metadata from(GamePacket packet) {
        return Metadatas.builder().put(COMMAND, packet.getCommand()).put(SEQ, packet.getSeq()).put(CODE, packet.getCode()).build();
    }
}
