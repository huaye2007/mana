package cn.managame.runtime.context;

import cn.managame.common.context.Metadata;

import java.util.Arrays;

public class GameTaskContext {

    private final GameTaskType taskType;

    private final byte group;

    private final long routerKey;

    private final byte busType;

    private final long busId;

    private Metadata[] metadatas;

    public GameTaskContext(GameTaskType taskType,byte group, long routerKey, byte busType, long busId,Metadata[] metadatas) {
        if (routerKey == 0) {
            // routerKey 哈希到组内固定 worker，0 会让所有"无路由键"任务挤到 worker-0 形成热点。
            // 强制非 0：全局/无玩家归属的业务也必须给出一个可散列的键（如连接 id、公会 id）。
            throw new IllegalArgumentException("routerKey must not be 0, taskType=" + taskType + ", group=" + group);
        }
        this.taskType = taskType;
        this.group = group;
        this.routerKey = routerKey;
        this.busType = busType;
        this.busId = busId;
        this.metadatas = copyMetadatas(metadatas);
    }

    public void addMetadataStrVal(short key,String val){
        findOrCreateMetadata(key).setStrVal(val);
    }

    public void addMetadataLongVal(short key,long val){
        findOrCreateMetadata(key).setVal(val);
    }

    public Metadata getMetadata(short key){
        return copyMetadata(findMetadata(key));
    }

    private Metadata findOrCreateMetadata(short key) {
        Metadata existing = findMetadata(key);
        if (existing != null) {
            return existing;
        }
        Metadata metadata = new Metadata();
        metadata.setKey(key);
        if (metadatas == null) {
            metadatas = new Metadata[]{metadata};
        } else {
            metadatas = Arrays.copyOf(metadatas, metadatas.length + 1);
            metadatas[metadatas.length - 1] = metadata;
        }
        return metadata;
    }

    private Metadata findMetadata(short key) {
        if (metadatas == null) {
            return null;
        }
        for (Metadata metadata : metadatas) {
            if (metadata != null && metadata.getKey() == key) {
                return metadata;
            }
        }
        return null;
    }

    private static Metadata[] copyMetadatas(Metadata[] source) {
        if (source == null) {
            return null;
        }
        Metadata[] copy = new Metadata[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = copyMetadata(source[i]);
        }
        return copy;
    }

    private static Metadata copyMetadata(Metadata metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.getType() == Metadata.TYPE_STRING
                ? Metadata.ofString(metadata.getKey(), metadata.getStrVal())
                : Metadata.ofLong(metadata.getKey(), metadata.getVal());
    }

    public GameTaskType getTaskType() {
        return taskType;
    }

    public byte getGroup() {
        return group;
    }

    public long getRouterKey() {
        return routerKey;
    }

    public byte getBusType() {
        return busType;
    }

    public long getBusId() {
        return busId;
    }

    public Metadata[] getMetadatas() {
        return copyMetadatas(metadatas);
    }
}
