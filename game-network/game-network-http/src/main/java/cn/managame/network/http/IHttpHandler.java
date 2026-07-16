package cn.managame.network.http;

@FunctionalInterface
public interface IHttpHandler {

    void handle(HttpRequest request, IHttpResponder responder);
}
