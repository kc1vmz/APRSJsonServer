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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

final class PacketFormat {
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)?");
    private static final Pattern ALIAS = Pattern.compile("(?i)(WIDE|TRACE|RELAY)[0-9]*(?:-[0-9]+)?");

    private PacketFormat() {}

    static Map<String, Object> packet(byte[] bytes) {
        int length = bytes.length;
        while (length > 0 && (bytes[length - 1] == '\r' || bytes[length - 1] == '\n')) length--;
        byte[] raw = Arrays.copyOf(bytes, length);
        var result = new LinkedHashMap<String, Object>();
        result.put("raw_tnc2_base64", Base64.getEncoder().encodeToString(raw));
        putText(result, "tnc2", raw);
        result.put("parse_status", "malformed");

        int colon = -1;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == ':') {
                colon = i;
                break;
            }
        }
        if (colon < 0) return result;
        byte[] payload = Arrays.copyOfRange(raw, colon + 1, raw.length);
        var information = new LinkedHashMap<String, Object>();
        information.put("raw_base64", Base64.getEncoder().encodeToString(payload));
        putText(information, "text", payload);
        result.put("information", information);

        String header = new String(raw, 0, colon, StandardCharsets.US_ASCII);
        int separator = header.indexOf('>');
        if (separator < 1) return result;
        String source = header.substring(0, separator);
        String[] route = header.substring(separator + 1).split(",", -1);
        if (!ADDRESS.matcher(source).matches() || !ADDRESS.matcher(route[0]).matches()) return result;
        List<Map<String, Object>> path = new ArrayList<>();
        for (int i = 1; i < route.length; i++) {
            String text = route[i];
            boolean repeated = text.endsWith("*");
            String value = repeated ? text.substring(0, text.length() - 1) : text;
            if (!ADDRESS.matcher(value).matches()) return result;
            var hop = address(text, value);
            hop.put("repeated", repeated);
            String kind = "station";
            if (value.matches("qA[A-Za-z]")) kind = "q_construct";
            else if (value.equalsIgnoreCase("TCPIP") || value.equalsIgnoreCase("TCPXX")) kind = "internet";
            else if (ALIAS.matcher(value).matches()) kind = "alias";
            hop.put("kind", kind);
            path.add(hop);
        }
        result.put("parse_status", "parsed");
        result.put("source", address(source, source));
        result.put("destination", address(route[0], route[0]));
        result.put("path", path);
        if (payload.length > 0) {
            int dti = payload[0] & 255;
            information.put("dti_hex", HexFormat.of().toHexDigits(payload[0]));
            if ((dti >= 32 && dti <= 126) || dti == 28 || dti == 29)
                information.put("dti", String.valueOf((char) dti));
        }
        return result;
    }

    private static Map<String, Object> address(String text, String value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("text", text);
        int dash = value.indexOf('-');
        result.put("call", dash < 0 ? value : value.substring(0, dash));
        if (dash >= 0) result.put("suffix", value.substring(dash + 1));
        return result;
    }

    private static void putText(Map<String, Object> target, String key, byte[] bytes) {
        try {
            target.put(key, StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException ignored) {
            // The Base64 representation carries bytes that cannot be represented as UTF-8.
        }
    }
}
