package cn.managame.network;


/** Common client lifecycle. Protocol-specific implementations expose their connect parameters. */
public interface NetworkClient {
    /** Initialize the client before connecting. Repeated calls while initialized have no effect. */
    void init();

    /**
     * Close managed connections and owned resources. A destroyed client cannot be initialized
     * again.
     */
    void destroy();
}
