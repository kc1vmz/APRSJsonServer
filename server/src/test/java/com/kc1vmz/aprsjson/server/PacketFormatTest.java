package com.kc1vmz.aprsjson.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PacketFormatTest {
    @Test
    void keepsBinaryMicEAndOpaqueSuffixes() {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes("F4MLV-16>4R5WV3,WIDE1-1*,qAR,NN7LE-S:".getBytes(StandardCharsets.US_ASCII));
        bytes.writeBytes(new byte[] {0x1c, (byte) 0xff, ':'});
        Map<String, Object> packet = PacketFormat.packet(bytes.toByteArray());
        assertThat(packet).doesNotContainKey("tnc2").containsEntry("parse_status", "parsed");
        assertThat(Base64.getDecoder().decode((String) packet.get("raw_tnc2_base64")))
                .containsExactly(bytes.toByteArray());
        assertThat(packet.get("information").toString()).contains("dti_hex=1c");
        assertThat(packet.get("source").toString()).contains("suffix=16");
    }

    @Test
    void keepsKissEscapesAndRepeatedBitAcrossSharedDelimiter() throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(0xc0);
        for (int i = 0; i < 2; i++) {
            bytes.write(0);
            address(bytes, "APRS", 0x60);
            address(bytes, "N0CALL", 0x60);
            address(bytes, "WIDE1", 0xe3);
            bytes.writeBytes(new byte[] {3, (byte) 0xf0, 0x1c, (byte) 0xdb, (byte) 0xdc,
                    (byte) 0xdb, (byte) 0xdd, (byte) 0xc0});
        }
        var reader = new KissReader(new ByteArrayInputStream(bytes.toByteArray()));
        byte[] first = reader.read();
        assertThat(new String(first, StandardCharsets.ISO_8859_1)).isEqualTo("N0CALL>APRS,WIDE1-1*:\u001cÀÛ");
        assertThat(reader.read()).containsExactly(first);
    }

    private static void address(ByteArrayOutputStream bytes, String call, int flags) {
        String padded = "%-6s".formatted(call);
        padded.chars().forEach(c -> bytes.write(c << 1));
        bytes.write(flags);
    }
}
