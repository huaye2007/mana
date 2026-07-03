package com.github.huaye2007.mana.rpc;

public class ConnectionTargetConfig {
    private String serviceName;
    private String serviceId;
    private String ip;
    private int port;
    private int connectionSize;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getConnectionSize() {
        return connectionSize;
    }

    public void setConnectionSize(int connectionSize) {
        this.connectionSize = connectionSize;
    }
}
