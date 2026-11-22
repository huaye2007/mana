package cn.managame.example;

import cn.managame.example.ai.AiConfig;
import cn.managame.example.ai.PlayerAiTools;
import cn.managame.example.data.DataConfig;
import cn.managame.example.login.LoginHandle;
import cn.managame.example.login.LoginService;
import cn.managame.example.rpc.RpcConfig;
import cn.managame.example.server.GameServerConfig;
import cn.managame.example.world.EnterWorldHandle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 游戏服启动入口：显式装配数据层、游戏运行时、客户端网关、RPC、AI 三平面及游戏服业务处理器，不混入世界服逻辑。
 * 示例启动参数：{@code -Drpc.node=2 -Drpc.peers=1=127.0.0.1:7101 -Dgame.port=7001 -Dmysql.user=root -Dmysql.password=...}
 */
@Configuration
@Import({
        DataConfig.class,
        GameServerConfig.class,
        RpcConfig.class,
        AiConfig.class,
        LoginService.class,
        LoginHandle.class,
        EnterWorldHandle.class,
        PlayerAiTools.class
})
public class GameServerApp {

    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(GameServerApp.class);
        context.registerShutdownHook();
    }
}
