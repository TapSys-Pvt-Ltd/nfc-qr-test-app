package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RaastQrTest {

    @Test
    public void dynamicQrFields() {
        RaastQr q = RaastQr.of(PhoneFixtures.DYNAMIC);
        assertTrue(q.problem, q.valid);
        assertEquals("dynamic", q.type);
        assertEquals("2.0", q.amount);
        assertEquals("Test Merchant", q.merchant);
        assertEquals("Karachi", q.city);
        assertEquals("999000000000001", q.mid);
        assertEquals("00009999", q.tid);
        assertEquals("PK00TEST0000000000000001", q.iban);
        assertTrue(q.hasRtp);
    }

    @Test
    public void staticQrHasNoAmountOrRtp() {
        RaastQr q = RaastQr.of(PhoneFixtures.STATIC);
        assertTrue(q.valid);
        assertEquals("static", q.type);
        assertNull(q.amount);
        assertFalse(q.hasRtp);
        assertTrue(q.describe().contains("payer enters the amount"));
    }

    @Test
    public void badCrcIsRejected() {
        RaastQr q = RaastQr.of(PhoneFixtures.DYNAMIC.replace("6304C871", "6304C872"));
        assertFalse(q.valid);
        assertTrue(q.problem, q.problem.contains("should be C871"));
    }

    @Test
    public void notARaastQr() {
        assertFalse(RaastQr.of("MR:4210112345671|Amount:100").valid);
        assertFalse(RaastQr.of("").valid);
        assertFalse(RaastQr.of(null).valid);
        assertTrue(RaastQr.of("000201010212").problem.contains("6304"));
    }

    @Test
    public void lowerCaseCrcIsAcceptedAsTheNbpAppDoes() {
        String qr = PhoneFixtures.resign("000201010211" + "5802PK");
        String lower = qr.substring(0, qr.length() - 4) + qr.substring(qr.length() - 4).toLowerCase();
        assertTrue(RaastQr.of(lower).valid);
    }

    @Test
    public void brokenTlvStillGivesTheCrcVerdict() {
        String qr = PhoneFixtures.resign("0002010102125999abc");
        RaastQr q = RaastQr.of(qr);
        assertTrue(q.valid);
        assertNull(q.merchant);
    }
}
