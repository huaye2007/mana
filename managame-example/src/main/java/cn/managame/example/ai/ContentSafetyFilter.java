package cn.managame.example.ai;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 游戏服出站前的 AI 内容安全过滤器：
 * <ul>
 *   <li>{@link #filter(String)}：用于整段 AI 回复脱敏</li>
 *   <li>{@link #newStreamFilter(Consumer)}：用于流式 SSE 输出的滑动前缀保留过滤——普通分片零延迟立即下发，
 *       末尾若命中敏感词真前缀则暂留，能拦截被拆在两个 SSE chunk 之间的敏感词（如 {@code "私" + "服"}）</li>
 * </ul>
 */
@Component
public class ContentSafetyFilter {
    private static final List<String> DEFAULT_BLOCKED = List.of("外挂", "私服", "代练", "free-hack");

    private final List<String> blockedWords;

    public ContentSafetyFilter() {
        this(DEFAULT_BLOCKED);
    }

    public ContentSafetyFilter(List<String> blockedWords) {
        this.blockedWords = List.copyOf(Objects.requireNonNull(blockedWords, "blockedWords"));
    }

    public String filter(String text) {
        if (text == null || text.isEmpty()) return "";
        String result = text;
        for (String word : blockedWords) {
            if (!word.isEmpty() && result.contains(word)) {
                result = result.replace(word, "*".repeat(word.length()));
            }
        }
        return result;
    }

    /** 为单次流式响应创建有状态过滤接收器（仅在单个流回调序列中使用）。 */
    public StreamFilter newStreamFilter(Consumer<String> downstream) {
        Objects.requireNonNull(downstream, "downstream");
        return new StreamFilter(downstream);
    }

    public final class StreamFilter {
        private final Consumer<String> downstream;
        private final StringBuilder pending = new StringBuilder();

        private StreamFilter(Consumer<String> downstream) {
            this.downstream = downstream;
        }

        public synchronized void accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            pending.append(chunk);
            String replaced = filter(pending.toString());
            int hold = longestPrefixSuffix(replaced);
            int emitLen = replaced.length() - hold;
            pending.setLength(0);
            if (hold > 0) pending.append(replaced, emitLen, replaced.length());
            if (emitLen > 0) downstream.accept(replaced.substring(0, emitLen));
        }

        public synchronized void finish() {
            if (pending.isEmpty()) return;
            String rest = filter(pending.toString());
            pending.setLength(0);
            if (!rest.isEmpty()) downstream.accept(rest);
        }

        private int longestPrefixSuffix(String text) {
            int maxHold = 0;
            for (String word : blockedWords) {
                int limit = Math.min(text.length(), word.length() - 1);
                for (int len = limit; len > maxHold; len--) {
                    if (text.regionMatches(text.length() - len, word, 0, len)) {
                        maxHold = len;
                        break;
                    }
                }
            }
            return maxHold;
        }
    }
}
