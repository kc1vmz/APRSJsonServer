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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kc1vmz.aprsjson.server.ApplicationVersionAccessor.ApplicationVersion;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
final class Sources {
    private final Map<UUID, Source> sources = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final ApplicationVersion applicationVersion;
    private final Path file;
    private final Path producerFile;
    private volatile String station;
    private final long bootTime = System.nanoTime();
    final Feed combined;

    Sources(ObjectMapper mapper, @Value("${aprs.config-file}") Path file,
            @Value("${aprs.station}") String station, ApplicationVersionAccessor versionAccessor) throws IOException {
        this.mapper = mapper;
        this.applicationVersion = versionAccessor.find().block();
        this.file = file.toAbsolutePath();
        producerFile = this.file.resolveSibling(this.file.getFileName() + ".producer.json");
        this.station = validateStation(Files.exists(producerFile)
                ? mapper.readValue(producerFile.toFile(), ProducerSettings.class).station() : station);
        combined = new Feed(mapper, () -> this.station, bootTime);
        if (Files.exists(this.file)) {
            List<Connection> saved = mapper.readValue(this.file.toFile(), new TypeReference<>() {});
            boolean migrate = false;
            for (Connection item : saved) {
                if (item.id() == null || sources.containsKey(item.id()))
                    throw new IllegalArgumentException("Invalid or duplicate source ID");
                Connection config = item.validated(item.id(), null, this.station);
                migrate |= item.producerStation() == null;
                checkSerial(config);
                sources.put(config.id(), new Source(config));
            }
            if (migrate) writeSettings(this.file, sources.values().stream().map(source -> source.config).toList());
        }
        sources.values().forEach(Source::start);
    }

    synchronized List<PublicFeed> publicFeeds() {
        var feeds = new ArrayList<PublicFeed>();
        feeds.add(new PublicFeed("all", "All sources", "COMBINED", true, "AVAILABLE", "/api/v1/aprs/stream", station));
        for (Source source : sources.values()) {
            var config = source.config;
            String id = config.id().toString();
            feeds.add(new PublicFeed(id, config.label(), config.type().name(), config.active(), source.health(),
                    "/api/v1/aprs/feeds/" + id + "/stream", config.producerStation()));
        }
        return feeds;
    }

    synchronized Feed feed(UUID id) {
        Source source = sources.get(id);
        return source == null ? null : source.feed;
    }

    synchronized Map<String, Object> dashboard() {
        return Map.of("producerStation", station,
                "sources", sources.values().stream().map(Source::status).toList(),
                "combinedSubscribers", combined.subscribers(),
                "activeSubscribers", combined.subscribers() + sources.values().stream()
                        .mapToInt(source -> source.feed.subscribers()).sum(),
                "slowClientDisconnects", combined.slowClients() + sources.values().stream()
                        .mapToLong(source -> source.feed.slowClients()).sum());
    }

    synchronized SerialPorts.Result serialPorts() {
        return SerialPorts.discover(sources.values().stream().map(source -> source.config)
                .filter(config -> config.type().serial())
                .map(config -> new SerialPorts.Assignment(config.serialDevice(), config.label(), config.active()))
                .toList());
    }

    synchronized UUID save(UUID id, Connection proposed) throws IOException {
        Source existing = id == null ? null : required(id);
        UUID key = id == null ? UUID.randomUUID() : id;
        Connection config = proposed.validated(key, existing == null ? null : existing.config, null);
        checkSerial(config);
        persist(key, config);
        if (existing == null) {
            existing = new Source(config);
            sources.put(key, existing);
        } else {
            existing.stop();
            if (!existing.config.producerStation().equals(config.producerStation())) {
                existing.feed.close();
                existing.feed = sourceFeed(config);
            }
            existing.config = config;
        }
        existing.start();
        return key;
    }

    synchronized void active(UUID id, boolean active) throws IOException {
        Source source = required(id);
        if (source.config.active() != active) save(id, source.config.withActive(active));
    }

    synchronized void delete(UUID id) throws IOException {
        Source source = required(id);
        persist(id, null);
        source.stop();
        source.feed.close();
        sources.remove(id);
    }

    private Source required(UUID id) {
        Source source = sources.get(id);
        if (source == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        return source;
    }

    private void checkSerial(Connection config) {
        if (!config.active() || !config.type().serial()) return;
        String device = SerialDeviceIdentity.identity(config.serialDevice());
        for (Source source : sources.values()) {
            Connection other = source.config;
            if (!other.id().equals(config.id()) && other.active() && other.type().serial()
                    && SerialDeviceIdentity.identity(other.serialDevice()).equals(device))
                throw new IllegalArgumentException("An active source already owns this serial device");
        }
    }

    private void persist(UUID id, Connection replacement) throws IOException {
        var next = new LinkedHashMap<UUID, Connection>();
        sources.forEach((key, source) -> next.put(key, source.config));
        if (replacement == null) next.remove(id);
        else next.put(id, replacement);
        writeSettings(file, next.values());
    }

    synchronized ProducerSettings saveProducer(ProducerSettings settings) throws IOException {
        var saved = new ProducerSettings(validateStation(settings.station()));
        writeSettings(producerFile, saved);
        station = saved.station();
        return saved;
    }

    private static String validateStation(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 80)
            throw new IllegalArgumentException("Producer station must be between 1 and 80 characters");
        return value.strip();
    }

    private void writeSettings(Path destination, Object settings) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "aprs-settings-", ".json");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), settings);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Scheduled(fixedDelay = 1000)
    synchronized void heartbeats() {
        combined.heartbeat();
        sources.values().forEach(source -> source.feed.heartbeat());
    }

    @PreDestroy
    synchronized void stop() {
        sources.values().forEach(source -> {
            source.stop();
            source.feed.close();
        });
        combined.close();
    }

    record ProducerSettings(String station) {}

    record PublicFeed(String id, String label, String type, boolean active, String health, String streamUrl,
                      String producerStation) {}

    private Feed sourceFeed(Connection config) {
        return new Feed(mapper, config::producerStation, bootTime);
    }

    private final class Source {
        Connection config;
        SourceWorker worker;
        Feed feed;
        private long received;
        private long rejected;
        private long failures;
        private Instant lastPacket;
        private final long[] minuteBuckets = new long[60];
        private long bucketSecond = System.nanoTime() / 1_000_000_000;

        Source(Connection config) {
            this.config = config;
            feed = sourceFeed(config);
        }

        void start() {
            if (config.active()) {
                Connection input = config;
                worker = new SourceWorker(input, applicationVersion, bytes -> accept(input, bytes));
                worker.start();
            }
        }

        void stop() {
            if (worker == null) return;
            worker.close();
            failures += worker.failures;
            worker = null;
        }

        String health() {
            return worker == null ? "PAUSED" : worker.health;
        }

        synchronized void accept(Connection input, byte[] bytes) {
            if (bytes.length == 0 || bytes.length > LineReader.MAX_PACKET_BYTES) {
                rejected++;
                return;
            }
            Instant now = Instant.now();
            var packet = PacketFormat.packet(bytes);
            String receiver = input.producerStation();
            try {
                combined.publish(input.id(), receiver, now, packet);
                feed.publish(input.id(), receiver, now, packet);
            } catch (IllegalArgumentException e) {
                rejected++;
                return;
            }
            received++;
            lastPacket = now;
            minuteBuckets[advanceBuckets()]++;
        }

        private int advanceBuckets() {
            long now = System.nanoTime() / 1_000_000_000;
            for (long second = Math.max(bucketSecond + 1, now - 59); second <= now; second++)
                minuteBuckets[Math.floorMod(second, 60)] = 0;
            bucketSecond = now;
            return Math.floorMod(now, 60);
        }

        synchronized Map<String, Object> status() {
            advanceBuckets();
            return Map.of("config", config.redacted(), "health", health(), "received", received,
                    "rejected", rejected, "reconnects", failures + (worker == null ? 0 : worker.failures),
                    "lastPacket", lastPacket == null ? "" : lastPacket.toString(),
                    "packetsLastMinute", Arrays.stream(minuteBuckets).sum(), "subscribers", feed.subscribers());
        }
    }
}
