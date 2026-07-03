package com.github.huaye2007.mana.rpc.protocol;

/**
 * 帧级元数据：short key + string/long 值，不可变。
 */
public final class Metadata {

    public static final byte TYPE_STRING = 0;
    public static final byte TYPE_LONG = 1;

    /** metadata key 1~99 保留给框架（握手身份、错误描述、token 等），业务自定义 key 从这里起 */
    public static final short KEY_BUSINESS_MIN = 100;

    /** 在数组中找 string 类型 key 的值，没有返回 null */
    public static String findString(Metadata[] metadata, short key) {
        if (metadata == null) {
            return null;
        }
        for (Metadata item : metadata) {
            if (item.key == key && item.type == TYPE_STRING) {
                return item.strVal;
            }
        }
        return null;
    }

    /** 在数组中找 long 类型 key 的值，没有返回 defaultValue */
    public static long findLong(Metadata[] metadata, short key, long defaultValue) {
        if (metadata == null) {
            return defaultValue;
        }
        for (Metadata item : metadata) {
            if (item.key == key && item.type == TYPE_LONG) {
                return item.lval;
            }
        }
        return defaultValue;
    }

    private final short key;
    private final byte type;
    private final String strVal;
    private final long lval;

    private Metadata(short key, byte type, String strVal, long lval) {
        this.key = key;
        this.type = type;
        this.strVal = strVal;
        this.lval = lval;
    }

    public static Metadata ofString(short key, String value) {
        return new Metadata(key, TYPE_STRING, value == null ? "" : value, 0L);
    }

    public static Metadata ofLong(short key, long value) {
        return new Metadata(key, TYPE_LONG, null, value);
    }

    public short getKey() {
        return key;
    }

    public byte getType() {
        return type;
    }

    public String getStrVal() {
        return strVal;
    }

    public long getLval() {
        return lval;
    }
}
