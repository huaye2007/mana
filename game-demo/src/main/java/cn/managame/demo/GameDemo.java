package cn.managame.demo;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class GameDemo {
    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext("cn.managame.demo");
        context.registerShutdownHook();
    }
}
