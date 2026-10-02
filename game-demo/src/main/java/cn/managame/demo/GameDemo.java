package cn.managame.demo;

import cn.managame.demo.bus.user.User;
import cn.managame.demo.bus.user.UserRepository;
import cn.managame.demo.bus.user.UserService;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.NetworkServer;
import cn.managame.demo.network.GamePacket;
import cn.managame.demo.network.GamePacketDecoder;
import cn.managame.demo.network.GamePacketEncoder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

public class GameDemo {
    public static void main(String[] args) throws InterruptedException {
        var context = new AnnotationConfigApplicationContext();
        context.scan("cn.managame.demo");
        run(context);

        ConnectionHandler handler = new ConnectionHandler() {
            public void onConnected(Connection c) {}
            public void onMessage(Connection c, Object message) {
                GamePacket packet = (GamePacket) message;
                if (c.write(packet) != WriteStatus.ACCEPTED) c.close();
            }
            public void onDisconnected(Connection c) {}
            public void onException(Connection c, Throwable cause) {
                cause.printStackTrace();
                c.close();
            }
        };
        NetworkServer server = NetworkServer.builder()
                .bindAddress(new InetSocketAddress(9000))
                .pipeline(pipeline -> pipeline.addLast(new GamePacketDecoder(), new GamePacketEncoder()))
                .handler(handler)
                .build();
        context.addApplicationListener((ContextClosedEvent event) -> server.close());
        server.start();
//        UserService userService = context.getBean(UserService.class);
//        User user = userService.getAndCreateUser(System.nanoTime());
//        UserRepository userRepository = context.getBean(UserRepository.class);
//        int serverId = 1000;
//        Long roleId = user.getServerRoleIdMap().get(serverId);
//        if(roleId == null){
//            roleId = System.nanoTime();
//            user.getServerRoleIdMap().put(serverId,roleId);
//            userRepository.update(user);
//        }
    }

    static void run(AnnotationConfigApplicationContext context) throws InterruptedException {
//        try (context) {
//            var stopped = new CountDownLatch(1);
//            context.addApplicationListener((ContextClosedEvent event) -> stopped.countDown());
            context.refresh();
            context.registerShutdownHook();
            System.out.println("game-demo started. Press Ctrl+C to stop.");
//            stopped.await();
//        }
    }
}
