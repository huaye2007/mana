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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GameplayAiTest {
    private static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(1);

    @Test void eventPipelineFeedsDecisionAndPerPlayerNpcChatSessionsWithTruncationSync() throws Exception {
        var feature = new CompletableFuture<GameplayAi.Purchase>();
        var truncatedEvent = new CompletableFuture<GameplayAi.ChatTruncated>();
        var eventPartition = new CompletableFuture<String>();
        var callRequest = new CompletableFuture<AiRequest>();
        var turn1Closed = new CountDownLatch(1);
        var turn1Started = new CountDownLatch(1);
        var turn2Observer = new CompletableFuture<AiTransport.StreamObserver>();
        var otherPlayerObserver = new CompletableFuture<AiTransport.StreamObserver>();
        var mapper = new ObjectMapper();

        AiEventSink mq = batch -> {
            for (AiEvent event : batch) {
                if ("purchase.completed".equals(event.event())) {
                    eventPartition.complete(event.partitionKey());
                    feature.complete(mapper.readValue(event.payload(), GameplayAi.Purchase.class));
                } else if ("chat.truncated".equals(event.event())) {
                    truncatedEvent.complete(mapper.readValue(event.payload(), GameplayAi.ChatTruncated.class));
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
            @Override public AutoCloseable streamAsync(AiRequest request, Duration remaining, StreamObserver observer) {
                if ("42:blacksmith".equals(request.routingKey()) && turn1Started.getCount() > 0) {
                    observer.onChunk("{\"text\":\"First \"}");
                    turn1Started.countDown();
                    return turn1Closed::countDown;
                }
                if ("42:blacksmith".equals(request.routingKey())) {
                    turn2Observer.complete(observer);
                    return () -> {};
                }
                otherPlayerObserver.complete(observer);
                return () -> {};
            }
        };

        try (var runtime = GameRuntime.builder().domain(1, RouteExecutors.virtualThreads()).build();
             var publisher = AiEventPublisher.builder(mq)
                     .events(List.of(
                             new AiEventPublisher.EventType("purchase.completed", GameplayAi.Purchase.class),
                             new AiEventPublisher.EventType("chat.truncated", GameplayAi.ChatTruncated.class)))
                     .limits(1, 64, 16, Duration.ofMillis(10))
                     .build();
             var client = AiClient.builder(aiGateway)
                     .service(GameplayAi.DECISION_SERVICE, s -> s.limits(8, 32).perKey(2, AiClient.KeyConflict.REJECT))
                     .service(GameplayAi.CHAT_SERVICE, s -> s.limits(32, 64).perKey(1, AiClient.KeyConflict.CANCEL_PREVIOUS))
                     .build()) {
            var game = new GameplayAi(publisher, client, runtime);
            var suggestion = new CompletableFuture<String>();
            var p42Turn2Text = new StringBuilder();
            var p42Turn2Done = new CompletableFuture<Long>();
            var p99Text = new StringBuilder();
            var p99Done = new CompletableFuture<Long>();

            GameContext ctx42 = GameContext.of(1, 42);
            ctx42.put(TRACE_ID, "trace-42");
            runtime.execute(ctx42, task -> {
                game.purchased(new GameplayAi.Purchase("P7", 42, "boots"));
                game.recommend(new GameplayAi.EquipmentRequest(42, List.of("sword", "boots")), () -> 1, item -> {
                    if (Context.current().routeKey() == 42) suggestion.complete(item);
                });
                // Player 42 starts turn 1 with blacksmith
                game.chat(new GameplayAi.ChatRequest(42, "blacksmith", "craft sword"), chunk -> {}, err -> {});
            });

            // Player 99 talks to the SAME blacksmith concurrently (independent memory session 99:blacksmith)
            runtime.execute(GameContext.of(1, 99), task -> game.chat(
                    new GameplayAi.ChatRequest(99, "blacksmith", "hello"),
                    chunk -> { synchronized (p99Text) { p99Text.append(chunk); } },
                    err -> { if (err == null) p99Done.complete(Context.current().routeKey()); }));

            assertTrue(turn1Started.await(3, TimeUnit.SECONDS));

            // Player 42 interrupts turn 1 with turn 2 on 42:blacksmith -> cancels turn 1 & emits chat.truncated
            runtime.execute(ctx42, task -> game.chat(
                    new GameplayAi.ChatRequest(42, "blacksmith", "actually shield"),
                    chunk -> { synchronized (p42Turn2Text) { p42Turn2Text.append(chunk); } },
                    err -> { if (err == null) p42Turn2Done.complete(Context.current().routeKey()); }));

            var obs2 = turn2Observer.get(3, TimeUnit.SECONDS);
            obs2.onChunk("{\"text\":\"Shield ready!\"}");
            obs2.onComplete();

            var obs99 = otherPlayerObserver.get(3, TimeUnit.SECONDS);
            obs99.onChunk("{\"text\":\"Hi 99!\"}");
            obs99.onComplete();

            assertEquals("42", eventPartition.get(4, TimeUnit.SECONDS));
            assertEquals("boots", suggestion.get(4, TimeUnit.SECONDS));
            AiRequest sent = callRequest.get(4, TimeUnit.SECONDS);
            assertEquals("trace-42", sent.metadata().get(TRACE_ID));
            assertEquals("42", sent.routingKey());
            assertNotNull(sent.responseSchema());

            assertTrue(turn1Closed.await(3, TimeUnit.SECONDS));
            GameplayAi.ChatTruncated truncated = truncatedEvent.get(4, TimeUnit.SECONDS);
            assertEquals(42L, truncated.roleId());
            assertEquals("blacksmith", truncated.npcId());
            assertEquals(6, truncated.deliveredChars());

            assertEquals(42L, p42Turn2Done.get(4, TimeUnit.SECONDS));
            assertEquals("Shield ready!", p42Turn2Text.toString());
            assertEquals(99L, p99Done.get(4, TimeUnit.SECONDS));
            assertEquals("Hi 99!", p99Text.toString());
        }
    }
}
