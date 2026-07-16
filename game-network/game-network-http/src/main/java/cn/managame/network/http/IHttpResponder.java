package cn.managame.network.http;

import java.nio.charset.StandardCharsets;

/**
 * A thread-safe, one-shot response capability. Exactly one call to {@link #send(HttpResponse)} or
 * {@link #fail(Throwable)} is allowed; later calls fail with {@link IllegalStateException}.
 * The transport may complete an unanswered request itself when its configured timeout expires.
 */
public interface IHttpResponder {

    void send(HttpResponse response);

    void fail(Throwable cause);

    default void send(int status, byte[] body) {
        send(HttpResponse.of(status, body));
    }

    default void ok(byte[] body) {
        send(HttpResponse.ok(body));
    }

    default void text(int status, String body) {
        send(HttpResponse.text(status, body, StandardCharsets.UTF_8));
    }

    default void text(String body) {
        text(200, body);
    }
}
