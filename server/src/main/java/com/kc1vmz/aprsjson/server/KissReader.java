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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** KISS framing shared by TCP and serial transports. */
final class KissReader implements PacketReader {
    private final InputStream input;
    private boolean frameStarted;

    KissReader(InputStream input) {
        this.input = input;
    }

    @Override
    public byte[] read() throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        boolean escaped = false;
        while (true) {
            int b = input.read();
            if (b < 0) {
                frameStarted = false;
                throw new EOFException("Connection closed");
            }
            if (b == 0xc0) {
                // A closing delimiter also starts the next frame, including across read() calls.
                boolean complete = frameStarted && frame.size() > 0 && !escaped;
                frameStarted = true;
                if (complete) break;
                frame.reset();
                escaped = false;
                continue;
            }
            if (!frameStarted) continue;
            if (escaped) {
                if (b != 0xdc && b != 0xdd) {
                    frame.reset();
                    frameStarted = false;
                    escaped = false;
                    continue;
                }
                b = b == 0xdc ? 0xc0 : 0xdb;
                escaped = false;
            } else if (b == 0xdb) {
                escaped = true;
                continue;
            }
            frame.write(b);
            if (frame.size() > 4096) {
                frame.reset();
                frameStarted = false;
            }
        }
        return decodeFrame(frame.toByteArray());
    }

    private byte[] decodeFrame(byte[] bytes) {
        if (bytes.length < 17 || (bytes[0] & 15) != 0) return null;
        List<String> addresses = new ArrayList<>();
        int offset = 1;
        boolean last = false;
        while (!last) {
            if (offset + 7 > bytes.length || addresses.size() > 10) return null;
            StringBuilder call = new StringBuilder();
            for (int i = 0; i < 6; i++) call.append((char) ((bytes[offset + i] & 255) >> 1));
            int ssid = (bytes[offset + 6] >> 1) & 15;
            boolean repeated = addresses.size() >= 2 && (bytes[offset + 6] & 0x80) != 0;
            addresses.add(call.toString().trim() + (ssid == 0 ? "" : "-" + ssid) + (repeated ? "*" : ""));
            last = (bytes[offset + 6] & 1) != 0;
            offset += 7;
        }
        if (addresses.size() < 2
                || offset + 2 > bytes.length
                || bytes[offset] != 3
                || (bytes[offset + 1] & 255) != 0xf0) return null;
        String from = addresses.get(1);
        String path = addresses.get(0)
                + (addresses.size() > 2 ? "," + String.join(",", addresses.subList(2, addresses.size())) : "");
        byte[] header = (from + ">" + path + ":").getBytes(StandardCharsets.US_ASCII);
        byte[] packet = Arrays.copyOf(header, header.length + bytes.length - offset - 2);
        System.arraycopy(bytes, offset + 2, packet, header.length, bytes.length - offset - 2);
        return packet;
    }
}
