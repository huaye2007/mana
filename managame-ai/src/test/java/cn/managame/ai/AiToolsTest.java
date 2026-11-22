package cn.managame.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class AiToolsTest {
    public record Purchase(
            @AiParam("Purchased item id") String item,
            @AiParam("Item quantity") int quantity) {}

    public record PurchaseReceipt(long orderId, String status) {}

    public record QueryWindow(
            @AiParam("Operation UUID") UUID id,
            @AiParam("Role snowflake id") long roleId,
            @AiParam("Start timestamp") Instant from,
            @AiParam("Calendar day") LocalDate day,
            @AiParam("Local wall time") LocalDateTime localAt,
            @AiParam("Lookback duration") Duration span,
            @AiParam(value = "Optional filter", required = false) String filter) {}

    /** Contract-only interface usable in a standalone MCP/Agent gateway without handler implementations. */
    public interface MixedGameTools {
        @AiTool(name = "grant", description = "Grant a purchase", audience = "ops", output = PurchaseReceipt.class,
                requireApproval = true, idempotent = true)
        void grant(Purchase purchase);

        @AiTool(name = "queryWindow", description = "Query player history", audience = {"ops", "player"}, readOnly = true)
        PurchaseReceipt queryWindow(QueryWindow window);
    }

    public static class BadStaticTool {
        @AiTool(name = "bad", description = "Static tools are invalid")
        public static void bad(Purchase purchase) {}
    }

    @Test void audienceIsolationContractTypesCallerContextAndSchemas() throws Exception {
        AiTools playerCatalog = AiTools.builder()
                .audience("player")
                .json(AiJson.jacksonStringLongs())
                .registerTypes(List.of(MixedGameTools.class))
                .build();
        assertEquals("player", playerCatalog.audience());
        assertEquals(1, playerCatalog.tools().size());
        assertNull(playerCatalog.tool("grant"));
        assertNotNull(playerCatalog.tool("queryWindow"));

        AiTools opsCatalog = AiTools.builder()
                .audience("ops")
                .json(AiJson.jacksonStringLongs())
                .registerTypes(List.of(MixedGameTools.class))
                .build();
        assertEquals(2, opsCatalog.tools().size());

        AiTools.Tool grantTool = opsCatalog.tool("grant");
        assertTrue(grantTool.requireApproval());
        assertTrue(grantTool.idempotent());
        assertFalse(grantTool.readOnly());
        assertNotNull(grantTool.outputSchema());

        var mapper = new ObjectMapper();
        JsonNode outSchema = mapper.readTree(grantTool.outputSchema());
        assertEquals("string", outSchema.at("/properties/orderId/type").asText());
        assertEquals("int64", outSchema.at("/properties/orderId/format").asText());

        AiTools.Tool queryTool = playerCatalog.tool("queryWindow");
        assertTrue(queryTool.readOnly());
        assertNotNull(queryTool.outputSchema());
        JsonNode schema = mapper.readTree(queryTool.inputSchema());
        assertEquals("string", schema.at("/properties/roleId/type").asText());
        assertEquals("int64", schema.at("/properties/roleId/format").asText());
        assertEquals("Role snowflake id", schema.at("/properties/roleId/description").asText());
        assertEquals("date-time", schema.at("/properties/from/format").asText());
        assertEquals("date", schema.at("/properties/day/format").asText());
        assertEquals("date-time", schema.at("/properties/localAt/format").asText());
        assertEquals("duration", schema.at("/properties/span/format").asText());
        var required = new ArrayList<String>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertTrue(required.containsAll(List.of("id", "roleId", "from", "day", "localAt", "span")));
        assertFalse(required.contains("filter"));

        // Stateless Entry receives authenticated caller separately from decoded AI arguments
        record Caller(String principal, long roleId) {}
        AiTools.Entry<Caller> entry = new AiTools.Entry<>() {
            @Override public CompletionStage<?> dispatch(Caller caller, AiTools.Tool tool, Object value) {
                return CompletableFuture.completedFuture(Map.of("caller", caller.principal(), "value", value));
            }
            @Override public CompletionStage<?> propose(Caller caller, AiTools.Tool tool, Object value) {
                return CompletableFuture.completedFuture(Map.of("proposedBy", caller.principal(), "value", value));
            }
        };

        long snowflake = 9007199254740993L;
        UUID opId = UUID.randomUUID();
        var dispatched = (Map<?, ?>) playerCatalog.call("queryWindow", Map.of(
                "id", opId.toString(),
                "roleId", Long.toString(snowflake),
                "from", "2026-11-22T15:00:00Z",
                "day", "2026-11-22",
                "localAt", "2026-11-22T23:00:00",
                "span", "PT15M"), new Caller("player-42", 42L), entry).toCompletableFuture().join();
        assertEquals("player-42", dispatched.get("caller"));
        QueryWindow decoded = (QueryWindow) dispatched.get("value");
        assertEquals(snowflake, decoded.roleId());
        assertEquals(Instant.parse("2026-11-22T15:00:00Z"), decoded.from());
        assertEquals(LocalDate.parse("2026-11-22"), decoded.day());
        assertEquals(Duration.ofMinutes(15), decoded.span());
        assertNull(decoded.filter());

        // encodeResult uses the catalog's jacksonStringLongs() codec consistently
        assertEquals("{\"orderId\":\"9007199254740993\",\"status\":\"OK\"}",
                playerCatalog.encodeResult(new PurchaseReceipt(snowflake, "OK")));

        // requireApproval routes strictly to propose
        var proposed = (Map<?, ?>) opsCatalog.call("grant", "{\"item\":\"boots\",\"quantity\":2}",
                new Caller("ops-agent", 0L), entry).toCompletableFuture().join();
        assertEquals("ops-agent", proposed.get("proposedBy"));
        assertEquals(new Purchase("boots", 2), proposed.get("value"));

        // Strict validation
        assertThrows(IllegalArgumentException.class, () -> playerCatalog.call("grant", "{}", new Caller("p", 1), entry));
        assertThrows(IllegalArgumentException.class, () -> opsCatalog.call("grant", Map.of("item", "boots", "oops", true), new Caller("o", 0), entry));
        assertThrows(IllegalArgumentException.class, () -> AiJson.jacksonStringLongs().decode("{\"roleId\":null}", QueryWindow.class));
        assertThrows(IllegalArgumentException.class, () -> AiTools.builder().registerTypes(List.of(MixedGameTools.class, MixedGameTools.class)).build());
        assertThrows(IllegalArgumentException.class, () -> AiTools.builder().register(List.of(new BadStaticTool())).build());
    }
}
