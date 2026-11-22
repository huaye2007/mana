package cn.managame.example;

import cn.managame.example.rpc.RpcConfig;
import cn.managame.example.world.WorldHandle;
import cn.managame.example.world.WorldServerConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 世界服启动入口：纯世界服进程，只装配 WORLD 域运行时、RPC 节点和 WorldHandle，不依赖数据库或客户端网关。
 * 示例启动参数：{@code -Drpc.node=1 -Drpc.port=7101}
 */
@Configuration
@Import({
        WorldServerConfig.class,
        RpcConfig.class,
        WorldHandle.class
})
public class WorldServerApp {

    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(WorldServerApp.class);
        context.registerShutdownHook();
    }
}
