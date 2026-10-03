package cn.managame.demo.bus.system;

import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.runtime.http.HttpContext;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.http.HttpHandler;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.http.HttpRequestMethod;

@HttpHandler(domain = GameDomain.SYSTEM_ID, routeKey = "routeKey")
public class SystemHttpHandler {
    private final DemoTasks tasks;

    public SystemHttpHandler(DemoTasks tasks) { this.tasks = tasks; }

    @HttpMethod("/demo/echo")
    public EchoResult echo(String body) {
        return new EchoResult(Contexts.current(HttpContext.class).routeKey(), body);
    }
    @HttpMethod("/demo/dto")
    public EchoResult dto(EchoRequest request) {
        return new EchoResult(Contexts.current(HttpContext.class).routeKey(), request.text());
    }
    @HttpMethod(value = "/demo/query", method = HttpRequestMethod.GET)
    public EchoResult query(EchoRequest request) { return dto(request); }
    public record EchoRequest(String text) {}

    @HttpMethod(value = "/demo/tasks", method = HttpRequestMethod.GET)
    public DemoTasks.Status status() { return tasks.status(); }

    public record EchoResult(long routeKey, String body) {}
}
