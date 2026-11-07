package cn.managame.example.world;

import cn.managame.example.DomainConstant;
import cn.managame.example.NodeConstant;
import cn.managame.example.proto.World;
import cn.managame.example.rpc.GameRpc;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.HandleContext;
import org.springframework.beans.factory.annotation.Autowired;

/** 游戏服上的玩家进入世界：问世界服，世界服应答后才回客户端，所以方法是 void。世界服满员、连不上等失败由 GameRpc 回给客户端。 */
@Domain(DomainConstant.PLAYER)
public class EnterWorldHandle {
    @Autowired
    private GameRpc rpc;

    @HandleMethod
    public void enterWorld(HandleContext request, World.EnterWorldReq enterWorldReq) {
        World.JoinWorldReq join = World.JoinWorldReq.newBuilder().setRoleId(request.businessId()).build();
        rpc.call(NodeConstant.WORLD, join, (World.JoinWorldRes res) -> request.reply(res));
    }
}
