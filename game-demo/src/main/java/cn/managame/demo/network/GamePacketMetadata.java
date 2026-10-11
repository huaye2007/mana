package cn.managame.demo.network;

import cn.managame.core.*;

/** Application-owned keys for the demo packet header; they are not reserved Core keys. */
public final class GamePacketMetadata {
    public static final MetadataKey<Integer> COMMAND = MetadataKey.intKey(1);
    public static final MetadataKey<Integer> SEQ = MetadataKey.intKey(2);
    public static final MetadataKey<Integer> CODE = MetadataKey.intKey(3);
    private GamePacketMetadata() {}
    public static Metadata from(GamePacket packet) {
        return Metadata.builder().put(COMMAND, packet.getCommand()).put(SEQ, packet.getSeq()).put(CODE, packet.getCode()).build();
    }
}
