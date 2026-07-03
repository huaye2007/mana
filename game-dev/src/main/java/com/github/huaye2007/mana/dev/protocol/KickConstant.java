package com.github.huaye2007.mana.dev.protocol;

/**
 * 踢下线推送常量。
 *
 * <p>服务端主动断开前先推一帧：{@code command=1, seq=0, code=原因, body 空}，
 * 客户端据此区分“被踢”与普通断线。原因取值见 {@link GameErrorCode}
 * （如 {@link GameErrorCode#DUPLICATE_LOGIN}）。</p>
 *
 * <p>1~999 保留给服务端主动推送的系统命令；业务命令从 1000 起。客户端发上来的
 * 系统命令不会命中 CommandRegistry，会按未知 command 回错误帧。</p>
 */
public final class KickConstant {

    /** 踢下线推送 command。 */
    public static final int COMMAND = 1;

    private KickConstant() {
    }
}
