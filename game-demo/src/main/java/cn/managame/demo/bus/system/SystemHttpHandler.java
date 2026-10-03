package cn.managame.demo.bus.system;

import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.runtime.http.HttpContext;
import cn.managame.runtime.http.HttpHandler;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.http.HttpRequestMethod;
import io.netty.handler.codec.http.FullHttpRequest;

import java.nio.charset.StandardCharsets;

@HttpHandler(domain = GameDomain.SYSTEM_ID, routeKey = "routeKey")
public class SystemHttpHandler {
    private final DemoTasks tasks;

    public SystemHttpHandler(DemoTasks tasks) { this.tasks = tasks; }

    @HttpMethod("/demo/echo")
    public EchoResult echo(HttpContext context, FullHttpRequest request) {
        return new EchoResult(context.routeKey(), request.content().toString(StandardCharsets.UTF_8));
    }

    @HttpMethod(value = "/demo/tasks", method = HttpRequestMethod.GET)
    public DemoTasks.Status status() { return tasks.status(); }

    public record EchoResult(long routeKey, String body) {}
}
