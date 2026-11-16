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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpResponseStatus;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

@Component
final class PacketApi {
    private static final String ROOT = "/api/v1/aprs";
    private final Sources sources;
    private final ObjectMapper mapper;
    private final DisposableServer listener;

    PacketApi(Sources sources, ObjectMapper mapper, @Value("${aprs.api.address}") String address,
              @Value("${aprs.api.port}") int port) {
        this.sources = sources;
        this.mapper = mapper;
        // This listener has a dedicated route table and cannot dispatch to Spring management controllers.
        listener = HttpServer.create().host(address).port(port)
                .route(routes -> routes
                        .get(ROOT + "/feeds", (request, response) -> json(response, sources.publicFeeds()))
                        .get(ROOT + "/stream", (request, response) -> stream(request, response, sources.combined))
                        .get(ROOT + "/events", (request, response) -> snapshot(request, response, sources.combined))
                        .get(ROOT + "/feeds/{id}/stream", (request, response) -> stream(request, response, feed(request)))
                        .get(ROOT + "/feeds/{id}/events", (request, response) -> snapshot(request, response, feed(request))))
                .bindNow();
    }

    private Feed feed(HttpServerRequest request) {
        try {
            return sources.feed(UUID.fromString(request.param("id")));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Mono<Void> stream(HttpServerRequest request, HttpServerResponse response, Feed feed) {
        if (feed == null) return response.sendNotFound();
        String after;
        try {
            after = parameter(request, "after");
            if ("".equals(after)) return badRequest(response, "invalid_after", "after must not be empty");
        } catch (IllegalArgumentException e) {
            return badRequest(response, "invalid_after", "Invalid query encoding");
        }
        response.header("Content-Type", "application/x-ndjson; charset=utf-8")
                .header("Cache-Control", "no-store");
        return response.sendGroups(feed.stream(after).map(record ->
                Mono.fromSupplier(() -> response.alloc().buffer(record.length).writeBytes(record)))).then();
    }

    private Mono<Void> snapshot(HttpServerRequest request, HttpServerResponse response, Feed feed) {
        if (feed == null) return response.sendNotFound();
        try {
            String value = parameter(request, "limit");
            int limit = value == null ? 10 : Integer.parseInt(value);
            if (limit < 1 || limit > Feed.SNAPSHOT_LIMIT) throw new IllegalArgumentException();
            return json(response, feed.snapshot(limit));
        } catch (IllegalArgumentException e) {
            return badRequest(response, "invalid_limit", "limit must be between 1 and " + Feed.SNAPSHOT_LIMIT);
        }
    }

    private static String parameter(HttpServerRequest request, String key) {
        String query = URI.create(request.uri()).getRawQuery();
        if (query == null) return null;
        for (String entry : query.split("&")) {
            String[] pair = entry.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key))
                return pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
        }
        return null;
    }

    private Mono<Void> badRequest(HttpServerResponse response, String code, String message) {
        return json(response.status(HttpResponseStatus.BAD_REQUEST),
                Map.of("protocol", "lora-aprs-json", "protocol_version", "1", "schema_version", "1.0",
                        "event", "error", "code", code, "message", message, "recoverable", false));
    }

    private Mono<Void> json(HttpServerResponse response, Object body) {
        try {
            return response.header("Content-Type", "application/json")
                    .header("Cache-Control", "no-store")
                    .sendByteArray(Mono.just(mapper.writeValueAsBytes(body))).then();
        } catch (IOException e) {
            return response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).send().then();
        }
    }

    int port() {
        return listener.port();
    }

    @PreDestroy
    void stop() {
        listener.disposeNow();
    }
}
