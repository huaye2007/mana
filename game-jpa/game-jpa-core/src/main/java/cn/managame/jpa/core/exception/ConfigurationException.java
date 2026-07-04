package cn.managame.jpa.core.exception;

/**
 * 配置错误：如 {@link cn.managame.jpa.core.routing.RoutingStrategy} 产出的数据源名 / 物理表未注册或不可用。
 * <p>
 * 属确定性失败（重试也不会成功），但语义上是<b>配置问题</b>而非数据问题，应被显著告警、单独计量，
 * 而不是与字段超长/约束冲突等数据错误一起在 warn 级别静默丢弃。异步刷盘据此把它单列分流。
 */
public class ConfigurationException extends GameJpaException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
