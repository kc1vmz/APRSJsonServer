/*
 *
 * APRS JSON Client
 * Copyright (c) 2026 John Rokicki KC1VMZ
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * https://www.gnu.org/licenses/.
 *
 * https://www.kc1vmz.com
 */

package com.kc1vmz.aprsjson.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
final class StreamClient {
    private static final MediaType NDJSON = MediaType.APPLICATION_NDJSON;
    private final WebClient web;
    private final ObjectMapper mapper;
    private final ArrayDeque<Observation> recent = new ArrayDeque<>();
    private final LinkedHashMap<URI, ResumeCursor> cursors = new LinkedHashMap<>();

    private Disposable subscription;
    private long generation;
    private String state = "Disconnected";
    private String bootId;
    private JsonNode hello;
    private Instant lastHeartbeat;
    private long latestSequence;
    private long packets;
    private long sequenceGaps;
    private long gapEvents;
    private ResumeCursor requestedCursor;
    private boolean awaitingReplayStart;
    private boolean resumeSupported;

    StreamClient(WebClient.Builder builder, ObjectMapper mapper) {
        this.web = builder.build();
        this.mapper = mapper;
    }

    Mono<JsonNode> discover(String url) {
        URI base = httpUri(url);
        URI directory = base.resolve("/api/v1/aprs/feeds");
        return web.get().uri(directory).accept(MediaType.APPLICATION_JSON).retrieve()
                .bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(5))
                .map(feeds -> {
                    if (!feeds.isArray()) throw new IllegalArgumentException("Invalid feed directory");
                    return feeds;
                });
    }

    synchronized void connect(String url) {
        URI uri = httpUri(url);
        disconnect();
        long current = generation;
        recent.clear();
        bootId = null;
        hello = null;
        lastHeartbeat = null;
        latestSequence = packets = sequenceGaps = gapEvents = 0;
        state = "Connecting";
        subscription = Flux.defer(() -> open(current, uri))
                .retryWhen(Retry.fixedDelay(5, Duration.ofSeconds(2))
                        .filter(failure -> failure instanceof IOException || failure instanceof TimeoutException
                                || failure instanceof WebClientRequestException || failure instanceof SequenceJump)
                        .doBeforeRetry(signal -> finish(current, "Connecting"))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .subscribe(line -> {}, failure -> finish(current, "Stream error: " + failure.getMessage()));
    }

    private synchronized Flux<String> open(long current, URI uri) {
        if (current != generation) return Flux.empty();
        hello = null;
        lastHeartbeat = null;
        requestedCursor = cursors.get(uri);
        var target = UriComponentsBuilder.fromUri(uri);
        if (requestedCursor != null) {
            target.replaceQueryParam("after", UriUtils.encode(requestedCursor.id(), StandardCharsets.UTF_8));
        }
        awaitingReplayStart = target.build(true).getQueryParams().containsKey("after");
        return web.get().uri(target.build(true).toUri()).accept(NDJSON)
                .exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful())
                        return response.createException().flatMapMany(Flux::error);
                    if (response.headers().contentType().filter(NDJSON::isCompatibleWith).isEmpty())
                        return Flux.error(new IllegalArgumentException("Expected an NDJSON response"));
                    return response.bodyToFlux(String.class);
                })
                .timeout(Duration.ofSeconds(45))
                .doOnNext(line -> accept(current, uri, line))
                .concatWith(Mono.error(new EOFException("Stream ended")));
    }

    private synchronized void accept(long current, URI uri, String line) {
        if (current != generation) return;
        JsonNode event;
        try {
            event = mapper.readTree(line);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON record", e);
        }
        if (event == null || !event.isObject() || !"lora-aprs-json".equals(event.path("protocol").asText())
                || !"1".equals(event.path("protocol_version").asText())
                || !"1.0".equals(event.path("schema_version").asText()))
            throw new IllegalArgumentException("Unsupported protocol or schema");
        String type = event.path("event").asText();
        if (hello == null && !type.equals("hello")) throw new IllegalArgumentException("Expected hello first");
        switch (type) {
            case "hello" -> {
                if (hello != null) throw new IllegalArgumentException("Unexpected second hello");
                bootId = text(event, "boot_id");
                latestSequence = cursor(event, "latest_sequence");
                if (requestedCursor != null && bootId.equals(requestedCursor.bootId()))
                    latestSequence = requestedCursor.sequence();
                resumeSupported = false;
                for (JsonNode feature : event.path("capabilities").path("features"))
                    if ("history_resume".equals(feature.asText())) resumeSupported = true;
                if (!resumeSupported || (requestedCursor != null && !bootId.equals(requestedCursor.bootId())))
                    cursors.remove(uri);
                hello = event;
                state = "Connected";
            }
            case "heartbeat" -> {
                checkBoot(event);
                lastHeartbeat = Instant.now();
            }
            case "gap" -> {
                checkBoot(event);
                gapEvents++;
                cursors.remove(uri);
                requestedCursor = null;
                awaitingReplayStart = false;
                latestSequence = cursor(hello, "latest_sequence");
                append(new Observation(type, "", text(event, "reason"), event, null));
            }
            case "rx" -> {
                checkBoot(event);
                long sequence = cursor(event, "sequence");
                if (sequence < 1) throw new IllegalArgumentException("Invalid reception sequence");
                String id = text(event, "event_id");
                ResumeCursor last = cursors.get(uri);
                if (last != null && bootId.equals(last.bootId()) && id.equals(last.id())) return;
                if (awaitingReplayStart && requestedCursor == null && sequence <= latestSequence)
                    latestSequence = sequence - 1;
                awaitingReplayStart = false;
                if (sequence != latestSequence + 1) {
                    sequenceGaps++;
                    if (resumeSupported && last != null) throw new SequenceJump();
                }
                var packet = event.path("packet");
                String encoded = packet.path("raw_tnc2_base64").asText(null);
                if (encoded == null) throw new IllegalArgumentException("Missing raw packet");
                byte[] raw = Base64.getDecoder().decode(encoded);
                if (!Base64.getEncoder().encodeToString(raw).equals(encoded))
                    throw new IllegalArgumentException("Non-canonical raw packet Base64");
                JsonNode display = packet.path("tnc2");
                if (!display.isMissingNode() && (!display.isTextual()
                        || !Arrays.equals(raw, display.asText().getBytes(StandardCharsets.UTF_8))))
                    throw new IllegalArgumentException("TNC2 text does not match raw packet");
                if (event.has("created_at")) Instant.parse(text(event, "created_at"));
                latestSequence = sequence;
                if (resumeSupported) {
                    cursors.remove(uri);
                    cursors.put(uri, new ResumeCursor(id, bootId, sequence));
                    if (cursors.size() > 32) cursors.pollFirstEntry();
                }
                packets++;
                append(new Observation(type, id, display.asText(new String(raw, StandardCharsets.ISO_8859_1)),
                        event, HexFormat.ofDelimiter(" ").formatHex(raw)));
            }
            case "error" -> append(new Observation(type, "", event.path("message").asText(), event, null));
            default -> throw new IllegalArgumentException("Unknown protocol 1.0 event: " + type);
        }
    }

    private void append(Observation observation) {
        recent.addFirst(observation);
        if (recent.size() > 50) recent.removeLast();
    }

    private void checkBoot(JsonNode event) {
        if (!bootId.equals(text(event, "boot_id"))) throw new IllegalArgumentException("Producer boot changed");
    }

    private static long cursor(JsonNode event, String field) {
        JsonNode value = event.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalArgumentException("Invalid " + field);
        return value.longValue();
    }

    private static String text(JsonNode event, String field) {
        JsonNode value = event.path(field);
        if (!value.isTextual() || value.asText().isEmpty())
            throw new IllegalArgumentException("Invalid " + field);
        return value.asText();
    }

    private synchronized void finish(long current, String message) {
        if (current == generation) state = message;
    }

    @PreDestroy
    synchronized void disconnect() {
        generation++;
        if (subscription != null) subscription.dispose();
        subscription = null;
        state = "Disconnected";
    }

    synchronized Status status() {
        return new Status(state, hello, lastHeartbeat, packets, sequenceGaps, gapEvents, List.copyOf(recent));
    }

    private static URI httpUri(String url) {
        if (url == null) throw new IllegalArgumentException("A server URL is required");
        URI uri = URI.create(url.strip());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Enter an HTTP or HTTPS URL without credentials");
        return uri;
    }

    record Observation(String type, String id, String text, JsonNode json, String hex) {}
    private record ResumeCursor(String id, String bootId, long sequence) {}

    private static final class SequenceJump extends RuntimeException {
        SequenceJump() {
            super("Reception sequence jumped before recovery");
        }
    }
    record Status(String state, JsonNode hello, Instant lastHeartbeat, long packets, long sequenceGaps,
                  long gapEvents, List<Observation> recent) {}
}
