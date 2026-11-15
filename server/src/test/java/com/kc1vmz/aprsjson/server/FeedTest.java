package com.kc1vmz.aprsjson.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void resolvesStationFromTheInputType() {
        UUID id = UUID.randomUUID();
        var aprs = new Connection(id, "Internet", Connection.Type.APRS_IS, false, "localhost", 14580,
                "N0CALL-7", null, null, null, null, null, null, "IGNORED");
        assertThat(aprs.validated(id, null, "COMBINED").producerStation()).isEqualTo("N0CALL-7");

        var kiss = new Connection(id, "Radio", Connection.Type.KISS_TCP, false, "localhost", 8001,
                null, null, null, null, null, null, null, null);
        Connection migrated = kiss.validated(id, null, "N0CALL");
        assertThat(kiss.validated(id, migrated, "UPDATED").producerStation()).isEqualTo("N0CALL");
        assertThat(migrated.redacted().producerStation()).isEqualTo("N0CALL");
    }

    @Test
    void subscribersShareOneSequenceAndNeverSeePriorSnapshotEvents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Feed feed = new Feed(mapper, () -> "N0CALL", System.nanoTime());
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
        Feed feed = new Feed(new ObjectMapper(), () -> "N0CALL", System.nanoTime());
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
