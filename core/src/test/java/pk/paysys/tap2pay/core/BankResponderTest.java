package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One test per BankResponder row of bank-app/README.md (rows 1-17 the protocol, 60-69 the session log). */
public class BankResponderTest {
    private final List<String> accepted = new ArrayList<>();
    private final List<NfcSession> acceptedSessions = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();
    private final List<String> reasons = new ArrayList<>();
    private final List<NfcSession> rejectedSessions = new ArrayList<>();
    private final List<NfcSession> incomplete = new ArrayList<>();
    private boolean listenerThrows;
    private long mono = 5_000;
    private BankResponder r;

    @Before
    public void setUp() {
        r = new BankResponder(new PhoneResponder.Clock() {
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
            public void onAccepted(String qr, NfcSession session) {
                accepted.add(qr);
                acceptedSessions.add(session);
                if (listenerThrows) throw new IllegalStateException("boom");
            }

            @Override
            public void onRejected(String payload, String problem, NfcSession session) {
                rejected.add(payload);
                reasons.add(problem);
                rejectedSessions.add(session);
                if (listenerThrows) throw new IllegalStateException("boom");
            }

            @Override
            public void onIncomplete(NfcSession session) {
                incomplete.add(session);
                if (listenerThrows) throw new IllegalStateException("boom");
            }
        });
    }

    private int sw(byte[] apdu) {
        mono += 7;
        return Apdu.swOf(r.onApdu(apdu));
    }

    private void select() {
        mono += 7;
        byte[] resp = r.onApdu(Apdu.SELECT);
        assertEquals(Apdu.SW_OK, Apdu.swOf(resp));
    }

    /** Sends every APDU, returns the status words. */
    private List<Integer> send(List<byte[]> apdus) {
        List<Integer> out = new ArrayList<>();
        for (byte[] a : apdus) out.add(sw(a));
        return out;
    }

    // row 1
    @Test
    public void selectAnswersTheFciAnd9000() {
        byte[] resp = r.onApdu(Apdu.SELECT);
        assertArrayEquals(Apdu.withSw(Apdu.FCI, Apdu.SW_OK), resp);
    }

    // row 2: spec form, one APDU
    @Test
    public void validQrInOneSpecApduIsAcceptedAnd9000() {
        select();
        assertEquals(Apdu.SW_OK, sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0)));
        assertEquals(1, accepted.size());
        assertEquals(PhoneFixtures.STATIC, accepted.get(0));
        assertTrue(rejected.isEmpty());
    }

    // row 3: spec form, chunked
    @Test
    public void chunkedSpecQrIsAcceptedOnlyOnTheLastChunk() {
        select();
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        assertTrue(pushes.size() > 1);
        for (int i = 0; i < pushes.size(); i++) {
            assertEquals(Apdu.SW_OK, sw(pushes.get(i)));
            assertEquals(i == pushes.size() - 1 ? 1 : 0, accepted.size());
        }
        assertEquals(PhoneFixtures.DYNAMIC, accepted.get(0));
        assertEquals(1 + pushes.size(), acceptedSessions.get(0).exchanges().size());
    }

    // row 4: index form (Sunmi), one APDU and chunked
    @Test
    public void indexFormIsAcceptedOneApduAndChunked() {
        select();
        assertEquals(Apdu.SW_OK, sw(PhoneFixtures.index(PhoneFixtures.STATIC, 250).get(0)));
        select();
        for (byte[] a : PhoneFixtures.index(PhoneFixtures.DYNAMIC, 100)) assertEquals(Apdu.SW_OK, sw(a));
        assertEquals(2, accepted.size());
        assertEquals(PhoneFixtures.DYNAMIC, accepted.get(1));
    }

    // row 5: bad CRC
    @Test
    public void badCrcIsRefused6A80AndReported() {
        select();
        String bad = PhoneFixtures.DYNAMIC.replace("6304C871", "6304C872");
        List<Integer> sws = send(PhoneFixtures.spec(bad));
        assertTrue("QR is long enough to be chunked", sws.size() > 1);
        assertEquals("chunks before the last are buffered", Integer.valueOf(Apdu.SW_OK), sws.get(0));
        assertEquals(Integer.valueOf(Apdu.SW_BAD_DATA), sws.get(sws.size() - 1));
        assertTrue(accepted.isEmpty());
        assertEquals(1, rejected.size());
        assertEquals(bad, rejected.get(0));
        assertTrue(reasons.get(0), reasons.get(0).contains("should be C871"));
    }

    // row 6: not a Raast QR
    @Test
    public void textThatIsNotARaastQrIsRefused6A80() {
        select();
        assertEquals(Apdu.SW_BAD_DATA, sw(PhoneFixtures.spec("MR:4210112345671|Amount:100").get(0)));
        assertTrue(accepted.isEmpty());
        assertEquals(1, rejected.size());
        assertEquals("does not start with 000201", reasons.get(0));
    }

    // row 7: PUSH before SELECT
    @Test
    public void pushBeforeSelectIs6985AndNothingIsAcceptedOrRejected() {
        assertEquals(Apdu.SW_CONDITIONS, sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0)));
        assertTrue(accepted.isEmpty());
        assertTrue(rejected.isEmpty());
    }

    // row 8: class and instruction
    @Test
    public void wrongClassAndInstructionAreRefused() {
        select();
        assertEquals(0x6E00, sw(new byte[] {0x00, (byte) 0xCA, 0x03, 0x00, 0x01, 0x30}));
        assertEquals(Apdu.SW_INS_NOT_SUPPORTED, sw(new byte[] {(byte) 0x80, (byte) 0xCB, 0x03, 0x00, 0x01, 0x30}));
        assertTrue(accepted.isEmpty());
    }

    // row 9: short APDUs, and PUSH-before-SELECT beating a wrong class as the NBP service does
    @Test
    public void shortApduIs6A80AndNoSelectBeatsTheClassCheck() {
        assertEquals(Apdu.SW_BAD_DATA, sw(new byte[] {(byte) 0x80, (byte) 0xCA, 0x03}));
        assertEquals(Apdu.SW_BAD_DATA, sw(null));
        assertEquals(Apdu.SW_CONDITIONS, sw(new byte[] {0x00, 0x00, 0x00, 0x00}));
    }

    // row 10: chunk out of sequence
    @Test
    public void chunkOutOfSequenceDropsTheBufferAndTheNextTransferWorks() {
        select();
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        assertEquals(Apdu.SW_OK, sw(pushes.get(0)));
        assertEquals(Apdu.SW_WRONG_P1P2, sw(pushes.get(2)));
        assertTrue(accepted.isEmpty());
        assertEquals(Apdu.SW_OK, sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0)));
        assertEquals(PhoneFixtures.STATIC, accepted.get(0));
    }

    // row 11: link lost mid-transfer
    @Test
    public void linkLostMidTransferDropsEverythingAndNeedsANewSelect() {
        select();
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        assertEquals(Apdu.SW_OK, sw(pushes.get(0)));
        r.onDeactivated(true);
        assertEquals(Apdu.SW_CONDITIONS, sw(pushes.get(1)));
        select();
        for (byte[] a : PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100)) assertEquals(Apdu.SW_OK, sw(a));
        assertEquals(1, accepted.size());
        assertEquals(PhoneFixtures.DYNAMIC, accepted.get(0));
    }

    // row 12: a second SELECT in the same field
    @Test
    public void secondSelectMidTransferStartsAgain() {
        select();
        assertEquals(Apdu.SW_OK, sw(PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100).get(0)));
        select();
        for (byte[] a : PhoneFixtures.spec(PhoneFixtures.STATIC, 0)) assertEquals(Apdu.SW_OK, sw(a));
        assertEquals(1, accepted.size());
        assertEquals(PhoneFixtures.STATIC, accepted.get(0));
    }

    // row 13: the same QR twice is two taps
    @Test
    public void theSameQrTwiceIsAcceptedTwice() {
        for (int i = 0; i < 2; i++) {
            select();
            assertEquals(Apdu.SW_OK, sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0)));
            r.onDeactivated(false);
        }
        assertEquals(2, accepted.size());
    }

    // row 14: a second transfer without a new SELECT, as the NBP service allows
    @Test
    public void selectionSurvivesACompleteTransfer() {
        select();
        assertEquals(Apdu.SW_OK, sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0)));
        assertEquals(Apdu.SW_OK, sw(PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100).get(0)));
        for (byte[] a : PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100).subList(1, 3)) {
            assertEquals(Apdu.SW_OK, sw(a));
        }
        assertEquals(2, accepted.size());
    }

    // row 15: bookkeeping never changes the answer
    @Test
    public void aListenerThatThrowsDoesNotChangeTheAnswer() {
        listenerThrows = true;
        select();
        assertEquals(Apdu.SW_OK, sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0)));
        assertEquals(Apdu.SW_BAD_DATA, sw(PhoneFixtures.spec("junk").get(0)));
        assertEquals(1, accepted.size());
        assertEquals(1, rejected.size());
        r.onDeactivated(false);
        select();
        r.onDeactivated(true);
        assertEquals("the incomplete callback was reached and its throw swallowed", 1, incomplete.size());
    }

    // row 16: oversized payload
    @Test
    public void payloadOver4096BytesIsRefusedWithoutAPayloadCallback() {
        select();
        StringBuilder big = new StringBuilder("000201");
        while (big.length() < 4200) big.append('0');
        int last = -1;
        for (byte[] a : PhoneFixtures.spec(big.toString(), 239)) {
            last = sw(a);
            if (last != Apdu.SW_OK) break;
        }
        assertEquals(Apdu.SW_BAD_DATA, last);
        assertTrue(accepted.isEmpty());
        assertTrue(rejected.isEmpty());
    }

    // row 17: UTF-8 payload reaches the listener as text
    @Test
    public void utf8MerchantNameSurvivesTheTransfer() {
        select();
        String qr = PhoneFixtures.resign("000201" + "0102" + "11" + SampleQr.tlv("59", "Café سلام"));
        byte[] data = qr.getBytes(StandardCharsets.UTF_8);
        assertEquals(Apdu.SW_OK, sw(PhoneFixtures.cmd(0x03, 0x00, data, 0, data.length)));
        assertEquals(qr, accepted.get(0));
        assertEquals("Café سلام", BankQr.of(accepted.get(0)).merchantName);
    }

    // ------------------------------------------------------------- session log

    // row 60: an accepted tap carries the whole conversation, final 9000 included
    @Test
    public void anAcceptedTapCarriesEveryCommandAndAnswerInOrder() {
        select();
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        for (byte[] a : pushes) sw(a);
        NfcSession s = acceptedSessions.get(0);
        assertNull("still open when reported", s.end);
        assertEquals(1 + pushes.size(), s.exchanges().size());
        assertArrayEquals(Apdu.SELECT, s.exchanges().get(0).command());
        assertArrayEquals(Apdu.withSw(Apdu.FCI, Apdu.SW_OK), s.exchanges().get(0).response());
        for (int i = 0; i < pushes.size(); i++) {
            assertArrayEquals(pushes.get(i), s.exchanges().get(i + 1).command());
            assertEquals(Apdu.SW_OK, Apdu.swOf(s.exchanges().get(i + 1).response()));
        }
        assertEquals(0, s.exchanges().get(0).atMs);
        assertTrue(s.exchanges().get(1).atMs > 0);
        assertEquals(1_700_000_000_000L + 5_000 + 7, s.startedWallMs);
    }

    // row 61: a refused QR carries the 6A80 that was sent
    @Test
    public void aRejectedTapCarriesTheSixA80() {
        select();
        sw(PhoneFixtures.spec("junk").get(0));
        NfcSession s = rejectedSessions.get(0);
        assertEquals(2, s.exchanges().size());
        assertEquals(Apdu.SW_BAD_DATA, Apdu.swOf(s.exchanges().get(1).response()));
    }

    // row 62: PUSH before SELECT, reported when the link drops
    @Test
    public void aPushWithoutSelectIsReportedAsIncompleteWhenTheLinkDrops() {
        sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0));
        assertTrue(incomplete.isEmpty());
        r.onDeactivated(true);
        assertEquals(1, incomplete.size());
        NfcSession s = incomplete.get(0);
        assertEquals("link lost", s.end);
        assertEquals(1, s.exchanges().size());
        assertEquals(Apdu.SW_CONDITIONS, Apdu.swOf(s.exchanges().get(0).response()));
    }

    // row 63: SELECT only
    @Test
    public void aSelectWithNoPushIsIncompleteAndSaysHowItEnded() {
        select();
        r.onDeactivated(false);
        assertEquals(1, incomplete.size());
        assertEquals("deselected", incomplete.get(0).end);
        assertEquals(1, incomplete.get(0).exchanges().size());
    }

    // row 64: a refused chunk then a good retry in the same field
    @Test
    public void aFailedAttemptFollowedByASuccessInTheSameSessionIsOneAcceptedTapShowingBoth() {
        select();
        List<byte[]> pushes = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        sw(pushes.get(0));
        assertEquals(Apdu.SW_WRONG_P1P2, sw(pushes.get(2)));
        for (byte[] a : pushes) sw(a);
        assertEquals(1, accepted.size());
        assertTrue(incomplete.isEmpty());
        assertEquals(1 + 2 + pushes.size(), acceptedSessions.get(0).exchanges().size());
    }

    // row 65: a new SELECT while a transfer is open
    @Test
    public void aNewSelectClosesTheOpenSessionAsIncompleteAndStartsAnother() {
        select();
        sw(PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100).get(0));
        select();
        assertEquals(1, incomplete.size());
        assertEquals("new SELECT", incomplete.get(0).end);
        assertEquals(2, incomplete.get(0).exchanges().size());
        for (byte[] a : PhoneFixtures.spec(PhoneFixtures.STATIC, 0)) sw(a);
        assertEquals(1, acceptedSessions.size());
        assertEquals("the new session starts at its own SELECT", 2, acceptedSessions.get(0).exchanges().size());
    }

    // row 66: an accepted tap is not reported again when the link drops
    @Test
    public void deactivationAfterAnAcceptedOrRejectedTapReportsNothingMore() {
        select();
        sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0));
        r.onDeactivated(false);
        select();
        sw(PhoneFixtures.spec("junk").get(0));
        r.onDeactivated(true);
        assertEquals(1, accepted.size());
        assertEquals(1, rejected.size());
        assertTrue(incomplete.isEmpty());
    }

    // row 67: two transfers in one session
    @Test
    public void twoTransfersWithoutANewSelectGiveTwoTapsEachWithTheLogSoFar() {
        select();
        sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0));
        sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0));
        assertEquals(2, acceptedSessions.size());
        assertEquals(2, acceptedSessions.get(0).exchanges().size());
        assertEquals(3, acceptedSessions.get(1).exchanges().size());
    }

    // row 68: a listener that throws leaves the log intact
    @Test
    public void aThrowingListenerDoesNotLoseTheNextTapsLog() {
        listenerThrows = true;
        select();
        sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0));
        r.onDeactivated(false);
        listenerThrows = false;
        select();
        sw(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0));
        assertEquals(2, accepted.size());
        assertEquals(2, acceptedSessions.get(1).exchanges().size());
    }

    // row 69: nothing open
    @Test
    public void deactivationWithNothingOpenReportsNothing() {
        r.onDeactivated(true);
        r.onDeactivated(false);
        assertTrue(incomplete.isEmpty());
        assertTrue(accepted.isEmpty());
    }

    // row 59: a failure inside the handler is answered 6F00 and still logged
    @Test
    public void everyAnswerIsLoggedEvenWhenTheCommandIsRefused() {
        select();
        sw(new byte[] {0x00, (byte) 0xCA, 0x03, 0x00, 0x01, 0x30});
        r.onDeactivated(true);
        NfcSession s = incomplete.get(0);
        assertEquals(2, s.exchanges().size());
        assertEquals(0x6E00, Apdu.swOf(s.exchanges().get(1).response()));
    }
}
