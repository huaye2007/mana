package com.github.huaye2007.mana.network.server;

public abstract class NetworkServerConfig {

    private String host = "0.0.0.0";
    private int port;
    private int bossThreads = 1;
    private int workerThreads = 0;
    private int idleSeconds = 0;
    private boolean tcpNoDelay = true;
    private int soBacklog = 1024;

    protected NetworkServerConfig() {
    }

    protected NetworkServerConfig(int port) {
        setPort(port);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        if(host == null || host.isBlank()){
            this.host = "0.0.0.0";
            return;
        }
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        if(port < 0 || port > 65535){
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        this.port = port;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(int bossThreads) {
        if(bossThreads <= 0){
            throw new IllegalArgumentException("bossThreads must be positive");
        }
        this.bossThreads = bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        if(workerThreads < 0){
            throw new IllegalArgumentException("workerThreads must be zero or positive");
        }
        this.workerThreads = workerThreads;
    }

    public int getIdleSeconds() {
        return idleSeconds;
    }

    public void setIdleSeconds(int idleSeconds) {
        if(idleSeconds < 0){
            throw new IllegalArgumentException("idleSeconds must be zero or positive");
        }
        this.idleSeconds = idleSeconds;
    }

    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    public void setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
    }

    public int getSoBacklog() {
        return soBacklog;
    }

    public void setSoBacklog(int soBacklog) {
        if(soBacklog <= 0){
            throw new IllegalArgumentException("soBacklog must be positive");
        }
        this.soBacklog = soBacklog;
    }

}
