package cn.managame.rpc;

import io.netty.buffer.ByteBuf;

import java.time.Duration;
import java.util.Objects;

public record RpcOptions(
        long routeKey, byte busType, long busId, RpcMetadata metadata, Duration timeout) {
    public static final RpcOptions DEFAULT =
            new RpcOptions(0, (byte) 0, 0, RpcMetadata.EMPTY, null);

    public RpcOptions {
        RpcChecks.business(busType, busId);
        metadata = RpcMetadataUtil.snapshot(Objects.requireNonNull(metadata, "metadata"));
        if (timeout != null) RpcChecks.timeoutNanos(timeout);
    }

    public static RpcOptions route(long key) {
        return new RpcOptions(key, (byte) 0, 0, RpcMetadata.EMPTY, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long routeKey, busId;
        private byte busType;
        private RpcMetadata metadata = RpcMetadata.EMPTY;
        private RpcMetadata.Builder metadataBuilder;
        private Duration timeout;

        public Builder routeKey(long v) {
            routeKey = v;
            return this;
        }

        public Builder busType(byte v) {
            busType = v;
            return this;
        }

        public Builder busId(long v) {
            busId = v;
            return this;
        }

        public Builder metadata(RpcMetadata v) {
            metadata = Objects.requireNonNull(v);
            metadataBuilder = null;
            return this;
        }

        private RpcMetadata.Builder metadataBuilder() {
            if (metadataBuilder == null)
                metadataBuilder =
                        new RpcMetadata.Builder(metadata, RpcLimits.DEFAULT.maxMetadataBytes());
            return metadataBuilder;
        }

        public Builder putInt(short key, int value) {
            RpcMetadataUtil.putInt(metadataBuilder(), key, value);
            return this;
        }

        public Builder putBoolean(short key, boolean value) {
            RpcMetadataUtil.putBoolean(metadataBuilder(), key, value);
            return this;
        }

        public Builder putLong(short key, long value) {
            RpcMetadataUtil.putLong(metadataBuilder(), key, value);
            return this;
        }

        public Builder putString(short key, String value) {
            RpcMetadataUtil.putString(metadataBuilder(), key, value);
            return this;
        }

        public Builder put(short key, byte[] value) {
            metadataBuilder().put(key, value);
            return this;
        }

        public Builder put(short key, ByteBuf value) {
            metadataBuilder().put(key, value);
            return this;
        }

        public Builder timeout(Duration v) {
            timeout = v;
            return this;
        }

        public RpcOptions build() {
            return new RpcOptions(
                    routeKey,
                    busType,
                    busId,
                    metadataBuilder == null ? metadata : metadataBuilder.build(),
                    timeout);
        }
    }
}
