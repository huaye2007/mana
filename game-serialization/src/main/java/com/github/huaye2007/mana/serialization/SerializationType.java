package com.github.huaye2007.mana.serialization;

public enum SerializationType {
    JSON((byte) 1),
    PROTOBUF((byte) 2),
    FURY((byte) 3);

    /** 按 typeId 直接索引的查找表，避免每帧解码都 values() 克隆数组 + 线性扫描（热路径）。 */
    private static final SerializationType[] BY_ID = buildLookup();

    private final byte typeId;

    SerializationType(byte typeId) {
        this.typeId = typeId;
    }

    public byte typeId() {
        return typeId;
    }

    /** 未知 typeId 返回 {@code null}。O(1) 查表。 */
    public static SerializationType getSerializationType(byte type) {
        return type >= 0 && type < BY_ID.length ? BY_ID[type] : null;
    }

    private static SerializationType[] buildLookup() {
        SerializationType[] values = values();
        byte max = 0;
        for (SerializationType e : values) {
            if (e.typeId > max) {
                max = e.typeId;
            }
        }
        SerializationType[] table = new SerializationType[max + 1];
        for (SerializationType e : values) {
            if (e.typeId < 0) {
                throw new IllegalStateException("typeId must be non-negative: " + e);
            }
            if (table[e.typeId] != null) {
                throw new IllegalStateException("duplicate typeId " + e.typeId
                    + " for " + e + " and " + table[e.typeId]);
            }
            table[e.typeId] = e;
        }
        return table;
    }
}
