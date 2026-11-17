package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Synthetic QRs in the live FBL layout (template 28, 62 with 03/05/07, 84 RTP) and both POS header forms. */
final class PhoneFixtures {
    private PhoneFixtures() {}

    /** Dynamic QR, made-up values, 293 chars, so a POS must chunk it. */
    static final String DYNAMIC = "0002010102122863001926110615161500000010108FAYSPKKA0224PK00TEST0000000000000001"
            + "52041750530358654032.05802PK5913Test Merchant6007Karachi"
            + "6254031599900000000000105192611061516150000002070800009999"
            + "846300192611061516150000003013600000000-1111-2222-3333-444444444444"
            + "6304C871";

    /** Static form of {@link #DYNAMIC}: 01 = 11, no 54 / 62.05 / 84. */
    static final String STATIC = "0002010102112863001926110615161500000010108FAYSPKKA0224PK00TEST0000000000000001"
            + "5204175053035865802PK5913Test Merchant6007Karachi"
            + "62310315999000000000001070800009999"
            + "630477E3";

    static final byte[] SELECT = Apdu.SELECT;

    /** Spec form (Urovo): what {@link Apdu#pushInvoice} builds. */
    static List<byte[]> spec(String qr) {
        return Apdu.pushInvoice(qr, 0);
    }

    static List<byte[]> spec(String qr, int chunkSize) {
        return Apdu.pushInvoice(qr, chunkSize);
    }

    /** Index form (Mindcrafts Sunmi v2.7): P1 00 before the last chunk, 01 on it; P2 = 0-based index. */
    static List<byte[]> index(String qr, int chunkSize) {
        byte[] data = qr.getBytes(StandardCharsets.UTF_8);
        List<byte[]> out = new ArrayList<>();
        int n = 0;
        for (int off = 0; off < data.length; off += chunkSize, n++) {
            int len = Math.min(chunkSize, data.length - off);
            boolean last = off + len >= data.length;
            out.add(cmd(last ? 0x01 : 0x00, n, data, off, len));
        }
        return out;
    }

    static byte[] cmd(int p1, int p2, byte[] data, int off, int len) {
        byte[] a = new byte[5 + len];
        a[0] = (byte) 0x80;
        a[1] = (byte) 0xCA;
        a[2] = (byte) p1;
        a[3] = (byte) p2;
        a[4] = (byte) len;
        System.arraycopy(data, off, a, 5, len);
        return a;
    }

    static String resign(String bodyWithout63) {
        String b = bodyWithout63 + "6304";
        return b + Crc16.forPayloadPrefix(b);
    }
}
