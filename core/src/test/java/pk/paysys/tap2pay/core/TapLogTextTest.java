package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** One test per "Copy log" row of bank-app/README.md (rows 107-113). */
public class TapLogTextTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long T0 = 1_700_000_000_000L;

    private final List<NfcSession> accepted = new ArrayList<>();
    private final List<NfcSession> rejected = new ArrayList<>();
    private final List<NfcSession> incomplete = new ArrayList<>();
    private final List<String> rejectedPayloads = new ArrayList<>();
    private BankResponder responder;
    private long mono;

    @Before
    public void setUp() {
        mono = 0;
        responder = new BankResponder(new PhoneResponder.Clock() {
            @Override
            public long monotonicMs() {
                return mono;
            }

            @Override
            public long wallMs() {
                return T0 + mono;
            }
        }, new BankResponder.Listener() {
            @Override
            public void onAccepted(String qr, NfcSession s) {
                accepted.add(s);
            }

            @Override
            public void onRejected(String payload, String problem, NfcSession s) {
                rejected.add(s);
                rejectedPayloads.add(payload);
            }

            @Override
            public void onIncomplete(NfcSession s) {
                incomplete.add(s);
            }
        });
    }

    private void send(byte[] apdu) {
        mono += 10;
        responder.onApdu(apdu);
    }

    private void tap(String qr) {
        send(Apdu.SELECT);
        for (byte[] a : PhoneFixtures.spec(qr, 100)) send(a);
    }

    private static TapHistory.Entry entry(TapHistory h, TapHistory.Kind k, String reason, String payload, NfcSession s) {
        return h.add(T0, k, reason, payload, s);
    }

    private static int at(String text, String needle) {
        int i = text.indexOf(needle);
        assertTrue("missing: " + needle + "\n" + text, i >= 0);
        return i;
    }

    // row 107: an accepted tap, all three sections in order with the raw bytes and the QR
    @Test
    public void anAcceptedTapHasTheThreeSectionsInOrder() {
        tap(PhoneFixtures.DYNAMIC);
        TapHistory.Entry e = entry(new TapHistory(), TapHistory.Kind.ACCEPTED, null, PhoneFixtures.DYNAMIC, accepted.get(0));
        String t = TapLogText.of(e, UTC);
        assertTrue(t, t.startsWith("NFC QR Test App - tap log\nTap #1  2023-11-14 22:13:20  RECEIVED\n"));
        assertTrue(at(t, "1. NFC COMMUNICATION  [OK]") < at(t, "2. QR VALIDATION  ["));
        assertFalse(t, t.contains("2. QR VALIDATION  [FAIL]"));
        assertTrue(at(t, "2. QR VALIDATION") < at(t, "3. DATA EXTRACTED"));
        assertTrue(t, t.contains("POS sent:        00 A4 04 00 07 F0 01 02 03 03 06 05 00"));
        assertTrue(t, t.contains("Phone answered:  6F 11 84 07 F0 01 02 03 03 06 05 A5 06 01 01 02 02 01 03 90 00"));
        assertTrue(t, t.contains("Raw QR: " + PhoneFixtures.DYNAMIC));
        assertTrue(t, t.contains(BankQr.of(PhoneFixtures.DYNAMIC).merchantName));
        assertTrue(t, t.contains("Step 1: SELECT Tap2Pay AID"));
        assertTrue(t, t.contains("Step 2: PUSH INVOICE, chunk 1"));
        assertFalse(t, t.contains("[FAIL]"));
    }

    // row 108: a refused QR keeps the failing tag and the payload, and has no extracted data
    @Test
    public void aRejectedTapShowsTheFailingCrcAndNoData() {
        String bad = PhoneFixtures.DYNAMIC.substring(0, PhoneFixtures.DYNAMIC.length() - 4) + "0000";
        tap(bad);
        TapHistory.Entry e = entry(new TapHistory(), TapHistory.Kind.REJECTED, "CRC 0000 should be x", bad, rejected.get(0));
        String t = TapLogText.of(e, UTC);
        assertTrue(t, t.contains("REJECTED"));
        assertTrue(t, t.contains("Reason: CRC 0000 should be x"));
        assertTrue(t, t.contains("2. QR VALIDATION  [FAIL]"));
        assertTrue(t, t.contains("got 0000"));
        assertTrue(t, t.contains("Raw QR: " + bad));
        assertTrue(t, t.contains("1. NFC COMMUNICATION  [OK]"));
        assertTrue(t, t.contains("3. DATA EXTRACTED\n   none: the tap was rejected"));
    }

    // row 109: a tap with no QR says so and shows where the conversation stopped
    @Test
    public void anIncompleteTapSaysNoQrReachedThePhone() {
        send(Apdu.SELECT);
        responder.onDeactivated(true);
        TapHistory.Entry e = entry(new TapHistory(), TapHistory.Kind.INCOMPLETE, "no QR reached the phone", "", incomplete.get(0));
        String t = TapLogText.of(e, UTC);
        assertTrue(t, t.contains("INCOMPLETE"));
        assertTrue(t, t.contains("1. NFC COMMUNICATION  [FAIL]"));
        assertTrue(t, t.contains("the POS sent no PUSH after the SELECT"));
        assertTrue(t, t.contains("2. QR VALIDATION  [--]\n   no QR reached the phone"));
        assertTrue(t, t.contains("3. DATA EXTRACTED\n   none: the tap was incomplete"));
    }

    // row 110: a tap saved without a log, and an empty payload, never throw
    @Test
    public void aTapWithoutAnNfcLogIsStillWritten() {
        TapHistory.Entry e = entry(new TapHistory(), TapHistory.Kind.ACCEPTED, null, PhoneFixtures.STATIC, null);
        String t = TapLogText.of(e, UTC);
        assertTrue(t, t.contains("1. NFC COMMUNICATION  [--]\n   no NFC log was saved for this tap"));
        assertTrue(t, t.contains("Raw QR: " + PhoneFixtures.STATIC));
        TapHistory.Entry empty = entry(new TapHistory(), TapHistory.Kind.REJECTED, "rejected", "", null);
        assertNotNull(TapLogText.of(empty, UTC));
    }

    // row 111: all the taps in the order given, with a count
    @Test
    public void allTheTapsAreWrittenInOrder() {
        TapHistory h = new TapHistory();
        tap(PhoneFixtures.STATIC);
        TapHistory.Entry a = entry(h, TapHistory.Kind.ACCEPTED, null, PhoneFixtures.STATIC, accepted.get(0));
        send(Apdu.SELECT);
        responder.onDeactivated(false);
        TapHistory.Entry b = entry(h, TapHistory.Kind.INCOMPLETE, "no QR reached the phone", "", incomplete.get(0));
        List<TapHistory.Entry> newestFirst = new ArrayList<>();
        newestFirst.add(b);
        newestFirst.add(a);
        String t = TapLogText.all(newestFirst, UTC);
        assertTrue(t, t.startsWith("NFC QR Test App - 2 taps\n"));
        assertTrue(at(t, "Tap #2") < at(t, "Tap #1"));
        assertEquals("one rule before each tap", 2, t.split("--------------------------------", -1).length - 1);
        assertEquals("NFC QR Test App - 0 taps\n", TapLogText.all(new ArrayList<TapHistory.Entry>(), UTC));
        assertTrue(TapLogText.all(java.util.Collections.singletonList(a), UTC).startsWith("NFC QR Test App - 1 tap\n"));
    }

    // row 112: the times are in the zone asked for
    @Test
    public void theTimeFollowsTheZone() {
        TapHistory.Entry e = entry(new TapHistory(), TapHistory.Kind.INCOMPLETE, "x", "", null);
        assertTrue(TapLogText.of(e, UTC).contains("2023-11-14 22:13:20"));
        assertTrue(TapLogText.of(e, TimeZone.getTimeZone("Asia/Karachi")).contains("2023-11-15 03:13:20"));
    }

    // row 113: no log, whatever the tap, names another app
    @Test
    public void noLogMentionsAnotherApp() {
        List<String> texts = new ArrayList<>();
        // spec tap, Sunmi index header, Lc 0, SELECT only, bad CRC, oversize
        tap(PhoneFixtures.DYNAMIC);
        texts.add(TapLogText.of(entry(new TapHistory(), TapHistory.Kind.ACCEPTED, null, PhoneFixtures.DYNAMIC, accepted.get(accepted.size() - 1)), UTC));
        send(Apdu.SELECT);
        for (byte[] a : PhoneFixtures.index(PhoneFixtures.DYNAMIC, 100)) send(a);
        texts.add(TapLogText.of(entry(new TapHistory(), TapHistory.Kind.ACCEPTED, null, PhoneFixtures.DYNAMIC, accepted.get(accepted.size() - 1)), UTC));
        send(Apdu.SELECT);
        send(new byte[] {(byte) 0x80, (byte) 0xCA, 0x03, 0x00, 0x00, 0x00});
        responder.onDeactivated(true);
        texts.add(TapLogText.of(entry(new TapHistory(), TapHistory.Kind.INCOMPLETE, "no QR reached the phone", "", incomplete.get(incomplete.size() - 1)), UTC));
        String bad = PhoneFixtures.DYNAMIC.substring(0, PhoneFixtures.DYNAMIC.length() - 4) + "0000";
        tap(bad);
        texts.add(TapLogText.of(entry(new TapHistory(), TapHistory.Kind.REJECTED, "CRC", bad, rejected.get(rejected.size() - 1)), UTC));
        // the QR a POS sends is shown as it came, so its own text (the fixture's IBAN has a bank code) is left out
        String iban = BankQr.of(PhoneFixtures.DYNAMIC).iban;
        for (String t : texts) {
            String own = t.replace(PhoneFixtures.DYNAMIC, "").replace(bad, "").replace(iban, "").toLowerCase(java.util.Locale.US);
            assertFalse(t, own.contains("nbp"));
            assertFalse(t, own.contains("national bank"));
        }
    }
}
