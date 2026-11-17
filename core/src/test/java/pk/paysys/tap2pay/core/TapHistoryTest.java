package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** One test per TapHistory and UnlockRetry row of bank-app/README.md (rows named in each test). */
public class TapHistoryTest {
    private static final long T0 = 1_700_000_000_000L;
    private static final String QR = PhoneFixtures.STATIC;

    private static NfcSession session() {
        List<NfcSession.Exchange> ex = new ArrayList<>();
        ex.add(new NfcSession.Exchange(0, Apdu.SELECT, Apdu.withSw(Apdu.FCI, Apdu.SW_OK)));
        ex.add(new NfcSession.Exchange(12, PhoneFixtures.spec(QR).get(0), Apdu.sw(Apdu.SW_OK)));
        return new NfcSession(T0, ex, null);
    }

    private static TapHistory.Entry accepted(TapHistory h, long at) {
        return h.add(at, TapHistory.Kind.ACCEPTED, null, QR, session());
    }

    private static TapHistory.Entry rejected(TapHistory h, long at) {
        return h.add(at, TapHistory.Kind.REJECTED, "does not start with 000201", "junk", session());
    }

    private static TapHistory.Entry incomplete(TapHistory h, long at) {
        return h.add(at, TapHistory.Kind.INCOMPLETE, "the POS sent no PUSH after the SELECT", "", session());
    }

    // row 30
    @Test
    public void newestFirstWithIncreasingIds() {
        TapHistory h = new TapHistory();
        TapHistory.Entry a = accepted(h, T0);
        TapHistory.Entry b = rejected(h, T0 + 1);
        assertTrue(b.id > a.id);
        assertEquals(b.id, h.entries().get(0).id);
        assertEquals(a.id, h.entries().get(1).id);
        assertEquals(a.id, h.get(a.id).id);
        assertNull(h.get(999));
        assertNull(a.reason);
        assertTrue(a.accepted());
        assertFalse(b.accepted());
        assertEquals("does not start with 000201", b.reason);
    }

    // row 31
    @Test
    public void keepsOnlyTheNewestThirty() {
        TapHistory h = new TapHistory();
        TapHistory.Entry first = accepted(h, T0);
        for (int i = 1; i <= TapHistory.MAX; i++) accepted(h, T0 + i);
        assertEquals(TapHistory.MAX, h.entries().size());
        assertNull(h.get(first.id));
        assertEquals(TapHistory.MAX + 1, h.entries().get(0).id);
    }

    // row 32: which tap opens the details screen
    @Test
    public void aFreshAcceptedTapIsPendingUntilItIsShown() {
        TapHistory h = new TapHistory();
        assertNull(h.pending(T0));
        TapHistory.Entry e = accepted(h, T0);
        assertEquals(e.id, h.pending(T0 + 1000).id);
        h.markShown(e.id);
        assertNull(h.pending(T0 + 1000));
    }

    @Test
    public void aRejectedOrIncompleteTapIsNeverPending() {
        TapHistory h = new TapHistory();
        rejected(h, T0);
        incomplete(h, T0 + 1);
        assertNull(h.pending(T0 + 2));
    }

    // row 33: the five-minute rule of the NBP app
    @Test
    public void aTapOlderThanFiveMinutesIsNotPending() {
        TapHistory h = new TapHistory();
        TapHistory.Entry e = accepted(h, T0);
        assertNotNull(h.pending(T0 + TapHistory.PENDING_TTL_MS));
        assertNull(h.pending(T0 + TapHistory.PENDING_TTL_MS + 1));
        assertEquals(e.id, h.entries().get(0).id);
    }

    @Test
    public void aClockSetBackDoesNotMakeAFutureTapPendingForever() {
        TapHistory h = new TapHistory();
        accepted(h, T0 + 10 * 60 * 1000L);
        assertNull(h.pending(T0));
    }

    // row 34: two taps while the phone was locked
    @Test
    public void onlyTheNewestUnshownAcceptedTapIsPendingAndShowingItRetiresTheOlderOnes() {
        TapHistory h = new TapHistory();
        TapHistory.Entry older = accepted(h, T0);
        TapHistory.Entry newer = accepted(h, T0 + 5);
        assertEquals(newer.id, h.pending(T0 + 10).id);
        h.markShown(newer.id);
        assertNull(h.pending(T0 + 10));
        assertNotNull(h.get(older.id));
    }

    @Test
    public void aRejectedTapAfterAnAcceptedOneDoesNotHideIt() {
        TapHistory h = new TapHistory();
        TapHistory.Entry a = accepted(h, T0);
        rejected(h, T0 + 5);
        incomplete(h, T0 + 6);
        assertEquals(a.id, h.pending(T0 + 10).id);
    }

    @Test
    public void markingAnOlderTapShownDoesNotUndoANewerOne() {
        TapHistory h = new TapHistory();
        TapHistory.Entry a = accepted(h, T0);
        TapHistory.Entry b = accepted(h, T0 + 1);
        h.markShown(b.id);
        h.markShown(a.id);
        assertNull(h.pending(T0 + 2));
    }

    // row 35
    @Test
    public void clearEmptiesTheListAndTheNextTapIsStillPending() {
        TapHistory h = new TapHistory();
        TapHistory.Entry a = accepted(h, T0);
        h.clear();
        assertTrue(h.entries().isEmpty());
        assertNull(h.pending(T0 + 1));
        TapHistory.Entry b = accepted(h, T0 + 2);
        assertTrue(b.id > a.id);
        assertEquals(b.id, h.pending(T0 + 3).id);
    }

    // row 36: the process was killed after the tap
    @Test
    public void roundTripKeepsEntriesSessionsPendingAndShownState() {
        TapHistory h = new TapHistory();
        h.add(T0, TapHistory.Kind.ACCEPTED, null, "tab\there\nnewline éس", session());
        TapHistory.Entry r = h.add(T0 + 1, TapHistory.Kind.REJECTED, "reason\twith\ttabs", "", session());
        TapHistory.Entry i = h.add(T0 + 2, TapHistory.Kind.INCOMPLETE, "torn", "", null);

        TapHistory back = TapHistory.parse(h.serialize());
        assertEquals(3, back.entries().size());
        TapHistory.Entry a = back.entries().get(2);
        assertEquals("tab\there\nnewline éس", a.payload);
        assertEquals(TapHistory.Kind.ACCEPTED, a.kind);
        assertEquals(T0, a.atMs);
        assertNotNull(a.session);
        assertEquals(2, a.session.exchanges().size());
        TapHistory.Entry rr = back.entries().get(1);
        assertEquals(r.id, rr.id);
        assertEquals(TapHistory.Kind.REJECTED, rr.kind);
        assertEquals("reason\twith\ttabs", rr.reason);
        TapHistory.Entry ii = back.entries().get(0);
        assertEquals(i.id, ii.id);
        assertEquals(TapHistory.Kind.INCOMPLETE, ii.kind);
        assertNull(ii.session);
        assertEquals(a.id, back.pending(T0 + 3).id);

        back.markShown(a.id);
        assertNull(TapHistory.parse(back.serialize()).pending(T0 + 3));
        TapHistory.Entry next = accepted(TapHistory.parse(back.serialize()), T0 + 4);
        assertTrue(next.id > ii.id);
    }

    // row 37: a damaged store
    @Test
    public void damagedTextGivesAnEmptyHistoryNeverAnException() {
        for (String junk : new String[] {null, "", "\n", "garbage", "v3\t1\t0\n", "v2\tx\t0\n", "v2\t1\n",
                "v2\t-\t-\n\t\t\t"}) {
            TapHistory h = TapHistory.parse(junk);
            assertTrue(String.valueOf(junk), h.entries().isEmpty());
            assertNull(h.pending(T0));
            assertEquals(1, accepted(h, T0).id);
        }
    }

    @Test
    public void aDamagedLineIsSkippedAndTheRestKept() {
        TapHistory h = new TapHistory();
        accepted(h, T0);
        accepted(h, T0 + 1);
        String[] lines = h.serialize().split("\n");
        String bad = lines[0] + "\n" + lines[1] + "\nnot a line\n1\t2\tX\t\t\t\n1\t2\tA\tZZ\t\t\n1\t2\tA\t\t4\t\n"
                + lines[2] + "\n";
        TapHistory back = TapHistory.parse(bad);
        assertEquals(2, back.entries().size());
        assertEquals(2, back.entries().get(0).id);
        assertEquals(1, back.entries().get(1).id);
    }

    @Test
    public void aSessionThatCannotBeReadBackLeavesTheTapWithoutALog() {
        TapHistory h = new TapHistory();
        accepted(h, T0);
        String[] lines = h.serialize().split("\n");
        String[] f = lines[1].split("\t", -1);
        f[5] = "not;a;session";
        TapHistory back = TapHistory.parse(lines[0] + "\n" + String.join("\t", f) + "\n");
        assertEquals(1, back.entries().size());
        assertNull(back.entries().get(0).session);
        assertEquals(QR, back.entries().get(0).payload);
    }

    // row 39: the previous store format is dropped, not mis-read
    @Test
    public void theVersion1FormatIsIgnored() {
        String v1 = "v1\t1\t0\n1\t1700000000000\tA\tSPEC\t1\t\t" + hex(QR) + "\n";
        TapHistory h = TapHistory.parse(v1);
        assertTrue(h.entries().isEmpty());
        assertEquals(1, accepted(h, T0).id);
    }

    private static String hex(String s) {
        return Hex.encode(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)).replace(" ", "");
    }

    @Test
    public void entriesWithAnIdAboveTheCounterAreDropped() {
        TapHistory h = new TapHistory();
        accepted(h, T0);
        String text = h.serialize() + "9\t5\tA\t\t\t\n";
        assertEquals(1, TapHistory.parse(text).entries().size());
    }

    @Test
    public void aStoreWithMoreThanThirtyLinesIsTrimmedToTheNewest() {
        StringBuilder sb = new StringBuilder("v2\t50\t0\n");
        for (int i = 1; i <= 50; i++) sb.append(i).append("\t").append(T0).append("\tA\t\t\t\n");
        TapHistory h = TapHistory.parse(sb.toString());
        assertEquals(TapHistory.MAX, h.entries().size());
        assertEquals(50, h.entries().get(0).id);
        assertEquals(21, h.entries().get(TapHistory.MAX - 1).id);
    }

    @Test
    public void entryBuildsItsQrDetails() {
        TapHistory h = new TapHistory();
        assertEquals("Test Merchant", accepted(h, T0).qr().merchantName);
    }

    // row 38: unlock retry
    @Test
    public void onlyAnEarlyCancelOnALockedPhoneIsRetriedAndOnlyOnce() {
        assertTrue(UnlockRetry.isSpuriousCancel(300, false, true));
        assertTrue(UnlockRetry.isSpuriousCancel(0, false, true));
        assertTrue(UnlockRetry.isSpuriousCancel(999, false, true));
        assertFalse(UnlockRetry.isSpuriousCancel(1000, false, true));
        assertFalse(UnlockRetry.isSpuriousCancel(300, true, true));
        assertFalse(UnlockRetry.isSpuriousCancel(300, false, false));
        assertFalse(UnlockRetry.isSpuriousCancel(-1, false, true));
    }
}
