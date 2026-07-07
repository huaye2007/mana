package cn.managame.runtime.context;

import cn.managame.common.context.Metadata;

import java.util.Arrays;

public class GameTaskContext {

    private GameTaskType taskType;

    private byte group;

    private long routerKey;

    private byte busType;

    private long busId;

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
        this.metadatas = metadatas;
    }

    public void addMetadataStrVal(short key,String val){
        findOrCreateMetadata(key).setStrVal(val);
    }

    public void addMetadataLongVal(short key,long val){
        findOrCreateMetadata(key).setVal(val);
    }

    public Metadata getMetadata(short key){
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

    private Metadata findOrCreateMetadata(short key) {
        Metadata existing = getMetadata(key);
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
        return metadatas;
    }
}
