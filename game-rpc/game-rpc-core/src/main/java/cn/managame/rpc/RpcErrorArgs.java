package cn.managame.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.nio.charset.StandardCharsets;

/** Canonical error payload: repeated signed int32 UTF-8 byte length followed by string bytes. */
final class RpcErrorArgs {
    private RpcErrorArgs() {}

    static int encodedLength(String[] args, int maximum) {
        long size = 0;
        for (String value : args) {
            size += 4;
            if (size + value.length() > maximum)
                throw new RpcProtocolException("Error arguments limit");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x80) size++;
                else if (c < 0x800) size += 2;
                else if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                        throw new RpcProtocolException("Invalid error argument Unicode");
                    size += 4;
                } else if (Character.isLowSurrogate(c))
                    throw new RpcProtocolException("Invalid error argument Unicode");
                else size += 3;
                if (size > maximum) throw new RpcProtocolException("Error arguments limit");
            }
        }
        return (int) size;
    }

    static void write(ByteBuf out, String[] args) {
        for (String value : args) {
            int length = ByteBufUtil.utf8Bytes(value);
            out.writeInt(length);
            ByteBufUtil.reserveAndWriteUtf8(out, value, length);
        }
    }

    static String[] read(ByteBuf body) {
        int end = body.writerIndex(), count = 0;
        // Validate all lengths and UTF-8 before allocating the result array.
        for (int offset = body.readerIndex(); offset < end; count++) {
            if (end - offset < 4) throw new RpcProtocolException("Truncated error argument length");
            int length = body.getInt(offset);
            offset += 4;
            if (length < 0 || length > end - offset)
                throw new RpcProtocolException("Error argument length");
            if (!ByteBufUtil.isText(body, offset, length, StandardCharsets.UTF_8))
                throw new RpcProtocolException("Invalid error argument UTF-8");
            offset += length;
        }
        String[] result = new String[count];
        int offset = body.readerIndex();
        for (int i = 0; i < count; i++) {
            int length = body.getInt(offset);
            offset += 4;
            result[i] = body.toString(offset, length, StandardCharsets.UTF_8);
            offset += length;
        }
        return result;
    }
}
