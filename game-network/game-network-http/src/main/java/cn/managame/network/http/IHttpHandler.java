package cn.managame.network.http;

/**
 * Handles an immutable, transport-neutral request on its channel event-loop thread.
 * The handler may return before responding and complete {@code responder} from another thread.
 * Blocking work must be offloaded by the implementation.
 */
@FunctionalInterface
public interface IHttpHandler {

    void handle(HttpRequest request, IHttpResponder responder);
}
