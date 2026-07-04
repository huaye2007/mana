package cn.managame.dev.protocol;

/**
 * 外网帧 {@code code} 字段的统一取值。
 *
 * <p>0 表示成功；非 0 时 body 一律为空，客户端按 code 提示或重试。
 * 踢下线推送（{@link KickConstant#COMMAND}）复用本表作为踢下线原因。</p>
 */
public final class GameErrorCode {

    /** 成功。 */
    public static final int OK = 0;

    /** 服务器内部错误：handler 抛出未识别异常，业务未执行完成。 */
    public static final int INTERNAL_ERROR = 1;

    /** 未注册的 command。 */
    public static final int UNKNOWN_COMMAND = 2;

    /** 请求非法：body 反序列化失败或参数校验不过。 */
    public static final int BAD_REQUEST = 3;

    /** 未登陆：LOGIN 组之外的命令要求先完成登陆绑定。 */
    public static final int NOT_LOGGED_IN = 4;

    /** 服务器繁忙：worker 队列满任务被丢弃（丢弃即终态，不缓冲不重试）。 */
    public static final int SERVER_BUSY = 5;

    /** 踢下线原因：同账号在别处登陆（顶号）。 */
    public static final int DUPLICATE_LOGIN = 6;

    private GameErrorCode() {
    }
}
