package cn.managame.example.aiservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 独立外部 AI 服务启动入口：纯 Spring Boot + Spring AI 实现，不依赖任何 managame 框架 jar，
 * 通过 HTTP/SSE 接受游戏服调用，通过标准 MCP Streamable HTTP 回调游戏服的 {@code @McpTool}。
 */
@SpringBootApplication
public class GameAiServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GameAiServiceApplication.class, args);
    }
}
