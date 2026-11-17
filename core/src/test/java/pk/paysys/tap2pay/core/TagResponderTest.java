package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

/** One test per row of the tag-route scenario matrix. */
public class TagResponderTest {
    private static final String QR = new Invoice().toPayload();
    private TagResponder r;

    @Before
    public void setUp() {
        r = new TagResponder();
        r.setQr(QR);
    }

    // Row 1
    @Test
    public void selectNdefAppWithAndWithoutLe() {
        assertEquals(0x9000, sw(r.process(hex("00A4040007D2760000850101"))));
        assertEquals(0x9000, sw(r.process(hex("00A4040007D276000085010100"))));
    }

    // Row 2
    @Test
    public void capabilityContainerAdvertisesReadOnlyNdefFile() {
        r.process(hex("00A4040007D2760000850101"));
        assertEquals(0x9000, sw(r.process(hex("00A4000C02E103"))));
        byte[] cc = r.process(hex("00B000000F"));
        assertEquals(0x9000, sw(cc));
        assertEquals("000F2000F600F60406E1040400" + "00FF", Hex.encode(data(cc)).replace(" ", ""));
    }

    // Row 3 (NDEF read the way iOS and Android do it: NLEN first, then the message in MLe-sized chunks)
    @Test
    public void ndefFileReadsBackTheQrAsOneMimeRecord() {
        byte[] msg = readNdefMessage(r);
        assertEquals(0xD2, msg[0] & 0xFF); // MB ME SR, TNF 2
        int typeLen = msg[1] & 0xFF, payloadLen = msg[2] & 0xFF;
        assertEquals(TagResponder.MIME_TYPE, new String(msg, 3, typeLen, StandardCharsets.US_ASCII));
        assertEquals(QR, new String(msg, 3 + typeLen, payloadLen, StandardCharsets.UTF_8));
        assertEquals(3 + typeLen + payloadLen, msg.length);
    }

    // Row 4
    @Test
    public void readBinaryWithoutFileSelectedIsRejected() {
        assertEquals(0x6986, sw(r.process(hex("00B0000002"))));
        r.process(hex("00A4040007D2760000850101"));
        assertEquals(0x6986, sw(r.process(hex("00B0000002"))));
    }

    // Row 5
    @Test
    public void readPastTheEndIsWrongOffset() {
        r.process(hex("00A4040007D2760000850101"));
        r.process(hex("00A4000C02E103"));
        assertEquals(0x9000, sw(r.process(hex("00B0000E01"))));
        assertEquals(0x6B00, sw(r.process(hex("00B0000F01"))));
        assertEquals(0x6B00, sw(r.process(hex("00B0010001"))));
    }

    // Row 6
    @Test
    public void unknownAidAndFileAreNotFound() {
        assertEquals(0x6A82, sw(r.process(hex("00A4040007A0000000041010"))));
        r.process(hex("00A4040007D2760000850101"));
        assertEquals(0x6A82, sw(r.process(hex("00A4000C02E105"))));
        // EF select is only valid inside the NDEF application, not the Tap2Pay one
        r.process(hex("00A4040007F0010203030605"));
        assertEquals(0x6A82, sw(r.process(hex("00A4000C02E104"))));
    }

    // Row 7
    @Test
    public void tap2payPullReturnsFciThenChunksFlaggedUntilTheLast() {
        byte[] sel = r.process(hex("00A4040007F001020303060500"));
        assertEquals(0x9000, sw(sel));
        assertArrayEquals(Apdu.FCI, data(sel));
        assertEquals(QR, new String(pull(r, 0x64), StandardCharsets.UTF_8));
    }

    @Test
    public void tap2payPullWithDefaultLeFitsInOneOrTwoChunks() {
        r.process(hex("00A4040007F0010203030605"));
        byte[] first = r.process(hex("80CB000000"));
        int expectedSw = QR.length() > TagResponder.MAX_LE ? TagResponder.SW_MORE_DATA : 0x9000;
        assertEquals(expectedSw, sw(first));
        assertEquals(Math.min(QR.length(), TagResponder.MAX_LE), data(first).length);
    }

    // Row 8
    @Test
    public void getInvoiceWithoutSelectIsConditionsNotSatisfied() {
        assertEquals(0x6985, sw(r.process(hex("80CB000000"))));
        r.process(hex("00A4040007D2760000850101"));
        assertEquals(0x6985, sw(r.process(hex("80CB000000"))));
    }

    // Row 9
    @Test
    public void idlePosAnswersNoAid() {
        r.setQr(null);
        assertEquals(0x6A82, sw(r.process(hex("00A4040007D2760000850101"))));
        assertEquals(0x6A82, sw(r.process(hex("00A4040007F0010203030605"))));
        assertEquals(0x6985, sw(r.process(hex("80CB000000"))));
        r.setQr("");
        assertNull(r.qr());
        assertEquals(0x6A82, sw(r.process(hex("00A4040007F0010203030605"))));
    }

    // Row 10 (stale state: a new invoice arrives while the phone is mid-read)
    @Test
    public void qrReplacedMidReadKeepsTheSnapshotTakenAtSelect() {
        r.process(hex("00A4040007F0010203030605"));
        byte[] first = data(r.process(hex("80CB000064")));
        r.setQr(Invoice.fresh("999.00").toPayload());
        byte[] rest = pull(r, 0x64, 0x64);
        assertEquals(QR, new String(first, StandardCharsets.UTF_8) + new String(rest, StandardCharsets.UTF_8));
    }

    // Row 11
    @Test
    public void deactivationResetsSelectionAndNextTapServesTheNewQr() {
        r.process(hex("00A4040007F0010203030605"));
        r.reset();
        assertEquals(0x6985, sw(r.process(hex("80CB000000"))));
        String next = Invoice.fresh("12.00").toPayload();
        r.setQr(next);
        r.process(hex("00A4040007F0010203030605"));
        assertEquals(next, new String(pull(r, 0x00), StandardCharsets.UTF_8));
    }

    // Row 12
    @Test
    public void shortAndUnknownApdus() {
        assertEquals(0x6700, sw(r.process(hex("00A4"))));
        assertEquals(0x6700, sw(r.process(null)));
        assertEquals(0x6700, sw(r.process(hex("00A40400"))));
        assertEquals(0x6700, sw(r.process(hex("00A4040007D27600"))));
        assertEquals(0x6D00, sw(r.process(hex("80CA030000"))));
    }

    // Row 13
    @Test
    public void longQrUsesLongRecordAndMultiChunkReads() {
        String longQr = repeat('A', 600);
        r.setQr(longQr);
        byte[] msg = readNdefMessage(r);
        assertEquals(0xC2, msg[0] & 0xFF); // MB ME, no SR, TNF 2
        int typeLen = msg[1] & 0xFF;
        int len = ((msg[2] & 0xFF) << 24) | ((msg[3] & 0xFF) << 16) | ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
        assertEquals(600, len);
        assertEquals(longQr, new String(msg, 6 + typeLen, len, StandardCharsets.UTF_8));

        r.reset();
        r.process(hex("00A4040007F0010203030605"));
        assertEquals(longQr, new String(pull(r, 0x00), StandardCharsets.UTF_8));
    }

    @Test
    public void qrThatDoesNotFitTheNdefFileIsRefusedUpFront() {
        try {
            r.setQr(repeat('A', TagResponder.MAX_NDEF_FILE));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals(QR, r.qr());
        }
    }

    private static byte[] readNdefMessage(TagResponder r) {
        assertEquals(0x9000, sw(r.process(hex("00A4040007D276000085010100"))));
        assertEquals(0x9000, sw(r.process(hex("00A4000C02E104"))));
        byte[] nlen = data(r.process(hex("00B0000002")));
        int len = ((nlen[0] & 0xFF) << 8) | (nlen[1] & 0xFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int off = 2;
        while (out.size() < len) {
            int want = Math.min(TagResponder.MAX_LE, len - out.size());
            byte[] resp = r.process(new byte[]{0, (byte) 0xB0, (byte) (off >> 8), (byte) off, (byte) want});
            assertEquals(0x9000, sw(resp));
            byte[] d = data(resp);
            out.write(d, 0, d.length);
            off += d.length;
        }
        return out.toByteArray();
    }

    /** GET INVOICE loop: expects 6310 on every chunk but the last, 9000 on the last. */
    private static byte[] pull(TagResponder r, int le) {
        return pull(r, le, 0);
    }

    private static byte[] pull(TagResponder r, int le, int startOffset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int off = startOffset;
        for (int i = 0; i < 20; i++) {
            byte[] resp = r.process(new byte[]{(byte) 0x80, (byte) 0xCB, (byte) (off >> 8), (byte) off, (byte) le});
            byte[] d = data(resp);
            out.write(d, 0, d.length);
            off += d.length;
            if (sw(resp) == 0x9000) return out.toByteArray();
            assertEquals(TagResponder.SW_MORE_DATA, sw(resp));
        }
        throw new AssertionError("no final chunk");
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private static byte[] hex(String s) {
        return Hex.decode(s);
    }

    private static int sw(byte[] resp) {
        return Apdu.swOf(resp);
    }

    private static byte[] data(byte[] resp) {
        return Arrays.copyOf(resp, resp.length - 2);
    }
}
