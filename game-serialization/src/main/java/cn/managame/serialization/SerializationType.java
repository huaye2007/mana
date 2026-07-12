package cn.managame.serialization;

/** Serialization formats and their stable ids on the wire. */
public enum SerializationType {
    JSON((byte) 1),
    PROTOBUF((byte) 2),
    FORY((byte) 3);

    private static final SerializationType[] BY_ID = buildLookup();

    private final byte typeId;

    SerializationType(byte typeId) {
        this.typeId = typeId;
    }

    public byte typeId() {
        return typeId;
    }

    /** Returns the matching wire type, or {@code null} when the id is unknown. */
    public static SerializationType getSerializationType(byte typeId) {
        int index = Byte.toUnsignedInt(typeId);
        return index < BY_ID.length ? BY_ID[index] : null;
    }

    static int maxTypeId() {
        return BY_ID.length - 1;
    }

    private static SerializationType[] buildLookup() {
        int max = 0;
        for (SerializationType type : values()) {
            max = Math.max(max, Byte.toUnsignedInt(type.typeId));
        }

        SerializationType[] lookup = new SerializationType[max + 1];
        for (SerializationType type : values()) {
            int index = Byte.toUnsignedInt(type.typeId);
            if (index == 0) {
                throw new IllegalStateException("typeId 0 is reserved: " + type);
            }
            if (lookup[index] != null) {
                throw new IllegalStateException("duplicate typeId " + index + " for "
                    + type + " and " + lookup[index]);
            }
            lookup[index] = type;
        }
        return lookup;
    }
}
