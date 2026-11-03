package cn.managame.example;

import cn.managame.runtime.annotation.Domain;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(includeFilters = @ComponentScan.Filter(Domain.class))
public class Example {

    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Example.class);
        context.registerShutdownHook();

    }
}
