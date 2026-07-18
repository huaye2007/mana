package cn.managame.common.context;

/**
 * 框架组件共用的键值元数据：{@code short key} + {@code long}/{@code String} 值。
 * <p>
 * 显式类型用于 RPC 线协议无歧义编解码；同时保留可变 POJO API，供任务上下文增量补充元数据。
 * 调用 {@link #setVal(long)} 或 {@link #setStrVal(String)} 时会同步切换当前值类型。
 */
public class Metadata {

    public static final byte TYPE_STRING = 0;
    public static final byte TYPE_LONG = 1;

    private short key;
    private byte type = TYPE_LONG;
    private long val;
    private String strVal;

    public Metadata() {
    }

    private Metadata(short key, byte type, long val, String strVal) {
        this.key = key;
        this.type = type;
        this.val = val;
        this.strVal = strVal;
    }

    public static Metadata ofString(short key, String value) {
        return new Metadata(key, TYPE_STRING, 0L, value == null ? "" : value);
    }

    public static Metadata ofLong(short key, long value) {
        return new Metadata(key, TYPE_LONG, value, null);
    }

    /** 在数组中查找 string 类型的值，不存在或类型不匹配时返回 {@code null}。 */
    public static String findString(Metadata[] metadata, short key) {
        if (metadata == null) {
            return null;
        }
        for (Metadata item : metadata) {
            if (item != null && item.key == key && item.type == TYPE_STRING) {
                return item.strVal;
            }
        }
        return null;
    }

    /** 在数组中查找 long 类型的值，不存在或类型不匹配时返回默认值。 */
    public static long findLong(Metadata[] metadata, short key, long defaultValue) {
        if (metadata == null) {
            return defaultValue;
        }
        for (Metadata item : metadata) {
            if (item != null && item.key == key && item.type == TYPE_LONG) {
                return item.val;
            }
        }
        return defaultValue;
    }

    public short getKey() {
        return key;
    }

    public void setKey(short key) {
        this.key = key;
    }

    public long getVal() {
        return val;
    }

    public void setVal(long val) {
        this.type = TYPE_LONG;
        this.val = val;
        this.strVal = null;
    }

    public String getStrVal() {
        return strVal;
    }

    public void setStrVal(String strVal) {
        this.type = TYPE_STRING;
        this.val = 0L;
        this.strVal = strVal;
    }

    public byte getType() {
        return type;
    }
}
