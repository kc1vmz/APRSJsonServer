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

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// Serial setup and initialization adapted from APRSWelcomeCenter.
final class SerialTransport implements Transport {
    private final Connection config;
    private SerialPort serial;
    private PacketReader reader;
    private KenwoodSession kenwood;
    private boolean closed;

    SerialTransport(Connection config) {
        this.config = config;
    }

    @Override
    public void connect() throws IOException, InterruptedException {
        OutputStream output;
        synchronized (this) {
            if (closed) throw new IOException("Stopped");
            serial = SerialPort.getCommPort(config.serialDevice());
            serial.setComPortParameters(config.baudRate(), 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            serial.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            boolean commandMode = config.type() == Connection.Type.KENWOOD_SERIAL;
            serial.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                    commandMode ? 1000 : 0, 5000);
            if (!serial.openPort()) throw new IOException("Cannot open serial port");
            var input = serial.getInputStream();
            output = serial.getOutputStream();
            switch (config.type()) {
                case KISS_SERIAL -> reader = new KissReader(input);
                case TNC2_SERIAL -> reader = new LineReader(input);
                case KENWOOD_SERIAL -> {
                    kenwood = new KenwoodSession(input, output, config);
                    reader = kenwood::read;
                }
                default -> throw new IllegalStateException("Unexpected serial type");
            }
        }
        if (kenwood != null) {
            kenwood.initialize();
            return;
        }
        for (String command : new String[] {config.initCommand1(), config.initCommand2()}) {
            if (command != null && !command.isBlank()) {
                output.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                Thread.sleep(2000);
            }
        }
    }

    @Override
    public byte[] read() throws IOException {
        return reader.read();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (kenwood != null) kenwood.close();
        if (serial != null) serial.closePort();
    }
}
