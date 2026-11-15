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

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/manage")
final class Management {
    private final Sources sources;
    private final ApplicationVersionAccessor applicationVersion;

    Management(Sources sources, ApplicationVersionAccessor applicationVersion) {
        this.sources = sources;
        this.applicationVersion = applicationVersion;
    }

    @GetMapping("/application-information")
    Mono<ApplicationVersionAccessor.ApplicationVersion> applicationInformation() {
        return applicationVersion.find();
    }

    @GetMapping("/status")
    Mono<Map<String, Object>> status() {
        return blocking(sources::dashboard);
    }

    @PutMapping(value = "/producer", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<Sources.ProducerSettings> producer(@RequestBody Sources.ProducerSettings settings) {
        return blocking(() -> sources.saveProducer(settings));
    }

    @GetMapping("/serial-ports")
    Mono<SerialPorts.Result> serialPorts() {
        return blocking(sources::serialPorts);
    }

    @PostMapping(value = "/sources", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<Map<String, UUID>>> create(@RequestBody Connection config) {
        return blocking(() -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", sources.save(null, config))));
    }

    @PutMapping(value = "/sources/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<Map<String, UUID>> update(@PathVariable UUID id, @RequestBody Connection config) {
        return blocking(() -> Map.of("id", sources.save(id, config)));
    }

    @PutMapping(value = "/sources/{id}/active", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<Void>> active(@PathVariable UUID id, @RequestBody Active state) {
        return blocking(() -> {
            sources.active(id, state.active());
            return ResponseEntity.noContent().build();
        });
    }

    @DeleteMapping(value = "/sources/{id}", headers = "X-APRS-Management=1")
    Mono<ResponseEntity<Void>> delete(@PathVariable UUID id) {
        return blocking(() -> {
            sources.delete(id);
            return ResponseEntity.noContent().build();
        });
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    Map<String, String> persistenceFailure() {
        return Map.of("message", "Could not save communication settings");
    }

    private static <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    record Active(boolean active) {}
}
