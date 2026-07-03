package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.http.HttpProtocol;

public class NetworkHttpServerConfig extends NetworkServerConfig {

    private int httpMaxContentLength = 65536;
    private HttpProtocol httpProtocol = HttpProtocol.HTTP1;

    public NetworkHttpServerConfig() {
    }

    public NetworkHttpServerConfig(int port) {
        super(port);
    }

    public int getHttpMaxContentLength() {
        return httpMaxContentLength;
    }

    public void setHttpMaxContentLength(int httpMaxContentLength) {
        if(httpMaxContentLength <= 0){
            throw new IllegalArgumentException("httpMaxContentLength must be positive");
        }
        this.httpMaxContentLength = httpMaxContentLength;
    }

    public HttpProtocol getHttpProtocol() {
        return httpProtocol;
    }

    public void setHttpProtocol(HttpProtocol httpProtocol) {
        this.httpProtocol = httpProtocol == null ? HttpProtocol.HTTP1 : httpProtocol;
    }
}
