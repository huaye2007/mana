package cn.managame.dev.server;

import cn.managame.dev.protocol.GameErrorCode;

/**
 * 业务拒绝异常：handler 里抛出后由 {@link GameTaskFailureReplier} 按 {@link #getCode()}
 * 给客户端回错误码（空 body），不打堆栈——业务拒绝是正常流程，不是故障。
 *
 * <p>code 取值见 {@link GameErrorCode}，业务可在其之外扩展自己的号段。
 * 不捕获堆栈（构造走 writableStackTrace=false），高频抛出无性能负担。</p>
 */
public class GameBusinessException extends RuntimeException {

    private final int code;

    public GameBusinessException(int code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
