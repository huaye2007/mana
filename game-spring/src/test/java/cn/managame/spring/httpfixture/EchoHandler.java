package cn.managame.spring.httpfixture;

import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.http.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@HttpHandler(domain = 1, routeKey = "id")
public class EchoHandler {
    public final CountDownLatch entered = new CountDownLatch(1);
    public final CountDownLatch release = new CountDownLatch(1);
    public record Input(String text) {}
    public record Output(String text, String uri, long key, boolean virtual) {}

    @HttpMethod("/echo") public Output echo(Input input) { return output(input.text()); }
    @HttpMethod("/raw") public Output raw(String body) { return output(body); }
    @HttpMethod(value = "/query", method = HttpRequestMethod.GET)
    public Output query(Input input) { return output(input.text()); }
    @HttpMethod(value = "/", method = HttpRequestMethod.GET)
    public Output root() { return output("root"); }
    @HttpMethod("/wait") public Output waitForRelease(Input input) throws InterruptedException {
        entered.countDown();
        if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
        return output(input.text());
    }
    private Output output(String text) {
        var context = Contexts.current(HttpContext.class);
        return new Output(text, context.request().uri(), context.routeKey(), Thread.currentThread().isVirtual());
    }
}
