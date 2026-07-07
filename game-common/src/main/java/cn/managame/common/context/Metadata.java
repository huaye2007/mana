package cn.managame.common.context;

/**
 * 任务上下文里承载的可变键值元数据：{@code short key} + {@code long val} / {@code String strVal}。
 * <p>
 * 从 game-runtime 下沉到 game-common，便于非 runtime 模块（如后端业务、网关转发侧）复用同一份
 * 元数据载体而无需依赖整个 game-runtime。保持零依赖的可变 POJO 形态。
 */
public class Metadata {
    private short key;
    private long val;
    private String strVal;

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
        this.val = val;
    }

    public String getStrVal() {
        return strVal;
    }

    public void setStrVal(String strVal) {
        this.strVal = strVal;
    }
}
