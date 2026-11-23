package cn.managame.ai.http;

import static cn.managame.FrameworkErrorCodes.AI_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_INVALID_ARGS;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_REJECTED;
import static cn.managame.FrameworkErrorCodes.SUCCESS;

import cn.managame.ai.AiToolDefinition;
import cn.managame.ai.AiToolResult;
import cn.managame.ai.AiTools;
import cn.managame.ai.internal.BasicJsonCodec;
import cn.managame.network.http.HttpExchange;
import cn.managame.network.http.HttpHandler;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.System.Logger.Level;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Thin HTTP/JSON {@link HttpHandler} exposing an {@link AiTools} registry to external AI services (such as a Spring AI
 * service) over plain HTTP, with no coupling to AI-specific wire standards.
 *
 * <ul>
 *   <li>{@code GET <prefix>} (default {@code GET /tools}): lists visible tool metadata (name, description, group, readOnly)</li>
 *   <li>{@code POST <prefix>/<name>} (default {@code POST /tools/<name>}): invokes the named tool asynchronously with a JSON body</li>
 * </ul>
 */
public final class AiToolHttpHandler implements HttpHandler {
    private static final System.Logger LOG = System.getLogger("cn.managame.ai.http");
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final AiTools tools;
    private final String prefix;
    private final String itemPrefix;

    public AiToolHttpHandler(AiTools tools) {
        this(tools, "/tools");
    }

    public AiToolHttpHandler(AiTools tools, String prefix) {
        this.tools = Objects.requireNonNull(tools, "tools");
        if (prefix == null || !prefix.startsWith("/")) {
            throw new IllegalArgumentException("prefix must start with '/': " + prefix);
        }
        String normalized = prefix;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.prefix = normalized;
        this.itemPrefix = normalized.equals("/") ? "/" : normalized + "/";
        LOG.log(Level.INFO, () -> "AiToolHttpHandler ready on " + this.prefix + " (tools=" + tools.definitions().size() + ")");
    }

    @Override public void handle(HttpExchange exchange) {
        String path = exchange.path();
        FullHttpRequest request = exchange.request();
        Map<String, String> headers = extractHeaders(request);

        if (path.equals(prefix)) {
            if (!HttpMethod.GET.equals(request.method())) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            respondJson(exchange, HttpResponseStatus.OK, formatToolList(tools.list(headers)));
            return;
        }

        if (path.startsWith(itemPrefix) && path.length() > itemPrefix.length()) {
            String rawName = path.substring(itemPrefix.length());
            if (rawName.indexOf('/') >= 0) {
                exchange.respond(HttpResponseStatus.NOT_FOUND);
                return;
            }
            if (!HttpMethod.POST.equals(request.method())) {
                methodNotAllowed(exchange, "POST");
                return;
            }
            String toolName;
            try {
                toolName = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException badEncoding) {
                exchange.respond(HttpResponseStatus.BAD_REQUEST);
                return;
            }
            String body = request.content().toString(StandardCharsets.UTF_8);
            tools.invoke(toolName, body, headers, exchange.remoteAddress(), result -> answer(exchange, result));
            return;
        }

        exchange.respond(HttpResponseStatus.NOT_FOUND);
    }

    private void answer(HttpExchange exchange, AiToolResult result) {
        if (result.isSuccess()) {
            String encodedData;
            if (result.data() == null) {
                encodedData = "null";
            } else {
                try {
                    String json = tools.codec().encode(result.data());
                    encodedData = json == null ? "null" : json;
                } catch (Exception e) {
                    LOG.log(Level.ERROR, "Failed to encode AI tool HTTP result", e);
                    respondFailure(exchange, HttpResponseStatus.INTERNAL_SERVER_ERROR, AI_PROTOCOL_ERROR, "Failed to encode tool result");
                    return;
                }
            }
            respondJson(exchange, HttpResponseStatus.OK, "{\"code\":" + SUCCESS + ",\"data\":" + encodedData + "}");
            return;
        }

        HttpResponseStatus status = switch (result.code()) {
            case AI_TOOL_NOT_FOUND -> HttpResponseStatus.NOT_FOUND;
            case AI_TOOL_INVALID_ARGS -> HttpResponseStatus.BAD_REQUEST;
            case AI_TOOL_REJECTED -> HttpResponseStatus.FORBIDDEN;
            case AI_TOOL_EXECUTION_ERROR -> HttpResponseStatus.INTERNAL_SERVER_ERROR;
            default -> HttpResponseStatus.OK;
        };
        respondFailure(exchange, status, result.code(), result.message());
    }

    private static String formatToolList(List<AiToolDefinition> visible) {
        StringBuilder sb = new StringBuilder(64 + visible.size() * 64);
        sb.append("{\"tools\":[");
        for (int i = 0; i < visible.size(); i++) {
            if (i > 0) sb.append(',');
            AiToolDefinition def = visible.get(i);
            sb.append("{\"name\":");
            BasicJsonCodec.writeQuoted(sb, def.name());
            sb.append(",\"description\":");
            BasicJsonCodec.writeQuoted(sb, def.description());
            sb.append(",\"group\":");
            BasicJsonCodec.writeQuoted(sb, def.group());
            sb.append(",\"readOnly\":").append(def.readOnly()).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void respondFailure(HttpExchange exchange, HttpResponseStatus status, int code, String message) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"code\":").append(code);
        if (message != null) {
            sb.append(",\"message\":");
            BasicJsonCodec.writeQuoted(sb, message);
        }
        sb.append('}');
        respondJson(exchange, status, sb.toString());
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED);
        response.headers().set(HttpHeaderNames.ALLOW, allow);
        exchange.respond(response);
    }

    private static void respondJson(HttpExchange exchange, HttpResponseStatus status, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, JSON_CONTENT_TYPE);
        exchange.respond(response);
    }

    private static Map<String, String> extractHeaders(FullHttpRequest request) {
        TreeMap<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : request.headers()) {
            headers.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(headers);
    }
}
