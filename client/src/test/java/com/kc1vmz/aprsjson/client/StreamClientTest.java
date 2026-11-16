package com.kc1vmz.aprsjson.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class StreamClientTest {
    @Test
    void reconnectsWithOpaqueCursorAndAcceptsReplayBelowHelloBoundary() {
        var attempts = new AtomicInteger();
        var resumedQuery = new AtomicReference<String>();
        var builder = WebClient.builder().exchangeFunction(request -> {
            if (attempts.getAndIncrement() == 0) {
                return Mono.just(response(hello(0) + rx(1, "cursor:+&"), false));
            }
            resumedQuery.set(request.url().getRawQuery());
            return Mono.just(response(hello(3) + rx(1, "cursor:+&") + rx(2, "two")
                    + rx(3, "three") + rx(4, "four"), true));
        });
        var client = new StreamClient(builder, new ObjectMapper());
        try {
            client.connect("http://127.0.0.1/api/v1/aprs/stream?test=1");
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                var status = client.status();
                assertThat(status.state()).isEqualTo("Connected");
                assertThat(status.packets()).isEqualTo(4);
                assertThat(status.sequenceGaps()).isZero();
                assertThat(resumedQuery.get()).isEqualTo("test=1&after=cursor%3A%2B%26");
            });
        } finally {
            client.disconnect();
        }
    }

    private static ClientResponse response(String records, boolean stayOpen) {
        Flux<DataBuffer> data = Flux.just(
                DefaultDataBufferFactory.sharedInstance.wrap(records.getBytes(StandardCharsets.UTF_8)));
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_NDJSON_VALUE)
                .body(stayOpen ? data.concatWith(Flux.never()) : data)
                .build();
    }

    private static String hello(long latest) {
        return event("hello", """
                "latest_sequence":%d,"capabilities":{"features":["history_resume"]}
                """.formatted(latest));
    }

    private static String rx(long sequence, String id) {
        return event("rx", """
                "sequence":%d,"event_id":"%s","packet":{"raw_tnc2_base64":"eA=="}
                """.formatted(sequence, id));
    }

    private static String event(String type, String fields) {
        return """
                {"protocol":"lora-aprs-json","protocol_version":"1","schema_version":"1.0","boot_id":"boot","event":"%s",%s}
                """.formatted(type, fields.strip());
    }
}
