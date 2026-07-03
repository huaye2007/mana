package com.github.huaye2007.mana.network.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public interface IHttpExchange {

    String getProtocol();

    String getMethod();

    String getUri();

    String getHeader(String name);

    byte[] getBody();

    boolean isResponseWritten();

    void writeResponse(int status, byte[] body);

    void writeResponse(int status, Map<String, String> headers, byte[] body);

    default void writeResponse(int status, String body) {
        writeResponse(status, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }
}
