package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** One test per row of the scenario matrix in phone-test/README.md (rows named in each test). */
public class PhoneResponderTest {
    private long now = 10_000L;
    private PhoneCase current = PhoneCase.ACCEPT;
    private final List<TapRecord> closed = new ArrayList<>();
    private int changes;
    private PhoneResponder r;

    @Before
    public void setUp() {
        r = new PhoneResponder(new PhoneResponder.Clock() {
            @Override
            public long monotonicMs() {
                return now;
            }

            @Override
            public long wallMs() {
                return 1_700_000_000_000L + now;
            }
        }, () -> current, new PhoneResponder.Listener() {
            @Override
            public void onTapChanged(TapRecord tap) {
                changes++;
            }

            @Override
            public void onTapClosed(TapRecord tap) {
                closed.add(tap);
            }
        });
    }

    private byte[] send(byte[] apdu) {
        now += 5;
        return r.onApdu(apdu);
    }

    private static int sw(byte[] response) {
        return Apdu.swOf(response);
    }

    /** SELECT then every APDU; returns the SW of the last one. */
    private int tap(List<byte[]> pushes) {
        assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
        int last = -1;
        for (byte[] p : pushes) last = sw(send(p));
        return last;
    }

    private TapRecord end() {
        now += 20;
        return r.onDeactivated(true);
    }

    // Row 1
    @Test
    public void acceptSpecOneApdu() {
        assertEquals(Apdu.SW_OK, tap(PhoneFixtures.spec(PhoneFixtures.STATIC)));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.PASS, t.verdict());
        assertEquals(PushAssembler.Form.SPEC, t.form());
        assertEquals(1, t.chunks);
        assertEquals(4, t.exchanges().size());
        assertTrue(t.describe(false).contains("Urovo app form"));
    }

    // Row 2
    @Test
    public void acceptSpecChunked() {
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC);
        assertEquals(Apdu.SW_OK, tap(pushes));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.PASS, t.verdict());
        assertEquals(2, t.chunks);
        assertEquals("dynamic", t.qr().type);
        assertEquals("2.0", t.qr().amount);
    }

    // Row 3
    @Test
    public void acceptIndexOneApdu() {
        assertEquals(Apdu.SW_OK, tap(PhoneFixtures.index(PhoneFixtures.STATIC, 239)));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.PASS, t.verdict());
        assertEquals(PushAssembler.Form.INDEX, t.form());
        assertTrue(t.describe(false).contains("Sunmi Mindcrafts form"));
    }

    // Row 4
    @Test
    public void acceptIndexChunked() {
        List<byte[]> pushes = PhoneFixtures.index(PhoneFixtures.DYNAMIC, 120);
        for (int i = 0; i < pushes.size(); i++) {
            if (i == 0) assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
            assertEquals(Apdu.SW_OK, sw(send(pushes.get(i))));
        }
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.PASS, t.verdict());
        assertEquals(3, t.chunks);
    }

    // Row 5
    @Test
    public void badCrcOrNotRaastIs6A80AndFail() {
        assertEquals(Apdu.SW_BAD_DATA, tap(PhoneFixtures.spec(PhoneFixtures.DYNAMIC.replace("6304C871", "6304C872"))));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.FAIL, t.verdict());
        assertTrue(t.reason(), t.reason().contains("CRC"));

        assertEquals(Apdu.SW_BAD_DATA, tap(PhoneFixtures.spec("MR:4210112345671|Amount:100")));
        assertEquals(TapRecord.Verdict.FAIL, end().verdict());
    }

    // Row 6
    @Test
    public void pushWithoutSelect() {
        assertEquals(Apdu.SW_CONDITIONS, sw(send(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0))));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.FAIL, t.verdict());
        assertEquals("PUSH without SELECT", t.reason());
    }

    // Row 7
    @Test
    public void outOfSequenceOrMixedFormsFail() {
        List<byte[]> spec = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        assertEquals(Apdu.SW_WRONG_P1P2, tap(java.util.Arrays.asList(spec.get(0), spec.get(2))));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.FAIL, t.verdict());
        assertTrue(t.reason(), t.reason().contains("out of sequence"));

        List<byte[]> index = PhoneFixtures.index(PhoneFixtures.DYNAMIC, 100);
        assertEquals(Apdu.SW_WRONG_P1P2, tap(java.util.Arrays.asList(spec.get(0), index.get(0))));
        assertTrue(end().reason().contains("form changed"));
    }

    // Row 8
    @Test
    public void unknownP1() {
        assertEquals(Apdu.SW_WRONG_P1P2, tap(java.util.Collections.singletonList(
                PhoneFixtures.cmd(0x05, 0, new byte[]{0x41}, 0, 1))));
        assertEquals(TapRecord.Verdict.FAIL, end().verdict());
    }

    // Row 9
    @Test
    public void wrongClaOrIns() {
        assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
        assertEquals(PhoneResponder.SW_CLA_NOT_SUPPORTED, sw(send(new byte[]{0x00, (byte) 0xCA, 3, 0, 1, 0x41})));
        assertEquals(Apdu.SW_INS_NOT_SUPPORTED, sw(send(new byte[]{(byte) 0x80, (byte) 0xCB, 3, 0, 1, 0x41})));
        assertEquals(TapRecord.Verdict.FAIL, end().verdict());
    }

    // Row 10
    @Test
    public void badLc() {
        assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
        assertEquals(Apdu.SW_BAD_DATA, sw(send(new byte[]{(byte) 0x80, (byte) 0xCA, 3, 0, 9, 0x41})));
        assertEquals(Apdu.SW_BAD_DATA, sw(send(new byte[]{(byte) 0x80, (byte) 0xCA})));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.FAIL, t.verdict());
        assertTrue(t.reason(), t.reason().contains("longer than the data"));
    }

    // Row 11
    @Test
    public void extendedLcAccepted() {
        byte[] d = PhoneFixtures.STATIC.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] apdu = new byte[7 + d.length];
        apdu[0] = (byte) 0x80;
        apdu[1] = (byte) 0xCA;
        apdu[2] = 3;
        apdu[6] = (byte) d.length;
        System.arraycopy(d, 0, apdu, 7, d.length);
        assertEquals(Apdu.SW_OK, tap(java.util.Collections.singletonList(apdu)));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.PASS, t.verdict());
        assertTrue(t.exchanges().get(2).summary.contains("extended"));
    }

    // Row 12
    @Test
    public void payloadOver4096Bytes() {
        byte[] d = new byte[239];
        java.util.Arrays.fill(d, (byte) 'A');
        assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
        int last = 0;
        for (int i = 0; i < 18 && last != Apdu.SW_BAD_DATA; i++) last = sw(send(PhoneFixtures.cmd(0, i, d, 0, d.length)));
        assertEquals(Apdu.SW_BAD_DATA, last);
        assertTrue(end().reason().contains("4096"));
    }

    // Row 13
    @Test
    public void linkLostMidTransfer() {
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC);
        assertEquals(Apdu.SW_OK, tap(pushes.subList(0, 1)));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.FAIL, t.verdict());
        assertEquals("link lost after chunk 1, QR never completed", t.reason());
    }

    // Row 13b: selected but nothing pushed
    @Test
    public void selectOnly() {
        assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
        assertEquals("POS sent no QR", end().reason());
    }

    // Row 14
    @Test
    public void secondSelectClosesTheOpenTap() {
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC);
        tap(pushes.subList(0, 1));
        tap(pushes);
        assertEquals(1, closed.size());
        assertEquals(TapRecord.Verdict.FAIL, closed.get(0).verdict());
        assertTrue(closed.get(0).notes().toString().contains("new SELECT"));
        TapRecord second = end();
        assertEquals(2, second.number);
        assertEquals(TapRecord.Verdict.PASS, second.verdict());
    }

    // Row 15
    @Test
    public void sameQrAgainIsNoted() {
        tap(PhoneFixtures.spec(PhoneFixtures.STATIC));
        end();
        assertEquals(Apdu.SW_OK, tap(PhoneFixtures.spec(PhoneFixtures.STATIC)));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.PASS, t.verdict());
        assertTrue(t.notes().contains("same QR as tap #1"));
    }

    // Row 16
    @Test
    public void caseIsFrozenAtSelect() {
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC);
        assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
        current = PhoneCase.REJECT_DATA;
        for (byte[] p : pushes) assertEquals(Apdu.SW_OK, sw(send(p)));
        TapRecord t = end();
        assertEquals(PhoneCase.ACCEPT, t.phoneCase);
        assertEquals(TapRecord.Verdict.PASS, t.verdict());
    }

    // The kept fault case: the wallet takes the whole QR and refuses it as invalid data
    @Test
    public void rejectDataAnswers6A80() {
        current = PhoneCase.REJECT_DATA;
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC);
        assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
        assertEquals(Apdu.SW_OK, sw(send(pushes.get(0))));
        assertEquals(Apdu.SW_BAD_DATA, sw(send(pushes.get(1))));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.DONE, t.verdict());
        assertTrue(t.describe(false).contains("Check on POS"));
        assertEquals("phone received the whole QR and refused it with 6A80", t.reason());
        assertTrue(PhoneCase.REJECT_DATA.phoneDoes.contains("6A80"));
    }

    // Reject case with a protocol error is still a FAIL (the POS broke the transfer, not the injected fault)
    @Test
    public void rejectDataWithBrokenTransferFails() {
        current = PhoneCase.REJECT_DATA;
        List<byte[]> spec = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        assertEquals(Apdu.SW_WRONG_P1P2, tap(java.util.Arrays.asList(spec.get(0), spec.get(2))));
        assertEquals(TapRecord.Verdict.FAIL, end().verdict());
    }

    // Reject case, link lost before the QR completed
    @Test
    public void rejectDataLinkLostIsFail() {
        current = PhoneCase.REJECT_DATA;
        tap(PhoneFixtures.spec(PhoneFixtures.DYNAMIC).subList(0, 1));
        TapRecord t = end();
        assertEquals(TapRecord.Verdict.FAIL, t.verdict());
        assertEquals("link lost after chunk 1, QR never completed", t.reason());
    }

    // Row 21
    @Test
    public void staticQrPasses() {
        tap(PhoneFixtures.spec(PhoneFixtures.STATIC));
        TapRecord t = end();
        assertEquals("valid static QR received and accepted", t.reason());
    }

    @Test
    public void deactivationWithNoTapIsIgnoredAndSelectReturnsFci() {
        assertNull(r.onDeactivated(false));
        assertArrayEquals(Apdu.withSw(Apdu.FCI, Apdu.SW_OK), send(Apdu.SELECT));
        assertNotNull(r.openTap());
        assertTrue(changes > 0);
    }

    @Test
    public void caseNamesParse() {
        assertEquals(PhoneCase.REJECT_DATA, PhoneCase.parse(" reject_data "));
        assertEquals(PhoneCase.ACCEPT, PhoneCase.parse("bogus"));
        assertEquals(PhoneCase.ACCEPT, PhoneCase.parse(null));
    }

    // ---- QR-kind cases (rows 26-35) ----

    private static String qr(boolean dynamic, String amount) {
        return SampleQr.build(dynamic, amount, "REF0001");
    }

    /** A QR whose tag 54 is present but unusable (zero, or not a number), correctly signed. */
    private static String withRawAmount(String amount) {
        String s = qr(false, null);
        return PhoneFixtures.resign(s.substring(0, s.length() - 8) + SampleQr.tlv("54", amount));
    }

    private TapRecord tapAs(PhoneCase c, String qr, int expectSw) {
        current = c;
        assertEquals(expectSw, tap(PhoneFixtures.spec(qr)));
        return end();
    }

    // Rows 26, 28, 29, 31: each kind passes its own case, answered 9000
    @Test
    public void eachQrKindPassesItsOwnCase() {
        Object[][] rows = {
                {PhoneCase.SQRC_NO_AMOUNT, qr(false, null), "valid static QR without amount received and accepted"},
                {PhoneCase.SQRC_WITH_AMOUNT, qr(false, "150.00"), "valid static QR with amount received and accepted"},
                {PhoneCase.DQRC_WITH_AMOUNT, qr(true, "2.0"), "valid dynamic QR with amount received and accepted"},
                {PhoneCase.DQRC_NO_AMOUNT, qr(true, null), "valid dynamic QR without amount received and accepted"},
        };
        for (Object[] row : rows) {
            TapRecord t = tapAs((PhoneCase) row[0], (String) row[1], Apdu.SW_OK);
            assertEquals(row[0].toString(), TapRecord.Verdict.PASS, t.verdict());
            assertEquals(row[2], t.reason());
            assertTrue(t.describe(false).contains("Expected: " + ((PhoneCase) row[0]).expectedKind()));
        }
    }

    // Rows 27, 30, 32: every other kind fails every QR-kind case, and the phone still answers 9000 like the NBP app
    @Test
    public void everyWrongKindFailsWithWhatArrived() {
        PhoneCase[] cases = {PhoneCase.SQRC_NO_AMOUNT, PhoneCase.SQRC_WITH_AMOUNT, PhoneCase.DQRC_WITH_AMOUNT,
                PhoneCase.DQRC_NO_AMOUNT};
        String[] qrs = {qr(false, null), qr(false, "150.00"), qr(true, "2.0"), qr(true, null)};
        String[] got = {"static QR without amount", "static QR with amount PKR 150.00",
                "dynamic QR with amount PKR 2.0", "dynamic QR without amount"};
        for (int c = 0; c < cases.length; c++) {
            for (int q = 0; q < qrs.length; q++) {
                if (c == q) continue;
                TapRecord t = tapAs(cases[c], qrs[q], Apdu.SW_OK);
                assertEquals(cases[c] + "/" + q, TapRecord.Verdict.FAIL, t.verdict());
                assertEquals("accepted, but expected " + cases[c].expectedKind() + ", got " + got[q], t.reason());
            }
        }
    }

    // Row 33: tag 54 present but zero or not a number fails both the with- and without-amount cases
    @Test
    public void unusableAmountFailsBothAmountCases() {
        for (String bad : new String[] {"0", "0.00", "1,000", "abc"}) {
            String q = withRawAmount(bad);
            for (PhoneCase c : new PhoneCase[] {PhoneCase.SQRC_NO_AMOUNT, PhoneCase.SQRC_WITH_AMOUNT}) {
                TapRecord t = tapAs(c, q, Apdu.SW_OK);
                assertEquals(bad + "/" + c, TapRecord.Verdict.FAIL, t.verdict());
                assertTrue(t.reason(), t.reason().endsWith("got static QR with an unusable amount \"" + bad + "\""));
            }
        }
        assertEquals(RaastQr.AmountState.UNUSABLE, RaastQr.of(withRawAmount("0")).amountState());
    }

    // Row 34: a bad CRC is still 6A80 and the CRC is the reason, not the kind
    @Test
    public void badCrcBeatsTheKindCheck() {
        String good = qr(false, null);
        String bad = good.substring(0, good.length() - 4) + ("0000".equals(good.substring(good.length() - 4)) ? "1111" : "0000");
        TapRecord t = tapAs(PhoneCase.SQRC_NO_AMOUNT, bad, Apdu.SW_BAD_DATA);
        assertEquals(TapRecord.Verdict.FAIL, t.verdict());
        assertTrue(t.reason(), t.reason().startsWith("QR rejected: CRC"));
    }

    // Row 35: 01 missing or another value fails a QR-kind case
    @Test
    public void unknownQrTypeFails() {
        String s = qr(false, null);
        String no01 = PhoneFixtures.resign(s.substring(0, s.length() - 8).replace("010211", ""));
        TapRecord t = tapAs(PhoneCase.SQRC_NO_AMOUNT, no01, Apdu.SW_OK);
        assertEquals(TapRecord.Verdict.FAIL, t.verdict());
        assertEquals("accepted, but expected static QR without amount, got unknown QR without amount", t.reason());
    }

    // Row 36: ACCEPT checks no kind; the index header form works for the QR-kind cases too
    @Test
    public void acceptTakesAnyKindAndIndexFormIsChecked() {
        for (String q : new String[] {qr(false, null), qr(false, "1"), qr(true, "2.0"), qr(true, null)}) {
            TapRecord t = tapAs(PhoneCase.ACCEPT, q, Apdu.SW_OK);
            assertEquals(TapRecord.Verdict.PASS, t.verdict());
            assertTrue(!t.describe(false).contains("Expected:"));
        }
        current = PhoneCase.DQRC_NO_AMOUNT;
        assertEquals(Apdu.SW_OK, tap(PhoneFixtures.index(qr(true, "5"), 100)));
        TapRecord t = end();
        assertEquals(PushAssembler.Form.INDEX, t.form());
        assertEquals("accepted, but expected dynamic QR without amount, got dynamic QR with amount PKR 5", t.reason());
    }

    // Row 37: a QR-kind case with a slow or faulty answer is not mixed up: the case set at SELECT decides
    @Test
    public void caseChangedMidTapKeepsTheKindReadAtSelect() {
        current = PhoneCase.DQRC_WITH_AMOUNT;
        List<byte[]> pushes = PhoneFixtures.spec(qr(true, "2.0"), 100);
        assertEquals(Apdu.SW_OK, sw(send(Apdu.SELECT)));
        sw(send(pushes.get(0)));
        current = PhoneCase.SQRC_NO_AMOUNT;
        for (int i = 1; i < pushes.size(); i++) sw(send(pushes.get(i)));
        assertEquals(TapRecord.Verdict.PASS, end().verdict());
    }

    // Row 38: every case has a label and a description; kept names still parse, removed ones fall back to ACCEPT
    @Test
    public void everyCaseIsDescribedAndOldNamesParse() {
        for (PhoneCase c : PhoneCase.values()) {
            assertTrue(c + " label", c.label.length() > 3);
            assertTrue(c + " simulates", c.simulates.length() > 20);
            assertTrue(c + " phoneDoes", c.phoneDoes.length() > 10);
            assertTrue(c + " checkOnPos", c.checkOnPos.length() > 5);
            assertEquals(c.checksQrKind(), c.expectedKind() != null);
        }
        for (String kept : new String[] {"ACCEPT", "REJECT_DATA"}) assertEquals(kept, PhoneCase.parse(kept).name());
        // a case saved in preferences by an older build, since removed: the phone falls back to the generic case
        for (String removed : new String[] {"NOT_INSTALLED", "REJECT_CONDITIONS", "REJECT_ERROR",
                "BREAK_SECOND_CHUNK", "SLOW_ANSWER", "NO_ANSWER"}) {
            assertEquals(removed, PhoneCase.ACCEPT, PhoneCase.parse(removed));
        }
        assertEquals("Generic QR", PhoneCase.ACCEPT.label);
        assertEquals("Invalid QR", PhoneCase.REJECT_DATA.label);
        for (PhoneCase c : PhoneCase.values()) {
            assertTrue(c + " says bank app, not wallet", !(c.label + c.simulates + c.phoneDoes + c.checkOnPos)
                    .toLowerCase(java.util.Locale.ROOT).contains("wallet"));
        }
        assertEquals(6, PhoneCase.values().length);
        assertEquals(PhoneCase.REJECT_DATA, PhoneCase.values()[PhoneCase.values().length - 1]);
        assertEquals(PhoneCase.DQRC_NO_AMOUNT, PhoneCase.parse("dqrc_no_amount"));
    }
}
