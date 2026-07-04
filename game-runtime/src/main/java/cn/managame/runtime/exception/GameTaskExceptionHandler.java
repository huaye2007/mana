package cn.managame.runtime.exception;

import cn.managame.runtime.context.GameTaskContext;

/**
 * 游戏任务全局异常处理器。command/event/timer/callback 执行中的业务异常
 * 统一汇聚到这里，宿主可注册自定义实现接告警。
 */
public interface GameTaskExceptionHandler {

    void handle(GameTaskContext context, Throwable cause);
}
