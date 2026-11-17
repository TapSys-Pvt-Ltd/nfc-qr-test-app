package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class SampleQrTest {
    @Test
    public void fourKindsParseAsBuilt() {
        for (boolean dynamic : new boolean[] {false, true}) {
            for (String amount : new String[] {null, "", "2.50"}) {
                String s = SampleQr.build(dynamic, amount, "R1");
                RaastQr q = RaastQr.of(s);
                assertTrue(s, q.valid);
                assertEquals(dynamic ? RaastQr.TYPE_DYNAMIC : RaastQr.TYPE_STATIC, q.type);
                boolean has = amount != null && !amount.isEmpty();
                assertEquals(has ? RaastQr.AmountState.POSITIVE : RaastQr.AmountState.NONE, q.amountState());
                if (has) assertEquals("2.50", q.amount);
                assertEquals(dynamic, q.hasRtp);
                assertEquals(dynamic, s.contains("0502R1"));
                assertEquals("Tap2Pay Test", q.merchant);
                assertEquals("999000000000001", q.mid);
                assertEquals("00009999", q.tid);
                assertEquals("PK00TEST0000000000000001", q.iban);
            }
        }
    }

    @Test
    public void referenceMakesDynamicQrsDifferAndIsIgnoredForStatic() {
        assertFalse(SampleQr.build(true, "1", "A").equals(SampleQr.build(true, "1", "B")));
        assertEquals(SampleQr.build(false, "1", "A"), SampleQr.build(false, "1", null));
    }

    @Test
    public void badInputsAreRefused() {
        String[][] bad = {{"0"}, {"0.00"}, {"-1"}, {"1,000"}, {"abc"}, {"12345678901234"}};
        for (String[] a : bad) {
            try {
                SampleQr.build(false, a[0], null);
                fail(a[0]);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        for (String ref : new String[] {null, "", "12345678901234567890123456"}) {
            try {
                SampleQr.build(true, "1", ref);
                fail(String.valueOf(ref));
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void amountStateOfFixtures() {
        assertEquals(RaastQr.AmountState.POSITIVE, RaastQr.of(PhoneFixtures.DYNAMIC).amountState());
        assertEquals(RaastQr.AmountState.NONE, RaastQr.of(PhoneFixtures.STATIC).amountState());
        assertNull(RaastQr.of(PhoneFixtures.STATIC).amount);
    }
}
