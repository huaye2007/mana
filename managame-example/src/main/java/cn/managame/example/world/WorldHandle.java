package cn.managame.example.world;

import cn.managame.example.DomainConstant;
import cn.managame.example.proto.World;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.HandleResult;
import java.util.HashSet;
import java.util.Set;

/** 世界服的处理器，和客户端请求的处理器写法一样，经 RPC 调到这里。都在世界的那一条路由上依次执行，所以不用加锁。 */
@Domain(DomainConstant.WORLD)
public class WorldHandle {
    /** 世界已满，参数是人数上限。 */
    public static final int WORLD_FULL = 10_001;
    private static final int MAX_ONLINE = 10_000;
    private final Set<Long> online = new HashSet<>();

    @HandleMethod
    public HandleResult join(World.JoinWorldReq joinWorldReq) {
        if (online.size() >= MAX_ONLINE && !online.contains(joinWorldReq.getRoleId()))
            return HandleResult.fail(WORLD_FULL, String.valueOf(MAX_ONLINE));
        online.add(joinWorldReq.getRoleId());
        return HandleResult.ok(World.JoinWorldRes.newBuilder().setOnline(online.size()).build());
    }
}
