/*
 *
 * APRS JSON Server
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

package com.kc1vmz.aprsjson.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

final class Feed {
    static final int MAX_RECORD_BYTES = 65536;
    static final int SNAPSHOT_LIMIT = 32;
    static final int DEFAULT_HISTORY_EVENTS = 32;
    private static final long HEARTBEAT_INTERVAL = TimeUnit.SECONDS.toNanos(15);
    private final String bootId = UUID.randomUUID().toString();
    private final Supplier<String> station;
    private final long bootTime;
    private final ObjectMapper mapper;
    private final List<Subscriber> clients = new ArrayList<>();
    private final String feedId;
    private int historyDepth;
    private final ArrayDeque<Reception> recent = new ArrayDeque<>();
    private long sequence;
    private long disconnectedSlowClients;
    private boolean closed;

    Feed(ObjectMapper mapper, Supplier<String> station, long bootTime, String feedId, int historyDepth) {
        this.mapper = mapper;
        this.station = station;
        this.bootTime = bootTime;
        this.feedId = feedId;
        this.historyDepth = historyDepth;
    }

    synchronized void publish(UUID source, String receiver, Instant received, Map<String, Object> packet) {
        if (closed) return;
        Map<String, Object> event = control("rx");
        event.put("event_id", feedId + ":" + bootId + ":" + (sequence + 1));
        event.put("sequence", sequence + 1);
        event.put("created_at", received.toString());
        event.put("receiver", Map.of("station", receiver, "interface", source.toString()));
        event.put("packet", packet);
        event.put("reception", Map.of());
        byte[] record = encode(event);
        sequence++;
        recent.addFirst(new Reception(event, record));
        if (recent.size() > historyDepth) recent.removeLast();
        for (Subscriber subscriber : List.copyOf(clients)) send(subscriber, record);
    }

    Flux<byte[]> stream() {
        return stream(null);
    }

    Flux<byte[]> stream(String after) {
        return Flux.defer(() -> {
            Subscriber subscriber;
            var initial = new ArrayList<byte[]>();
            synchronized (this) {
                if (closed) return Flux.empty();
                subscriber = new Subscriber();
                var hello = control("hello");
                hello.put("latest_sequence", sequence);
                hello.put("producer", Map.of("station", station.get(), "software", "APRSJsonServer"));
                hello.put("capabilities", Map.of("events", List.of("rx", "heartbeat", "gap", "error"),
                        "features", List.of("history_resume"), "transports", List.of("ndjson-http"),
                        "history_events", historyDepth, "max_record_bytes", MAX_RECORD_BYTES));
                initial.add(encode(hello));
                if (after != null) prepareReplay(after, initial);
                subscriber.lastSent = System.nanoTime();
                clients.add(subscriber);
            }
            var records = Flux.concat(Flux.fromIterable(initial), subscriber.records.asFlux());
            return Flux.merge(records, subscriber.overflow.asMono())
                    .doFinally(signal -> unsubscribe(subscriber));
        });
    }

    private void prepareReplay(String after, List<byte[]> initial) {
        String reason = "unknown_event";
        String[] parts = after.split(":", 3);
        if (parts.length == 3 && feedId.equals(parts[0])) {
            try {
                UUID cursorBoot = UUID.fromString(parts[1]);
                long cursor = Long.parseLong(parts[2]);
                if (cursor > 0 && cursorBoot.toString().equals(parts[1]) && Long.toString(cursor).equals(parts[2])) {
                    if (!bootId.equals(parts[1])) {
                        reason = "different_boot";
                    } else if (cursor <= sequence) {
                        if (cursor <= sequence - recent.size()) {
                            reason = "history_expired";
                        } else {
                            var oldestFirst = recent.descendingIterator();
                            while (oldestFirst.hasNext()) {
                                Reception entry = oldestFirst.next();
                                if ((long) entry.event().get("sequence") > cursor) initial.add(entry.record());
                            }
                            return;
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // External callers may provide any opaque cursor.
            }
        }
        var gap = control("gap");
        gap.put("requested_after", after);
        gap.put("reason", reason);
        if (!recent.isEmpty()) {
            gap.put("oldest_available", recent.getLast().event().get("event_id"));
            gap.put("latest_available", recent.getFirst().event().get("event_id"));
        }
        initial.add(encode(gap));
    }

    synchronized void heartbeat() {
        long now = System.nanoTime();
        byte[] record = null;
        for (Subscriber subscriber : List.copyOf(clients)) {
            if (now - subscriber.lastSent < HEARTBEAT_INTERVAL) continue;
            if (record == null) {
                var heartbeat = control("heartbeat");
                heartbeat.put("latest_sequence", sequence);
                heartbeat.put("time_synchronized", true);
                record = encode(heartbeat);
            }
            send(subscriber, record);
        }
    }

    private synchronized void unsubscribe(Subscriber subscriber) {
        clients.remove(subscriber);
    }

    private void send(Subscriber subscriber, byte[] record) {
        var result = subscriber.records.tryEmitNext(record);
        if (result == Sinks.EmitResult.OK) {
            subscriber.lastSent = System.nanoTime();
        } else {
            clients.remove(subscriber);
            if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                disconnectedSlowClients++;
                subscriber.overflow.tryEmitError(new IllegalStateException("Slow APRS subscriber"));
            }
        }
    }

    synchronized List<Map<String, Object>> snapshot(int limit) {
        return recent.stream().limit(limit).map(Reception::event).toList();
    }

    synchronized int historyDepth() {
        return historyDepth;
    }

    synchronized void resizeHistory(int depth) {
        historyDepth = depth;
        while (recent.size() > depth) recent.removeLast();
    }

    static int validateHistoryDepth(int depth) {
        if (depth < 1 || depth > 4096)
            throw new IllegalArgumentException("History size must be between 1 and 4096 receptions");
        return depth;
    }

    synchronized int subscribers() {
        return clients.size();
    }

    synchronized long slowClients() {
        return disconnectedSlowClients;
    }

    synchronized void close() {
        closed = true;
        for (Subscriber subscriber : List.copyOf(clients)) {
            subscriber.overflow.tryEmitEmpty();
            subscriber.records.tryEmitComplete();
        }
        clients.clear();
    }

    private Map<String, Object> control(String type) {
        var event = new LinkedHashMap<String, Object>();
        event.put("protocol", "lora-aprs-json");
        event.put("protocol_version", "1");
        event.put("schema_version", "1.0");
        event.put("event", type);
        event.put("boot_id", bootId);
        event.put("uptime_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bootTime));
        return event;
    }

    private byte[] encode(Map<String, Object> event) {
        try {
            byte[] json = mapper.writeValueAsBytes(event);
            if (json.length > MAX_RECORD_BYTES) throw new IllegalArgumentException("NDJSON record too large");
            byte[] record = Arrays.copyOf(json, json.length + 1);
            record[json.length] = '\n';
            return record;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode APRS event", e);
        }
    }

    private static final class Subscriber {
        final Sinks.Many<byte[]> records = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(64));
        final Sinks.One<byte[]> overflow = Sinks.one();
        long lastSent;
    }

    private record Reception(Map<String, Object> event, byte[] record) {}
}
