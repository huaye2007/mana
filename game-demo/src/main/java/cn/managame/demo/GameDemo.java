package cn.managame.demo;

import cn.managame.demo.bus.user.User;
import cn.managame.demo.bus.user.UserRepository;
import cn.managame.demo.bus.user.UserService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.concurrent.CountDownLatch;

public class GameDemo {
    public static void main(String[] args) throws InterruptedException {
        var context = new AnnotationConfigApplicationContext();
        context.scan("cn.managame.demo");
        run(context);

        UserService userService = context.getBean(UserService.class);
        User user = userService.getAndCreateUser(System.nanoTime());
        UserRepository userRepository = context.getBean(UserRepository.class);
        int serverId = 1000;
        Long roleId = user.getServerRoleIdMap().get(serverId);
        if(roleId == null){
            roleId = System.nanoTime();
            user.getServerRoleIdMap().put(serverId,roleId);
            userRepository.update(user);
        }
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
