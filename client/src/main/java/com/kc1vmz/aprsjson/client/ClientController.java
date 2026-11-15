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
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/client")
final class ClientController {
    private final StreamClient client;
    private final ApplicationVersionAccessor applicationVersion;

    ClientController(StreamClient client, ApplicationVersionAccessor applicationVersion) {
        this.client = client;
        this.applicationVersion = applicationVersion;
    }

    @GetMapping("/application-information")
    Mono<ApplicationVersionAccessor.ApplicationVersion> applicationInformation() {
        return applicationVersion.find();
    }

    @PostMapping(value = "/discover", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<JsonNode> discover(@RequestBody Endpoint endpoint) {
        return client.discover(endpoint.url())
                .onErrorMap(error -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Feed discovery failed: " + error.getMessage()));
    }

    @PostMapping(value = "/connect", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void connect(@RequestBody Endpoint endpoint) {
        client.connect(endpoint.url());
    }

    @PostMapping(value = "/disconnect", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect() {
        client.disconnect();
    }

    @GetMapping("/status")
    StreamClient.Status status() {
        return client.status();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> upstream(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of("message", error.getReason()));
    }

    record Endpoint(String url) {}
}
