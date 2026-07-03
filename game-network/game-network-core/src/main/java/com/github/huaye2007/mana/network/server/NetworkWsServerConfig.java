package com.github.huaye2007.mana.network.server;

public class NetworkWsServerConfig extends NetworkServerConfig {

    private String websocketPath = "/";
    private int httpMaxContentLength = 65536;

    public NetworkWsServerConfig() {
    }

    public NetworkWsServerConfig(int port) {
        super(port);
    }

    public String getWebsocketPath() {
        return websocketPath;
    }

    public void setWebsocketPath(String websocketPath) {
        if(websocketPath == null || websocketPath.isBlank()){
            this.websocketPath = "/";
            return;
        }
        this.websocketPath = websocketPath.startsWith("/") ? websocketPath : "/" + websocketPath;
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
}
