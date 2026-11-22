package cn.managame.example.ai;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.MetadataKey;
import cn.managame.ai.*;
import cn.managame.runtime.*;
import cn.managame.runtime.executor.RouteExecutors;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GameplayAiTest {
    private static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(1);

    @Test void eventPipelineFeedsDecisionAndChatLanesOnThePlayerRoute() throws Exception {
        var feature = new CompletableFuture<GameplayAi.Purchase>();
        var eventPartition = new CompletableFuture<String>();
        var callRequest = new CompletableFuture<AiRequest>();
        var mapper = new ObjectMapper();

        AiEventSink mq = batch -> {
            for (AiEvent event : batch) {
                if ("purchase.completed".equals(event.event())) {
                    eventPartition.complete(event.partitionKey());
                    feature.complete(mapper.readValue(event.payload(), GameplayAi.Purchase.class));
                }
            }
        };
        AiTransport aiGateway = new AiTransport() {
            @Override public String call(AiRequest request, Duration remaining) throws Exception {
                callRequest.complete(request);
                var req = mapper.readValue(request.payload(), GameplayAi.EquipmentRequest.class);
                var purchase = feature.get(remaining.toMillis(), TimeUnit.MILLISECONDS);
                String selection = purchase.roleId() == req.roleId() && req.available().contains(purchase.item())
                        ? purchase.item() : req.available().getFirst();
                return mapper.writeValueAsString(new GameplayAi.Choice(selection));
            }
            @Override public void stream(AiRequest request, Duration remaining, Consumer<String> onChunk) {
                onChunk.accept("{\"text\":\"Welcome\"}");
                onChunk.accept("{\"text\":\" back!\"}");
            }
        };

        try (var runtime = GameRuntime.builder().domain(1, RouteExecutors.virtualThreads()).build();
             var publisher = AiEventPublisher.builder(mq)
                     .events(List.of(new AiEventPublisher.EventType("purchase.completed", GameplayAi.Purchase.class)))
                     .limits(1, 64, 16, Duration.ofMillis(10))
                     .build();
             var client = AiClient.builder(aiGateway)
                     .lane(GameplayAi.DECISION_LANE, 8, 32)
                     .lane(GameplayAi.CHAT_LANE, 32, 64)
                     .build()) {
            var game = new GameplayAi(publisher, client, runtime);
            var suggestion = new CompletableFuture<String>();
            var chatText = new StringBuilder();
            var chatDoneOnRoute = new CompletableFuture<Long>();

            GameContext ctx = GameContext.of(1, 42);
            ctx.put(TRACE_ID, "trace-42");
            runtime.execute(ctx, task -> {
                game.purchased(new GameplayAi.Purchase("P7", 42, "boots"));
                game.recommend(new GameplayAi.EquipmentRequest(42, List.of("sword", "boots")), () -> 1, item -> {
                    if (Context.current().routeKey() == 42) suggestion.complete(item);
                });
                game.chat(new GameplayAi.ChatRequest(42, "blacksmith", "hello"),
                        chunk -> { synchronized (chatText) { chatText.append(chunk); } },
                        error -> { if (error == null) chatDoneOnRoute.complete(Context.current().routeKey()); });
            });

            assertEquals("42", eventPartition.get(4, TimeUnit.SECONDS));
            assertEquals("boots", suggestion.get(4, TimeUnit.SECONDS));
            AiRequest sent = callRequest.get(4, TimeUnit.SECONDS);
            assertEquals("trace-42", sent.metadata().get(TRACE_ID));
            assertNotNull(sent.responseSchema());
            assertEquals(42L, chatDoneOnRoute.get(4, TimeUnit.SECONDS));
            assertEquals("Welcome back!", chatText.toString());
        }
    }
}
