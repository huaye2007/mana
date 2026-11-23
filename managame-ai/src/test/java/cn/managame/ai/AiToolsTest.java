package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_INVALID_ARGS;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_REJECTED;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.ai.annotation.AiTool;
import cn.managame.ai.spi.AiToolExecutor;
import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class AiToolsTest {

    record AddItem(long roleId, int itemId, int count) {}
    record ItemAdded(long roleId, int itemId, int total) {}

    static class SampleTools {
        @AiTool(name = "online_count", description = "Count online players", group = "gm", readOnly = true)
        int onlineCount() { return 42; }

        @AiTool(name = "add_item", description = "Add items to a role", group = "gm")
        ItemAdded addItem(AddItem req) { return new ItemAdded(req.roleId(), req.itemId(), req.count() + 10); }

        @AiTool(name = "check_bag", description = "Check bag with business failure", group = "npc", readOnly = true)
        AiToolResult checkBag(AddItem req) {
            if (req.roleId() <= 0) return AiToolResult.fail(10001, "Role not found");
            return AiToolResult.ok(new ItemAdded(req.roleId(), req.itemId(), 5));
        }

        @AiTool(name = "async_mail", description = "Send mail asynchronously", group = "gm")
        void asyncMail(AiToolContext context, AddItem req) {
            String trace = context.header("x-trace-id");
            CompletableFuture.runAsync(() -> context.reply("sent:" + req.roleId() + ":" + trace));
        }

        @AiTool(name = "raw_echo", description = "Raw JSON echo", group = "npc", readOnly = true)
        String rawEcho(String rawJson) { return rawJson; }

        @AiTool(name = "returns_null", description = "Buggy tool returning null")
        String returnsNull() { return null; }

        @AiTool(name = "throws_error", description = "Buggy tool throwing")
        String throwsError() { throw new IllegalStateException("boom"); }
    }

    @Test void invokesSyncAsyncBusinessFailRawAndProgrammaticTools() throws Exception {
        AtomicReference<Throwable> lastError = new AtomicReference<>();
        TestMetrics metrics = new TestMetrics();
        AiTools tools = AiTools.builder()
                .codec(new JacksonAiCodec())
                .executor(AiToolExecutor.direct())
                .errorHandler((ctx, err) -> lastError.set(err))
                .metrics(metrics)
                .register(List.of(new SampleTools()))
                .register("dynamic_ping", "Programmatic ping", "npc", true, (ctx, raw) -> ctx.reply("pong:" + raw))
                .build();

        assertEquals(8, tools.definitions().size());
        assertNotNull(tools.find("add_item"));

        assertEquals(42, invokeSync(tools, "online_count", "{}").data());
        assertEquals(new ItemAdded(1001, 500, 13),
                invokeSync(tools, "add_item", "{\"roleId\":1001,\"itemId\":500,\"count\":3}").data());

        AiToolResult bizFail = invokeSync(tools, "check_bag", "{\"roleId\":0,\"itemId\":1,\"count\":1}");
        assertFalse(bizFail.isSuccess());
        assertEquals(10001, bizFail.code());
        assertEquals("Role not found", bizFail.message());

        CompletableFuture<AiToolResult> asyncRes = new CompletableFuture<>();
        tools.invoke("async_mail", "{\"roleId\":7,\"itemId\":1,\"count\":1}",
                Map.of("X-Trace-Id", "abc"), null, asyncRes::complete);
        assertEquals("sent:7:abc", asyncRes.get(5, TimeUnit.SECONDS).data());

        assertEquals("{\"k\":1}", invokeSync(tools, "raw_echo", "{\"k\":1}").data());
        assertEquals("pong:{\"a\":1}", invokeSync(tools, "dynamic_ping", "{\"a\":1}").data());

        AiToolResult nullBug = invokeSync(tools, "returns_null", "{}");
        assertEquals(AI_TOOL_EXECUTION_ERROR, nullBug.code());
        assertNotNull(lastError.get());

        lastError.set(null);
        AiToolResult threw = invokeSync(tools, "throws_error", "{}");
        assertEquals(AI_TOOL_EXECUTION_ERROR, threw.code());
        assertEquals("boom", lastError.get().getMessage());

        assertEquals(AI_TOOL_NOT_FOUND, invokeSync(tools, "missing", "{}").code());
        assertEquals(AI_TOOL_INVALID_ARGS, invokeSync(tools, "add_item", "{bad-json").code());

        assertEquals(1L, metrics.count("managame.ai.tool.not_found"));
        assertEquals(1L, metrics.count("managame.ai.tool.invalid_args"));
        assertEquals(1L, metrics.count("managame.ai.tool.calls", "tool", "add_item"));
        assertEquals(1L, metrics.count("managame.ai.tool.failures", "tool", "throws_error"));
    }

    @Test void interceptorsFilterDiscoveryAndOrderBeforeAfter() {
        List<String> trace = new ArrayList<>();
        AiToolInterceptor groupFilter = new AiToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, AiToolDefinition tool) {
                String allowedGroup = headers.get("x-group");
                return allowedGroup == null || allowedGroup.equals(tool.group());
            }

            @Override public boolean before(AiToolContext context, AiToolDefinition tool, Object argument) {
                trace.add("before-1");
                String allowedGroup = context.header("X-Group");
                if (allowedGroup != null && !allowedGroup.equals(tool.group())) {
                    context.fail(AI_TOOL_REJECTED, "group denied");
                    return false;
                }
                return true;
            }

            @Override public void after(AiToolContext context, AiToolDefinition tool, Object argument,
                                        AiToolResult result, Throwable failure) {
                trace.add("after-1");
            }
        };
        AiToolInterceptor second = new AiToolInterceptor() {
            @Override public boolean before(AiToolContext context, AiToolDefinition tool, Object argument) {
                trace.add("before-2");
                return !"online_count".equals(tool.name());
            }

            @Override public void after(AiToolContext context, AiToolDefinition tool, Object argument,
                                        AiToolResult result, Throwable failure) {
                trace.add("after-2");
            }
        };

        AiTools tools = AiTools.builder()
                .codec(new JacksonAiCodec())
                .executor(AiToolExecutor.direct())
                .intercept(List.of(groupFilter, second))
                .register(List.of(new SampleTools()))
                .build();

        List<AiToolDefinition> npcTools = tools.list(Map.of("X-GROUP", "npc"));
        assertEquals(List.of("check_bag", "raw_echo"), npcTools.stream().map(AiToolDefinition::name).toList());

        AiToolResult denied = invokeSync(tools, "add_item", "{\"roleId\":1,\"itemId\":2,\"count\":3}", Map.of("x-group", "npc"));
        assertEquals(AI_TOOL_REJECTED, denied.code());
        assertEquals("group denied", denied.message());
        assertEquals(List.of("before-1"), trace);

        trace.clear();
        AiToolResult rejectedBySecond = invokeSync(tools, "online_count", "{}", Map.of("x-group", "gm"));
        assertEquals(AI_TOOL_REJECTED, rejectedBySecond.code());
        assertEquals(List.of("before-1", "before-2", "after-1"), trace);

        trace.clear();
        AiToolResult ok = invokeSync(tools, "add_item", "{\"roleId\":1,\"itemId\":2,\"count\":3}", Map.of("x-group", "gm"));
        assertEquals(SUCCESS, ok.code());
        assertEquals(List.of("before-1", "before-2", "after-2", "after-1"), trace);
    }

    @Test void executorRejectionRepliesWithToolRejected() {
        AtomicInteger calls = new AtomicInteger();
        AiTools tools = AiTools.builder()
                .executor((call, invocation) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("route overloaded");
                })
                .register(List.of(new SampleTools()))
                .build();

        AiToolResult res = invokeSync(tools, "online_count", "{}");
        assertEquals(1, calls.get());
        assertEquals(AI_TOOL_REJECTED, res.code());
        assertEquals("route overloaded", res.message());
    }

    @Test void interceptorErrorsAndDuplicateReplySafety() {
        List<String> events = new ArrayList<>();
        AiToolInterceptor first = new AiToolInterceptor() {
            @Override public boolean before(AiToolContext ctx, AiToolDefinition tool, Object arg) {
                events.add("before-1");
                return true;
            }
            @Override public void after(AiToolContext ctx, AiToolDefinition tool, Object arg, AiToolResult res, Throwable err) {
                events.add("after-1");
            }
        };
        AiToolInterceptor explodingAfter = new AiToolInterceptor() {
            @Override public boolean before(AiToolContext ctx, AiToolDefinition tool, Object arg) {
                events.add("before-2");
                return true;
            }
            @Override public void after(AiToolContext ctx, AiToolDefinition tool, Object arg, AiToolResult res, Throwable err) {
                events.add("after-2");
                throw new RuntimeException("after-boom");
            }
        };

        AtomicInteger errors = new AtomicInteger();
        AtomicInteger callbackCount = new AtomicInteger();
        AiTools tools = AiTools.builder()
                .executor(AiToolExecutor.direct())
                .intercept(List.of(first, explodingAfter))
                .errorHandler((ctx, err) -> errors.incrementAndGet())
                .register("double_reply", "Replies twice", "test", true, (ctx, raw) -> {
                    assertTrue(ctx.reply("first"));
                    assertFalse(ctx.reply("second"));
                    assertFalse(ctx.fail(1234, "ignored"));
                })
                .build();

        AtomicReference<AiToolResult> result = new AtomicReference<>();
        tools.invoke("double_reply", "{}", r -> {
            callbackCount.incrementAndGet();
            result.set(r);
        });
        assertEquals(1, callbackCount.get());
        assertEquals("first", result.get().data());
        assertEquals(1, errors.get());
        assertEquals(List.of("before-1", "before-2", "after-2", "after-1"), events);
    }

    @Test void rejectsInvalidToolRegistrationsEagerly() {
        class Duplicate {
            @AiTool(name = "dup") int a() { return 1; }
            @AiTool(name = "dup") int b() { return 2; }
        }
        class VoidNoContext {
            @AiTool void bad() {}
        }
        class TooManyParams {
            @AiTool int bad(AiToolContext ctx, String a, String b) { return 1; }
        }
        class StaticTool {
            @AiTool static int bad() { return 1; }
        }

        assertThrows(IllegalArgumentException.class, () -> AiTools.builder().register(List.of(new Duplicate())));
        assertThrows(IllegalArgumentException.class, () -> AiTools.builder().register(List.of(new VoidNoContext())));
        assertThrows(IllegalArgumentException.class, () -> AiTools.builder().register(List.of(new TooManyParams())));
        assertThrows(IllegalArgumentException.class, () -> AiTools.builder().register(List.of(new StaticTool())));
    }

    private static AiToolResult invokeSync(AiTools tools, String name, String args) {
        return invokeSync(tools, name, args, Map.of());
    }

    private static AiToolResult invokeSync(AiTools tools, String name, String args, Map<String, String> headers) {
        AtomicReference<AiToolResult> ref = new AtomicReference<>();
        tools.invoke(name, args, headers, null, ref::set);
        assertTrue(ref.get() != null);
        return ref.get();
    }

    static final class TestMetrics implements Metrics {
        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();

        @Override public Counter counter(String name, String... tags) {
            return counters.computeIfAbsent(key(name, tags), k -> new LongAdder())::add;
        }

        @Override public Meter counter(String name, LongSupplier count, String... tags) {
            String k = key(name, tags);
            gauges.put(k, count::getAsLong);
            return () -> gauges.remove(k);
        }

        @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
            String k = key(name, tags);
            gauges.put(k, value);
            return () -> gauges.remove(k);
        }

        long count(String name, String... tags) {
            LongAdder adder = counters.get(key(name, tags));
            return adder == null ? 0L : adder.sum();
        }

        double gaugeValue(String name, String... tags) {
            DoubleSupplier s = gauges.get(key(name, tags));
            return s == null ? -1 : s.getAsDouble();
        }

        private static String key(String name, String... tags) {
            return tags.length == 0 ? name : name + List.of(tags);
        }
    }
}
