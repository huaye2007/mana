package cn.managame.jpa.core.exception;

/**
 * 数据库连接异常。
 * 用于连接获取失败、连接断开等场景。
 */
public class ConnectionException extends RetriableWriteException {

    private final String dataSourceName;

    public ConnectionException(String message, String dataSourceName, Throwable cause) {
        super(message, cause);
        this.dataSourceName = dataSourceName;
    }

    public String dataSourceName() { return dataSourceName; }
}
