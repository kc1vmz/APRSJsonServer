package com.kc1vmz.aprsjson.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;

class FeedTest {
    @Test
    void resizingHistoryKeepsNewestEventsAndStreamSequence() throws Exception {
        var mapper = new ObjectMapper();
        var feed = new Feed(mapper, () -> "N0CALL", System.nanoTime(), "all", 4);
        UUID source = UUID.randomUUID();
        var packet = PacketFormat.packet(new byte[] {'x'});
        for (int i = 0; i < 4; i++) feed.publish(source, "N0CALL", Instant.now(), packet);
        feed.resizeHistory(2);
        assertThat(feed.snapshot(4)).extracting(event -> event.get("sequence")).containsExactly(4L, 3L);
        var records = feed.stream().take(2).collectList().toFuture();
        feed.publish(source, "N0CALL", Instant.now(), packet);
        var result = records.get(2, TimeUnit.SECONDS);
        assertThat(mapper.readTree(result.getFirst()).path("capabilities").path("history_events").asInt())
                .isEqualTo(2);
        assertThat(mapper.readTree(result.getLast()).path("sequence").asLong()).isEqualTo(5);
    }

    @Test
    void queuesLiveReceptionDuringReplayAndPreservesOrder() throws Exception {
        var mapper = new ObjectMapper();
        var feed = new Feed(mapper, () -> "N0CALL", System.nanoTime(), "all", 128);
        UUID source = UUID.randomUUID();
        var packet = PacketFormat.packet(new byte[] {'x'});
        feed.publish(source, "N0CALL", Instant.now(), packet);
        String after = (String) feed.snapshot(1).getFirst().get("event_id");
        for (int i = 0; i < 100; i++) feed.publish(source, "N0CALL", Instant.now(), packet);

        List<byte[]> records = feed.stream(after).index()
                .doOnNext(record -> {
                    if (record.getT1() == 1) feed.publish(source, "N0CALL", Instant.now(), packet);
                })
                .map(record -> record.getT2())
                .take(102).collectList().block(Duration.ofSeconds(2));
        assertThat(records).hasSize(102);
        JsonNode hello = mapper.readTree(records.getFirst());
        assertThat(hello.path("latest_sequence").asLong()).isEqualTo(101);
        assertThat(hello.path("capabilities").path("history_events").asInt()).isEqualTo(128);
        for (int i = 1; i < records.size(); i++)
            assertThat(mapper.readTree(records.get(i)).path("sequence").asLong()).isEqualTo(i + 1L);
    }

    @Test
    void emitsGapReasonsAndContinuesWithLiveEvents() throws Exception {
        var mapper = new ObjectMapper();
        var feed = new Feed(mapper, () -> "N0CALL", System.nanoTime(), "all", 2);
        UUID source = UUID.randomUUID();
        var packet = PacketFormat.packet(new byte[] {'x'});
        feed.publish(source, "N0CALL", Instant.now(), packet);
        String expired = (String) feed.snapshot(1).getFirst().get("event_id");
        var restarted = new Feed(mapper, () -> "N0CALL", System.nanoTime(), "all", 2);
        var priorBoot = restarted.stream(expired).take(3).collectList().toFuture();
        restarted.publish(source, "N0CALL", Instant.now(), packet);
        var restartedRecords = priorBoot.get(2, TimeUnit.SECONDS);
        JsonNode rebootGap = mapper.readTree(restartedRecords.get(1));
        assertThat(rebootGap.path("reason").asText()).isEqualTo("different_boot");
        assertThat(rebootGap.has("oldest_available")).isFalse();
        assertThat(mapper.readTree(restartedRecords.getLast()).path("sequence").asLong()).isEqualTo(1);

        feed.publish(source, "N0CALL", Instant.now(), packet);
        feed.publish(source, "N0CALL", Instant.now(), packet);
        String latest = (String) feed.snapshot(1).getFirst().get("event_id");
        var live = feed.stream(latest).take(2).collectList().toFuture();
        var tooOld = feed.stream(expired).take(2).collectList().block(Duration.ofSeconds(2));
        var unknown = feed.stream("another-feed:" + latest).take(2).collectList().block(Duration.ofSeconds(2));
        JsonNode gap = mapper.readTree(tooOld.getLast());
        assertThat(gap.path("reason").asText()).isEqualTo("history_expired");
        assertThat(gap.path("latest_available").asText()).isEqualTo(latest);
        assertThat(mapper.readTree(unknown.getLast()).path("reason").asText()).isEqualTo("unknown_event");
        feed.publish(source, "N0CALL", Instant.now(), packet);
        assertThat(mapper.readTree(live.get(2, TimeUnit.SECONDS).getLast()).path("sequence").asLong())
                .isEqualTo(4);
    }

    @Test
    void resolvesStationFromTheInputType() {
        UUID id = UUID.randomUUID();
        var aprs = new Connection(id, "Internet", Connection.Type.APRS_IS, false, "localhost", 14580,
                "N0CALL-7", null, null, null, null, null, null, "IGNORED", null);
        assertThat(aprs.validated(id, null, "COMBINED").producerStation()).isEqualTo("N0CALL-7");

        var kiss = new Connection(id, "Radio", Connection.Type.KISS_TCP, false, "localhost", 8001,
                null, null, null, null, null, null, null, null, null);
        Connection migrated = kiss.validated(id, null, "N0CALL");
        assertThat(kiss.validated(id, migrated, "UPDATED").producerStation()).isEqualTo("N0CALL");
        assertThat(migrated.redacted().producerStation()).isEqualTo("N0CALL");
    }

    @Test
    void subscribersShareOneSequenceAndNeverSeePriorSnapshotEvents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Feed feed = new Feed(mapper, () -> "N0CALL", System.nanoTime(), "all", 32);
        UUID source = UUID.randomUUID();
        var packet = PacketFormat.packet(new byte[] {'x'});
        feed.publish(source, "N0CALL", Instant.now(), packet);
        var a = feed.stream().take(3).collectList().toFuture();
        var b = feed.stream().take(3).collectList().toFuture();
        feed.publish(source, "N0CALL", Instant.now(), packet);
        feed.publish(source, "N0CALL", Instant.now(), packet);
        List<byte[]> records = a.get(2, TimeUnit.SECONDS);
        assertThat(b.get(2, TimeUnit.SECONDS)).hasSize(3);
        assertThat(mapper.readTree(records.getFirst()).path("latest_sequence").asLong()).isEqualTo(1);
        assertThat(mapper.readTree(records.get(1)).path("sequence").asLong()).isEqualTo(2);
        assertThat(mapper.readTree(records.get(2)).path("sequence").asLong()).isEqualTo(3);
        assertThat(feed.snapshot(2)).extracting(event -> event.get("sequence")).containsExactly(3L, 2L);
        assertThat(feed.subscribers()).isZero();
        feed.close();
        assertThat(feed.stream().collectList().block(Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void boundedQueueDisconnectsOnlyTheSlowSubscriber() throws Exception {
        Feed feed = new Feed(new ObjectMapper(), () -> "N0CALL", System.nanoTime(), "all", 32);
        var slow = new BaseSubscriber<byte[]>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {}

            @Override
            protected void hookOnError(Throwable failure) {
                assertThat(failure).hasMessage("Slow APRS subscriber");
            }
        };
        feed.stream().subscribe(slow);
        var fast = feed.stream().take(100).collectList().toFuture();
        UUID source = UUID.randomUUID();
        var packet = PacketFormat.packet(new byte[] {'x'});
        for (int i = 0; i < 99; i++) feed.publish(source, "N0CALL", Instant.now(), packet);
        assertThat(fast.get(2, TimeUnit.SECONDS)).hasSize(100);
        assertThat(feed.slowClients()).isEqualTo(1);
        assertThat(feed.subscribers()).isZero();
    }
}
