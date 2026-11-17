package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One test per NfcReport and NfcSession row of bank-app/README.md (rows 70-89). */
public class NfcReportTest {
    private long mono;
    private long gap = 10;
    private BankResponder responder;

    @Before
    public void setUp() {
        mono = 0;
        gap = 10;
        responder = new BankResponder(new PhoneResponder.Clock() {
            @Override
            public long monotonicMs() {
                return mono;
            }

            @Override
            public long wallMs() {
                return 1_700_000_000_000L + mono;
            }
        }, new BankResponder.Listener() {
            @Override
            public void onAccepted(String qr, NfcSession session) {}

            @Override
            public void onRejected(String payload, String problem, NfcSession session) {}

            @Override
            public void onIncomplete(NfcSession session) {}
        });
    }

    /** Runs the commands through the real responder and records the conversation the way the app does. */
    private NfcSession play(byte[]... commands) {
        List<NfcSession.Exchange> ex = new ArrayList<>();
        long start = -1;
        for (byte[] c : commands) {
            mono += gap;
            if (start < 0) start = mono;
            byte[] resp = responder.onApdu(c);
            ex.add(new NfcSession.Exchange(mono - start, c, resp));
        }
        return new NfcSession(1_700_000_000_000L, ex, null);
    }

    private static byte[] cmd(int cla, int ins, int p1, int p2, byte[] data) {
        byte[] a = new byte[5 + data.length];
        a[0] = (byte) cla;
        a[1] = (byte) ins;
        a[2] = (byte) p1;
        a[3] = (byte) p2;
        a[4] = (byte) data.length;
        System.arraycopy(data, 0, a, 5, data.length);
        return a;
    }

    private static NfcReport.Field field(NfcReport.Step s, String name) {
        for (NfcReport.Field f : s.fields) if (f.name.equals(name)) return f;
        throw new AssertionError("no field " + name + " in step " + s.number);
    }

    private static NfcReport.Field row(NfcReport r, String name) {
        for (NfcReport.Field f : r.session) if (f.name.equals(name)) return f;
        throw new AssertionError("no session row " + name);
    }

    private static List<byte[]> list(byte[]... a) {
        return Arrays.asList(a);
    }

    // row 70: a tap exactly as the spec describes
    @Test
    public void aSpecTapWithOnePushIsAllOk() {
        NfcReport r = NfcReport.of(play(Apdu.SELECT, PhoneFixtures.spec(PhoneFixtures.STATIC).get(0)));
        assertEquals(BankQr.Status.OK, r.status);
        assertEquals(2, r.steps.size());
        assertEquals("SELECT Tap2Pay AID", r.steps.get(0).title);
        assertEquals("PUSH INVOICE (single APDU)", r.steps.get(1).title);
        for (NfcReport.Step s : r.steps) {
            for (NfcReport.Field f : s.fields) assertEquals(s.number + " " + f.name + ": " + f.note, BankQr.Status.OK, f.status);
        }
        assertEquals("00 A4 04 00 07 F0 01 02 03 03 06 05 00", r.steps.get(0).command);
        assertTrue(r.steps.get(0).response.endsWith("90 00"));
        assertEquals(PhoneFixtures.STATIC, r.steps.get(1).data);
        assertTrue(row(r, "QR delivered").got, row(r, "QR delivered").got.startsWith("complete: "));
        assertEquals("SELECT + 1 PUSH answered", r.summary);
        assertEquals("03", field(r.steps.get(1), "P1").got);
        assertEquals("9000", field(r.steps.get(1), "Phone answered").got);
    }

    // row 71: chunked
    @Test
    public void aChunkedSpecTapNumbersTheChunksAndEndsWithTheLast() {
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        List<byte[]> all = new ArrayList<>();
        all.add(Apdu.SELECT);
        all.addAll(pushes);
        NfcReport r = NfcReport.of(play(all.toArray(new byte[0][])));
        assertEquals(BankQr.Status.OK, r.status);
        assertEquals("PUSH INVOICE, chunk 1", r.steps.get(1).title);
        assertEquals("PUSH INVOICE, chunk 2", r.steps.get(2).title);
        assertEquals("PUSH INVOICE, chunk 3 (last)", r.steps.get(3).title);
        assertEquals("01", field(r.steps.get(1), "P2").got);
        assertEquals("83", field(r.steps.get(3), "P2").got);
        assertTrue(row(r, "QR delivered").got, row(r, "QR delivered").got.contains(PhoneFixtures.DYNAMIC.length() + " bytes in 3 APDUs"));
    }

    // row 72: Sunmi index header
    @Test
    public void theIndexHeaderIsAWarningBecauseTheSpecWouldAnswer6A86() {
        List<byte[]> all = new ArrayList<>();
        all.add(Apdu.SELECT);
        all.addAll(PhoneFixtures.index(PhoneFixtures.DYNAMIC, 100));
        NfcReport r = NfcReport.of(play(all.toArray(new byte[0][])));
        assertEquals(BankQr.Status.WARN, r.status);
        NfcReport.Step first = r.steps.get(1);
        assertEquals(BankQr.Status.WARN, field(first, "P1").status);
        assertEquals(BankQr.Status.OK, field(first, "P2").status);
        NfcReport.Field a = field(first, "Phone answered");
        assertEquals("6A86", a.expected);
        assertEquals("9000", a.got);
        assertEquals(BankQr.Status.WARN, a.status);
        assertEquals(BankQr.Status.WARN, row(r, "Header form").status);
        assertTrue(r.summary, r.summary.contains("warning"));
    }

    // row 73: SELECT without Le
    @Test
    public void aSelectWithoutLeIsAWarning() {
        byte[] noLe = Arrays.copyOf(Apdu.SELECT, Apdu.SELECT.length - 1);
        NfcReport r = NfcReport.of(play(noLe));
        NfcReport.Field le = field(r.steps.get(0), "Le");
        assertEquals("absent", le.got);
        assertEquals(BankQr.Status.WARN, le.status);
        assertEquals(BankQr.Status.OK, field(r.steps.get(0), "Phone answered").status);
    }

    // row 74: the FCI is part of what is judged
    @Test
    public void aWrongSelectAnswerFails() {
        List<NfcSession.Exchange> ex = new ArrayList<>();
        ex.add(new NfcSession.Exchange(0, Apdu.SELECT, Apdu.sw(Apdu.SW_OK)));
        NfcReport r = NfcReport.of(new NfcSession(0, ex, null));
        NfcReport.Field a = field(r.steps.get(0), "Phone answered");
        assertEquals(BankQr.Status.FAIL, a.status);
        assertEquals("6F 11 84 07 F0 01 02 03 03 06 05 A5 06 01 01 02 02 01 03 90 00", a.expected);
        assertEquals("90 00", a.got);
    }

    // row 75: class and instruction
    @Test
    public void aWrongClassOrInstructionFailsThatFieldAndTheRefusalIsTheExpectedOne() {
        NfcReport r = NfcReport.of(play(Apdu.SELECT,
                cmd(0x00, 0xCA, 0x03, 0x00, new byte[] {0x30}),
                cmd(0x80, 0xCB, 0x03, 0x00, new byte[] {0x30})));
        NfcReport.Step cla = r.steps.get(1);
        assertEquals(BankQr.Status.FAIL, field(cla, "CLA").status);
        assertEquals("80", field(cla, "CLA").expected);
        assertEquals("6E00", field(cla, "Phone answered").got);
        assertEquals(BankQr.Status.OK, field(cla, "Phone answered").status);
        NfcReport.Step ins = r.steps.get(2);
        assertEquals(BankQr.Status.FAIL, field(ins, "INS").status);
        assertEquals("6D00", field(ins, "Phone answered").got);
        assertEquals(BankQr.Status.OK, field(ins, "Phone answered").status);
        assertEquals("PUSH INVOICE (not processed)", cla.title);
        assertEquals(BankQr.Status.FAIL, r.status);
    }

    // row 76: PUSH before SELECT
    @Test
    public void aPushBeforeSelectFailsTheSessionAndTheSummaryNamesIt() {
        NfcReport r = NfcReport.of(play(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0)));
        assertEquals(BankQr.Status.FAIL, row(r, "SELECT first").status);
        assertEquals(BankQr.Status.FAIL, r.status);
        assertTrue(r.summary, r.summary.startsWith("SELECT first"));
        assertEquals("6985", field(r.steps.get(0), "Phone answered").got);
        assertEquals(BankQr.Status.OK, field(r.steps.get(0), "Phone answered").status);
    }

    // row 77: bad P1
    @Test
    public void anUnsupportedP1FailsAndIsAnswered6A86() {
        NfcReport r = NfcReport.of(play(Apdu.SELECT, cmd(0x80, 0xCA, 0x05, 0x00, new byte[] {0x30})));
        NfcReport.Step s = r.steps.get(1);
        assertEquals(BankQr.Status.FAIL, field(s, "P1").status);
        assertEquals("05", field(s, "P1").got);
        assertEquals("6A86", field(s, "Phone answered").got);
        assertEquals(BankQr.Status.OK, field(s, "Phone answered").status);
        assertEquals("PUSH INVOICE (refused)", s.title);
    }

    // row 78: chunk out of sequence
    @Test
    public void aChunkOutOfSequenceFailsP2() {
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        NfcReport r = NfcReport.of(play(Apdu.SELECT, pushes.get(0), pushes.get(2)));
        NfcReport.Step s = r.steps.get(2);
        assertEquals(BankQr.Status.FAIL, field(s, "P2").status);
        assertTrue(field(s, "P2").note, field(s, "P2").note.contains("out of sequence"));
        assertEquals(BankQr.Status.OK, field(s, "P1").status);
        assertEquals("6A86", field(s, "Phone answered").got);
        assertEquals(BankQr.Status.OK, field(s, "Phone answered").status);
        assertEquals(BankQr.Status.FAIL, row(r, "QR delivered").status);
    }

    // row 79: Lc 0
    @Test
    public void anEmptyChunkFailsLcAndWarnsThatTheSpecWouldAnswer6700() {
        NfcReport r = NfcReport.of(play(Apdu.SELECT, new byte[] {(byte) 0x80, (byte) 0xCA, 0x03, 0x00, 0x00, 0x00}));
        NfcReport.Step s = r.steps.get(1);
        assertEquals(BankQr.Status.FAIL, field(s, "Lc").status);
        NfcReport.Field a = field(s, "Phone answered");
        assertEquals("6700", a.expected);
        assertEquals("6A80", a.got);
        assertEquals(BankQr.Status.WARN, a.status);
    }

    // row 80: Lc above 239
    @Test
    public void aChunkOver239BytesIsAWarningNotAFailure() {
        byte[] qr = PhoneFixtures.DYNAMIC.getBytes(StandardCharsets.UTF_8);
        byte[] push = new byte[7 + qr.length];
        push[0] = (byte) 0x80;
        push[1] = (byte) 0xCA;
        push[2] = 0x03;
        push[3] = 0x00;
        push[4] = 0x00;
        push[5] = (byte) (qr.length >> 8);
        push[6] = (byte) qr.length;
        System.arraycopy(qr, 0, push, 7, qr.length);
        NfcReport r = NfcReport.of(play(Apdu.SELECT, push));
        NfcReport.Field lc = field(r.steps.get(1), "Lc");
        assertEquals(PhoneFixtures.DYNAMIC.length() + " (extended Lc)", lc.got);
        assertEquals(BankQr.Status.WARN, lc.status);
        assertEquals(BankQr.Status.OK, field(r.steps.get(1), "Data").status);
        assertEquals(BankQr.Status.OK, field(r.steps.get(1), "Phone answered").status);
        assertEquals(BankQr.Status.WARN, r.status);
    }

    // row 81: data shorter than Lc
    @Test
    public void dataShorterThanLcFails() {
        NfcReport r = NfcReport.of(play(Apdu.SELECT, new byte[] {(byte) 0x80, (byte) 0xCA, 0x03, 0x00, 0x05, 0x41}));
        NfcReport.Step s = r.steps.get(1);
        assertEquals(BankQr.Status.FAIL, field(s, "Data").status);
        assertEquals("1 bytes", field(s, "Data").got);
        assertEquals("6700", field(s, "Phone answered").expected);
        assertEquals(BankQr.Status.WARN, field(s, "Phone answered").status);
    }

    // row 82: a bad QR is the QR section's problem, not the NFC section's
    @Test
    public void aQrWithABadCrcIsStillAnOkTapBecauseTheAnswerIsTheSpecOne() {
        String bad = PhoneFixtures.STATIC.replace("630477E3", "630477E4");
        NfcReport r = NfcReport.of(play(Apdu.SELECT, PhoneFixtures.spec(bad).get(0)));
        NfcReport.Field a = field(r.steps.get(1), "Phone answered");
        assertEquals("6A80", a.expected);
        assertEquals("6A80", a.got);
        assertEquals(BankQr.Status.OK, a.status);
        assertEquals(BankQr.Status.OK, r.status);
    }

    // row 83: a transfer that stops half way
    @Test
    public void aTransferThatNeverCompletesFailsAndTheSummaryNamesIt() {
        NfcReport r = NfcReport.of(play(Apdu.SELECT, PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100).get(0)));
        assertEquals(BankQr.Status.FAIL, row(r, "QR delivered").status);
        assertTrue(row(r, "QR delivered").got, row(r, "QR delivered").got.contains("last answer 9000"));
        assertEquals(BankQr.Status.FAIL, r.status);
        assertTrue(r.summary, r.summary.startsWith("QR delivered"));
    }

    // row 84: gap between chunks
    @Test
    public void aGapOverTwoSecondsBetweenApdusIsAWarning() {
        gap = 2500;
        List<byte[]> all = new ArrayList<>();
        all.add(Apdu.SELECT);
        all.addAll(PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100));
        NfcReport slow = NfcReport.of(play(all.toArray(new byte[0][])));
        assertEquals(BankQr.Status.WARN, row(slow, "Gap between APDUs").status);
        assertEquals("longest 2500 ms", row(slow, "Gap between APDUs").got);

        setUp();
        gap = 2000;
        NfcReport edge = NfcReport.of(play(all.toArray(new byte[0][])));
        assertEquals("2000 ms is still inside T_chunk_gap", BankQr.Status.OK, row(edge, "Gap between APDUs").status);
    }

    // row 85: SELECT and nothing else
    @Test
    public void aSelectWithNoPushFails() {
        NfcReport r = NfcReport.of(play(Apdu.SELECT));
        assertEquals(BankQr.Status.FAIL, row(r, "QR delivered").status);
        assertEquals("the POS sent no PUSH after the SELECT", row(r, "QR delivered").got);
    }

    // row 86: oversized payload
    @Test
    public void aPayloadOver4096BytesGetsA6A80WhereTheSpecSays6A84() {
        StringBuilder big = new StringBuilder("000201");
        while (big.length() < 4200) big.append('0');
        List<byte[]> all = new ArrayList<>();
        all.add(Apdu.SELECT);
        all.addAll(PhoneFixtures.spec(big.toString(), 239));
        // the phone stops answering 9000 at the chunk that crosses the limit; stop the script there
        List<NfcSession.Exchange> ex = new ArrayList<>();
        long t = 0;
        byte[] last = null;
        for (byte[] c : all) {
            byte[] resp = responder.onApdu(c);
            ex.add(new NfcSession.Exchange(t += 10, c, resp));
            last = resp;
            if (Apdu.swOf(resp) != Apdu.SW_OK) break;
        }
        NfcSession probe = new NfcSession(0, ex, null);
        assertEquals(Apdu.SW_BAD_DATA, Apdu.swOf(last));
        NfcReport r = NfcReport.of(probe);
        NfcReport.Step s = r.steps.get(r.steps.size() - 1);
        NfcReport.Field a = field(s, "Phone answered");
        assertEquals("6A84", a.expected);
        assertEquals("6A80", a.got);
        assertEquals(BankQr.Status.WARN, a.status);
    }

    // row 87: no log
    @Test
    public void aTapWithoutALogHasNoReport() {
        assertNull(NfcReport.of(null));
    }

    // row 88: an empty session
    @Test
    public void anEmptySessionFailsWithoutAnException() {
        NfcReport r = NfcReport.of(new NfcSession(0, new ArrayList<NfcSession.Exchange>(), "link lost"));
        assertEquals(BankQr.Status.FAIL, r.status);
        assertEquals("nothing", row(r, "SELECT first").got);
        assertEquals("link lost", row(r, "Session ended").got);
        assertTrue(r.steps.isEmpty());
    }

    // row 89: the log survives the store, and a damaged one is refused
    @Test
    public void aSessionSurvivesSerializationAndDamagedTextIsRefused() {
        NfcSession s = play(Apdu.SELECT, PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100).get(0));
        NfcSession back = NfcSession.parse(new NfcSession(77, s.exchanges(), "café link").serialize());
        assertNotNull(back);
        assertEquals(77, back.startedWallMs);
        assertEquals("café link", back.end);
        assertEquals(2, back.exchanges().size());
        assertArrayEquals(s.exchanges().get(1).command(), back.exchanges().get(1).command());
        assertArrayEquals(s.exchanges().get(1).response(), back.exchanges().get(1).response());
        assertEquals(s.exchanges().get(1).atMs, back.exchanges().get(1).atMs);

        NfcSession open = NfcSession.parse(new NfcSession(5, s.exchanges(), null).serialize());
        assertNull(open.end);
        assertEquals(0, NfcSession.parse(new NfcSession(5, new ArrayList<NfcSession.Exchange>(), null).serialize())
                .exchanges().size());

        for (String junk : new String[] {null, "", "x", "12", "12;-;1,ZZ,00", "12;-;1,00", "12;-;a,00,00", "12;5;1,00,00", "q;-"}) {
            assertNull(String.valueOf(junk), NfcSession.parse(junk));
        }
    }

    // row 106: the arrays inside a session cannot be changed from outside
    @Test
    public void aSessionHoldsCopiesOfItsBytes() {
        byte[] cmd = Apdu.SELECT.clone();
        NfcSession.Exchange e = new NfcSession.Exchange(0, cmd, new byte[] {(byte) 0x90, 0x00});
        cmd[0] = 0x7F;
        assertEquals(0x00, e.command()[0]);
        e.command()[1] = 0x00;
        assertEquals((byte) 0xA4, e.command()[1]);
        assertFalse(Arrays.equals(cmd, e.command()));
    }
}
