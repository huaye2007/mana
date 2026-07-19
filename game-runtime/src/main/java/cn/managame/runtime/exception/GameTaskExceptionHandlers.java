package cn.managame.runtime.exception;

import cn.managame.runtime.context.GameTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局异常处理器持有者。默认实现打错误日志；宿主启动期可通过
 * {@link #setHandler(GameTaskExceptionHandler)} 替换（如接监控告警）。
 */
public final class GameTaskExceptionHandlers {

    private final static Logger logger = LoggerFactory.getLogger(GameTaskExceptionHandlers.class);

    private final static GameTaskExceptionHandler DEFAULT = (context, cause) ->
            logger.error("game task failed, taskType={}, group={}, routerKey={}",
                    context.getTaskType(), context.getGroup(), context.getRouterKey(), cause);

    private static volatile GameTaskExceptionHandler handler = DEFAULT;

    private GameTaskExceptionHandlers() {
    }

    public static void setHandler(GameTaskExceptionHandler exceptionHandler) {
        if (exceptionHandler == null) {
            throw new IllegalArgumentException("exceptionHandler must not be null");
        }
        handler = exceptionHandler;
    }

    public static void resetToDefault() {
        handler = DEFAULT;
    }

    /**
     * 路由异常到当前全局处理器。处理器自身抛出的异常兜底打日志，绝不向上传播。
     */
    public static void handle(GameTaskContext context, Throwable cause) {
        try {
            handler.handle(context, cause);
        } catch (Exception e) {
            logger.error("exception handler itself failed", e);
            logger.error("original task exception", cause);
        }
    }
}
