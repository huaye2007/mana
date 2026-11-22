package cn.managame.example.ai;

import cn.managame.FrameworkErrorCodes;
import cn.managame.ai.AiTools;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.dispatch.Dispatcher;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 游戏服内接收外部 AI 工具调用的统一网关入口（供独立 MCP 网关经 RPC 或 HTTP 转发过来）。
 * 按鉴权后的受众选择对应 {@link AiTools} 目录，把可信调用者 {@link GameContext} 与不可信 AI 入参分离：
 * 普通工具走 {@link Dispatcher} 进入业务路由，需要审批的高危工具只进入 {@link ProposalSink}。
 */
public final class AiToolGateway {

    /** 高危工具（{@code requireApproval = true}）的持久化提案入口。 */
    @FunctionalInterface
    public interface ProposalSink {
        CompletionStage<?> submit(GameContext caller, AiTools.Tool tool, Object message);
    }

    private final Map<String, AiTools> catalogs;
    private final AiTools.Entry<GameContext> entry;

    public AiToolGateway(Dispatcher dispatcher, ProposalSink proposals, Map<String, AiTools> catalogs) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(proposals, "proposals");
        this.catalogs = Map.copyOf(catalogs);
        this.entry = new AiTools.Entry<>() {
            @Override public CompletionStage<?> dispatch(GameContext caller, AiTools.Tool tool, Object message) {
                var answer = new CompletableFuture<Object>();
                dispatcher.dispatch(caller, message, result -> {
                    if (result.code() == FrameworkErrorCodes.SUCCESS) answer.complete(result.data());
                    else answer.completeExceptionally(new IllegalArgumentException(
                            "Game error " + result.code() + " " + Arrays.toString(result.args())));
                });
                return answer;
            }

            @Override public CompletionStage<?> propose(GameContext caller, AiTools.Tool tool, Object message) {
                return proposals.submit(caller, tool, message);
            }
        };
    }

    public AiTools catalog(String audience) {
        AiTools catalog = catalogs.get(audience);
        if (catalog == null) throw new IllegalArgumentException("Unknown AI audience: " + audience);
        return catalog;
    }

    /** 按受众解码 JSON 参数、执行或提案，并用同一目录的编解码器返回结果 JSON。 */
    public CompletionStage<String> invokeJson(String audience, String toolName, String argumentsJson, GameContext caller) {
        AiTools catalog = catalog(audience);
        return catalog.call(toolName, argumentsJson, caller, entry).thenApply(catalog::encodeResult);
    }

    /** 按受众直接转换结构化 Map 参数（如来自 MCP SDK）并执行或提案。 */
    public CompletionStage<String> invokeMap(String audience, String toolName, Map<String, ?> arguments, GameContext caller) {
        AiTools catalog = catalog(audience);
        return catalog.call(toolName, arguments, caller, entry).thenApply(catalog::encodeResult);
    }
}
