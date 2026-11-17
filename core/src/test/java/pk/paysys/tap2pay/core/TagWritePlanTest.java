package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import pk.paysys.tap2pay.core.TagWritePlan.Action;

/** One test per row of the sticker-writer scenario matrix. */
public class TagWritePlanTest {
    // Row 1: tap without arming (and the tap after a verified write, which disarms) only inspects
    @Test
    public void notArmedNeverWrites() {
        assertEquals(Action.INSPECT_ONLY, TagWritePlan.decide(false, true, true, 888, false, 100));
        assertEquals(Action.INSPECT_ONLY, TagWritePlan.decide(false, false, false, 0, true, 100));
    }

    // Row 2: NDEF tag with room
    @Test
    public void writableNdefWithRoomIsWritten() {
        assertEquals(Action.WRITE, TagWritePlan.decide(true, true, true, 492, false, 300));
    }

    // Row 3: exact fit is allowed
    @Test
    public void exactFitIsWritten() {
        assertEquals(Action.WRITE, TagWritePlan.decide(true, true, true, 300, false, 300));
    }

    // Row 4: NTAG213-sized tag (137 B) is too small for a static QR record
    @Test
    public void tooSmallIsRefusedNotTruncated() {
        assertEquals(Action.TOO_SMALL, TagWritePlan.decide(true, true, true, 137, false, 301));
    }

    // Row 5: locked tag
    @Test
    public void readOnlyTagIsNotTouched() {
        assertEquals(Action.READ_ONLY, TagWritePlan.decide(true, true, false, 888, false, 100));
    }

    // Row 6: blank, unformatted tag
    @Test
    public void blankTagIsFormattedWithTheMessage() {
        assertEquals(Action.FORMAT_AND_WRITE, TagWritePlan.decide(true, false, false, 0, true, 100));
    }

    // Row 7: bank card / unsupported tag
    @Test
    public void bankCardIsNotWritable() {
        assertEquals(Action.NOT_WRITABLE, TagWritePlan.decide(true, false, false, 0, false, 100));
    }

    // Row 8: read-back after write (tag pulled away mid-write leaves a different or empty payload)
    @Test
    public void readBackMustMatchExactly() {
        byte[] qr = Invoice.staticQr().getBytes(StandardCharsets.UTF_8);
        assertTrue(TagWritePlan.verified(qr, qr.clone()));
        byte[] torn = java.util.Arrays.copyOf(qr, qr.length - 1);
        assertFalse(TagWritePlan.verified(qr, torn));
        assertFalse(TagWritePlan.verified(qr, null));
        assertFalse(TagWritePlan.verified(null, qr));
    }

    // Row 9: the static QR is a well-formed reusable QR (POI 11, no amount, CRC valid)
    @Test
    public void staticQrHasNoAmountAndValidCrc() {
        String qr = Invoice.staticQr();
        assertTrue(qr.startsWith("000201010211"));
        assertTrue("amount 54 must be absent: currency 53 is followed by country 58", qr.contains("53035865802PK"));
        String body = qr.substring(0, qr.length() - 4);
        assertEquals(Crc16.forPayloadPrefix(body), qr.substring(qr.length() - 4));
    }
}
