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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

final class LineReader implements PacketReader {
    static final int MAX_PACKET_BYTES = 4096;
    private final InputStream input;

    LineReader(InputStream input) {
        this.input = input;
    }

    @Override
    public byte[] read() throws IOException {
        var line = new ByteArrayOutputStream();
        boolean oversized = false;
        while (true) {
            int next = input.read();
            if (next < 0) throw new EOFException("Connection closed");
            if (next == '\r' || next == '\n') {
                if (oversized) return new byte[0];
                if (line.size() > 0) return line.toByteArray();
                continue;
            }
            if (line.size() == MAX_PACKET_BYTES) oversized = true;
            if (!oversized) line.write(next);
        }
    }
}
