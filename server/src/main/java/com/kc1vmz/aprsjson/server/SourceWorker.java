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
import com.kc1vmz.aprsjson.server.ApplicationVersionAccessor.ApplicationVersion;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SourceWorker implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SourceWorker.class);
    private final Connection config;
    private final ApplicationVersion applicationVersion;
    private final Consumer<byte[]> receive;
    private final Thread thread;
    private Transport transport;
    private volatile boolean stopped;
    volatile String health = "CONNECTING";
    volatile long failures;

    SourceWorker(Connection config, ApplicationVersion applicationVersion, Consumer<byte[]> receive) {
        this.config = config;
        this.applicationVersion = applicationVersion;
        this.receive = receive;
        thread = Thread.ofPlatform().daemon().name("aprs-input-" + config.id()).unstarted(this::run);
    }

    void start() {
        thread.start();
    }

    private void run() {
        while (!stopped) {
            try (Transport current = config.type().serial()
                    ? new SerialTransport(config) : new SocketTransport(config, applicationVersion)) {
                synchronized (this) {
                    if (stopped) return;
                    transport = current;
                    health = "CONNECTING";
                }
                current.connect();
                health = "CONNECTED";
                while (!stopped) {
                    byte[] bytes = current.read();
                    if (bytes != null) {
                        synchronized (this) {
                            if (!stopped) receive.accept(bytes);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException | LinkageError failure) {
                if (stopped) return;
                failures++;
                health = "RECONNECTING";
                LOG.debug("Input {} reconnecting after {}", config.id(), failure.getClass().getSimpleName());
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        Transport current;
        synchronized (this) {
            stopped = true;
            current = transport;
        }
        if (current != null) current.close();
        thread.interrupt();
    }
}
