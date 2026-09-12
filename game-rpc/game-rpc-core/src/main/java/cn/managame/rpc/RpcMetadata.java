package cn.managame.rpc;

import io.netty.buffer.ByteBuf;

/** Metadata put/get facade. Encoding, validation and storage operations live in RpcMetadataUtil. */
public final class RpcMetadata {
    public static final RpcMetadata EMPTY = new RpcMetadata(RpcMetadataUtil.emptyStorage());
    final RpcMetadataUtil.Storage storage;

    public RpcMetadata() {
        this(RpcLimits.DEFAULT.maxMetadataBytes());
    }

    public RpcMetadata(int maximum) {
        this(new RpcMetadataUtil.Storage(maximum));
    }

    RpcMetadata(RpcMetadataUtil.Storage storage) {
        this.storage = storage;
    }

    public RpcMetadata putInt(short key, int value) {
        return RpcMetadataUtil.putInt(this, key, value);
    }

    public RpcMetadata putLong(short key, long value) {
        return RpcMetadataUtil.putLong(this, key, value);
    }

    public RpcMetadata putString(short key, String value) {
        return RpcMetadataUtil.putString(this, key, value);
    }

    public RpcMetadata putBoolean(short key, boolean value) {
        return RpcMetadataUtil.putBoolean(this, key, value);
    }

    public RpcMetadata put(short key, byte[] value) {
        return RpcMetadataUtil.put(this, key, value);
    }

    public RpcMetadata put(short key, ByteBuf value) {
        return RpcMetadataUtil.put(this, key, value);
    }

    public int getInt(short key) {
        return RpcMetadataUtil.getInt(this, key);
    }

    public long getLong(short key) {
        return RpcMetadataUtil.getLong(this, key);
    }

    public String getString(short key) {
        return RpcMetadataUtil.getString(this, key);
    }

    public boolean getBoolean(short key) {
        return RpcMetadataUtil.getBoolean(this, key);
    }

    public byte[] get(short key) {
        return RpcMetadataUtil.get(this, key);
    }

    public ByteBuf getBuffer(short key) {
        return RpcMetadataUtil.getBuffer(this, key);
    }

    public boolean contains(short key) {
        return RpcMetadataUtil.contains(this, key);
    }

    public int encodedLength() {
        return RpcMetadataUtil.encodedLength(this);
    }

    public ByteBuf encoded() {
        return RpcMetadataUtil.encoded(this);
    }

    public static RpcMetadata copyOf(ByteBuf source, int maximum) {
        return RpcMetadataUtil.copyOf(source, maximum);
    }

    static void validate(ByteBuf source, int maximum) {
        RpcMetadataUtil.validate(source, maximum);
    }

    public static Builder builder() {
        return builder(RpcLimits.DEFAULT.maxMetadataBytes());
    }

    public static Builder builder(int maximum) {
        return new Builder(maximum);
    }

    /** Optional snapshot builder; direct construction and put/get are the primary facade. */
    public static final class Builder {
        final RpcMetadata metadata;

        public Builder(int maximum) {
            metadata = new RpcMetadata(maximum);
        }

        Builder(RpcMetadata source, int maximum) {
            metadata = RpcMetadataUtil.mutableCopy(source, maximum);
        }

        public Builder putInt(short key, int value) {
            metadata.putInt(key, value);
            return this;
        }

        public Builder putLong(short key, long value) {
            metadata.putLong(key, value);
            return this;
        }

        public Builder putString(short key, String value) {
            metadata.putString(key, value);
            return this;
        }

        public Builder putBoolean(short key, boolean value) {
            metadata.putBoolean(key, value);
            return this;
        }

        public Builder put(short key, byte[] value) {
            metadata.put(key, value);
            return this;
        }

        public Builder put(short key, ByteBuf value) {
            metadata.put(key, value);
            return this;
        }

        public RpcMetadata build() {
            return RpcMetadataUtil.snapshot(metadata);
        }
    }
}
