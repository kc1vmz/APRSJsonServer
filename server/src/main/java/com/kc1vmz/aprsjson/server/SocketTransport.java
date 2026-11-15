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

import com.kc1vmz.aprsjson.server.ApplicationVersionAccessor.ApplicationVersion;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// Receive path adapted from APRSWelcomeCenter APRS-IS, KISS TCP and TNC2 TCP accessors.
final class SocketTransport implements Transport {
    private final Connection config;
    private final ApplicationVersion applicationVersion;
    private final Socket socket = new Socket();
    private PacketReader reader;

    SocketTransport(Connection config, ApplicationVersion applicationVersion) {
        this.config = config;
        this.applicationVersion = applicationVersion;
    }

    @Override
    public void connect() throws IOException {
        socket.connect(new InetSocketAddress(config.host(), config.port()), 5000);
        var input = new BufferedInputStream(socket.getInputStream());
        if (config.type() == Connection.Type.KISS_TCP) {
            reader = new KissReader(input);
        } else {
            reader = new LineReader(input);
        }
        if (config.type() == Connection.Type.APRS_IS) {
            String softwareName = applicationVersion.applicationName().replaceAll("\\s+", "");
            String softwareVersion = applicationVersion.version().replaceAll("\\s+", "");
            String login = "user " + config.username() + " pass " + config.passcode()
                    + " vers " + softwareName + " " + softwareVersion;
            if (config.filter() != null && !config.filter().isBlank()) login += " filter " + config.filter();
            var output = socket.getOutputStream();
            output.write((login + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
    }

    @Override
    public byte[] read() throws IOException {
        byte[] packet = reader.read();
        if (packet != null && packet.length > 0 && packet[0] == '#'
                && config.type() == Connection.Type.APRS_IS) return null;
        return packet;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing the socket also unblocks the reader.
        }
    }
}
