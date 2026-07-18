package cn.managame.jpa.async;

import cn.managame.jpa.core.exception.ConfigurationException;
import cn.managame.jpa.core.exception.RetriableWriteException;

/** 把存储层异常分为可重试、配置错误和确定性失败。 */
@FunctionalInterface
public interface WriteFailureClassifier {

    enum Kind {
        RETRIABLE,
        CONFIGURATION,
        PERMANENT
    }

    Kind classify(Throwable failure);

    /**
     * 默认分类器。存储适配器应把连接中断、超时、死锁、主从切换等包装为
     * {@link RetriableWriteException}；调用方也可通过
     * {@link FlushScheduler#failureClassifier(WriteFailureClassifier)} 扩展分类规则。
     */
    WriteFailureClassifier DEFAULT = failure -> {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConfigurationException) {
                return Kind.CONFIGURATION;
            }
            if (current instanceof RetriableWriteException) {
                return Kind.RETRIABLE;
            }
        }
        return Kind.PERMANENT;
    };
}
