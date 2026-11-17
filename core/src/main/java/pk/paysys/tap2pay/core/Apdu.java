package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Command and response constants from spec Clauses 3.2, 3.3, 3.7 and 3.8. */
public final class Apdu {
    private Apdu() {}

    public static final byte[] AID = Hex.decode("F0010203030605");
    public static final byte[] SELECT = Hex.decode("00A4040007F001020303060500");
    /** FCI: 84 = AID, A5 { 01 = protocol version 02, 02 = capabilities 03 } (Clause 3.2). */
    public static final byte[] FCI = Hex.decode("6F118407F0010203030605A506010102020103");

    public static final int CLA_PROPRIETARY = 0x80;
    public static final int INS_PUSH_INVOICE = 0xCA;
    public static final int P1_PUSH = 0x03;
    public static final int MAX_CHUNK = 239;

    public static final int SW_OK = 0x9000;
    public static final int SW_WRONG_LENGTH = 0x6700;
    public static final int SW_CONDITIONS = 0x6985;
    public static final int SW_NOT_ALLOWED = 0x6986;
    public static final int SW_BAD_DATA = 0x6A80;
    public static final int SW_NO_MEMORY = 0x6A84;
    public static final int SW_WRONG_P1P2 = 0x6A86;
    public static final int SW_INS_NOT_SUPPORTED = 0x6D00;
    public static final int SW_UNKNOWN = 0x6F00;

    public static byte[] sw(int sw) {
        return new byte[]{(byte) (sw >> 8), (byte) sw};
    }

    public static byte[] withSw(byte[] data, int sw) {
        byte[] out = new byte[data.length + 2];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) (sw >> 8);
        out[data.length + 1] = (byte) sw;
        return out;
    }

    /** Status word from the last two bytes of a response, or -1 if too short. */
    public static int swOf(byte[] resp) {
        if (resp == null || resp.length < 2) return -1;
        return ((resp[resp.length - 2] & 0xFF) << 8) | (resp[resp.length - 1] & 0xFF);
    }

    public static String swName(int sw) {
        switch (sw) {
            case SW_OK: return "9000 OK";
            case SW_WRONG_LENGTH: return "6700 wrong length";
            case SW_CONDITIONS: return "6985 stale/duplicate/policy";
            case SW_NOT_ALLOWED: return "6986 not allowed";
            case SW_BAD_DATA: return "6A80 invalid data";
            case SW_NO_MEMORY: return "6A84 buffer exceeded";
            case SW_WRONG_P1P2: return "6A86 wrong P1/P2";
            case SW_INS_NOT_SUPPORTED: return "6D00 INS not supported";
            case -1: return "no SW";
            default: return String.format("%04X", sw);
        }
    }

    /**
     * PUSH INVOICE APDUs: one APDU with P2=00 when it fits, otherwise chunks of at most 239 bytes split at
     * top-level tag boundaries, P2 = 01, 02, ... with the final chunk flagged 0x80 (Clause 3.8).
     * {@code forceChunkSize} (0 = 239) lowers the limit for testing; an element larger than the limit is split
     * mid-element.
     */
    public static List<byte[]> pushInvoice(String payload, int forceChunkSize) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        int chunk = forceChunkSize > 0 ? Math.min(forceChunkSize, MAX_CHUNK) : MAX_CHUNK;
        List<byte[]> out = new ArrayList<>();
        if (data.length <= chunk) {
            out.add(command(0x00, data, 0, data.length));
            return out;
        }
        List<Integer> bounds = tagBoundaries(payload);
        List<int[]> pieces = new ArrayList<>();
        int off = 0;
        while (off < data.length) {
            int end = Math.min(off + chunk, data.length);
            if (end < data.length) {
                int best = -1;
                for (int b : bounds) if (b > off && b <= end) best = b;
                if (best > 0) end = best;
            }
            pieces.add(new int[]{off, end - off});
            off = end;
        }
        if (pieces.size() > 0x7F) throw new IllegalArgumentException("invoice needs more than 127 chunks");
        for (int i = 0; i < pieces.size(); i++) {
            int n = i + 1;
            boolean last = n == pieces.size();
            out.add(command(last ? (0x80 | n) : n, data, pieces.get(i)[0], pieces.get(i)[1]));
        }
        return out;
    }

    /** Byte offsets where top-level elements start (excluding 0), or none if the payload is not valid TLV. */
    private static List<Integer> tagBoundaries(String payload) {
        List<Integer> out = new ArrayList<>();
        try {
            for (Tlv.Element e : Tlv.parseElements(payload, java.util.Collections.<String>emptySet())) {
                if (e.offset > 0) out.add(e.offset);
            }
        } catch (IllegalArgumentException ignored) {
            // malformed payload (negative tests): fall back to fixed-size chunks
        }
        return out;
    }

    private static byte[] command(int p2, byte[] data, int off, int len) {
        byte[] apdu = new byte[5 + len];
        apdu[0] = (byte) CLA_PROPRIETARY;
        apdu[1] = (byte) INS_PUSH_INVOICE;
        apdu[2] = (byte) P1_PUSH;
        apdu[3] = (byte) p2;
        apdu[4] = (byte) len;
        System.arraycopy(data, off, apdu, 5, len);
        return apdu;
    }
}
