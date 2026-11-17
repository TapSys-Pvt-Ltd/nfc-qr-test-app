package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;

/** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection, no final XOR) - spec Clause 3.6. */
public final class Crc16 {
    private Crc16() {}

    public static int compute(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    /** CRC over everything up to and including "6304", as 4 uppercase hex chars. */
    public static String forPayloadPrefix(String payloadIncluding6304) {
        return String.format("%04X", compute(payloadIncluding6304.getBytes(StandardCharsets.UTF_8)));
    }
}
