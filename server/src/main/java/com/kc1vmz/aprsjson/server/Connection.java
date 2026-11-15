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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
record Connection(UUID id, String label, Type type, boolean active, String host, Integer port,
                  String username, String passcode, String filter, String serialDevice,
                  Integer baudRate, String initCommand1, String initCommand2, String producerStation) {
    enum Type {
        APRS_IS, KISS_TCP, KISS_SERIAL, TNC2_TCP, TNC2_SERIAL, KENWOOD_SERIAL;

        boolean serial() {
            return this == KISS_SERIAL || this == TNC2_SERIAL || this == KENWOOD_SERIAL;
        }
    }

    Connection validated(UUID id, Connection previous, String legacyStation) {
        if (type == null || label == null || label.isBlank() || label.length() > 80)
            throw new IllegalArgumentException("A source type and label of up to 80 characters are required");
        if (type.serial()) {
            if (serialDevice == null || serialDevice.isBlank() || baudRate == null || baudRate < 1)
                throw new IllegalArgumentException("Serial device and positive baud rate are required");
        } else if (host == null || host.isBlank() || port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Host and port (1–65535) are required");
        }
        if ((type == Type.APRS_IS || type == Type.KENWOOD_SERIAL)
                && (username == null || username.length() > 80
                || !username.matches("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)?")))
            throw new IllegalArgumentException("A callsign is required");
        if (type == Type.KENWOOD_SERIAL) KenwoodSession.validateAddress(username);
        String password = passcode;
        if (password == null || password.isBlank()) {
            password = previous != null && previous.type == Type.APRS_IS ? previous.passcode : "-1";
        }
        if (type == Type.APRS_IS && (!password.matches("-?[0-9]+")
                || (filter != null && !filter.matches("[ -~]*"))))
            throw new IllegalArgumentException("Invalid APRS-IS login or filter");
        for (String command : new String[] {initCommand1, initCommand2}) {
            if (command != null && !command.matches("[ -~]*"))
                throw new IllegalArgumentException("Initialization commands must be printable ASCII");
        }
        String station = producerStation;
        if (type == Type.APRS_IS) station = username;
        else if (station == null && previous != null && previous.type == type) station = previous.producerStation;
        else if (station == null && type == Type.KENWOOD_SERIAL) station = username;
        else if (station == null) station = legacyStation;
        if (station == null || station.isBlank() || station.strip().length() > 80)
            throw new IllegalArgumentException("Producer station must be between 1 and 80 characters");
        return new Connection(id, label.strip(), type, active, type.serial() ? null : host.strip(),
                type.serial() ? null : port, type == Type.APRS_IS || type == Type.KENWOOD_SERIAL ? username : null,
                type == Type.APRS_IS ? password : null, type == Type.APRS_IS ? filter : null,
                type.serial() ? serialDevice.strip() : null, type.serial() ? baudRate : null,
                initCommand1, initCommand2, station.strip());
    }

    Connection withActive(boolean enabled) {
        return new Connection(id, label, type, enabled, host, port, username, passcode, filter,
                serialDevice, baudRate, initCommand1, initCommand2, producerStation);
    }

    Connection redacted() {
        return new Connection(id, label, type, active, host, port, username, null, filter,
                serialDevice, baudRate, initCommand1, initCommand2, producerStation);
    }

    @Override
    public String toString() {
        return "Connection[" + id + ", " + type + "]";
    }
}
