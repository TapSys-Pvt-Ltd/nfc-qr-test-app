package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CaseResultTest {
    private int number;

    private TapRecord tap(PhoneCase c, TapRecord.Verdict v, String reason) {
        TapRecord t = new TapRecord(++number, c, 0L, 0L);
        t.verdict = v;
        t.reason = reason;
        return t;
    }

    // A case with no taps: NOT RUN, nothing to show in Details
    @Test
    public void notRunWhenTheCaseHasNoTaps() {
        List<TapRecord> taps = Collections.singletonList(tap(PhoneCase.ACCEPT, TapRecord.Verdict.PASS, "ok"));
        CaseResult r = CaseResult.of(taps, PhoneCase.REJECT_DATA);
        assertEquals(CaseResult.Status.NOT_RUN, r.status);
        assertNull(r.latest());
        assertTrue(r.taps.isEmpty());
        assertEquals("", r.tally());
        assertEquals("No tap yet.", r.explanation());
    }

    // Row 46: the case picked for the next tap with no taps yet shows the phone is waiting for a tap
    @Test
    public void selectedCaseWithNoTapsIsWaiting() {
        List<TapRecord> taps = Collections.singletonList(tap(PhoneCase.ACCEPT, TapRecord.Verdict.PASS, "ok"));
        CaseResult r = CaseResult.of(taps, PhoneCase.DQRC_WITH_AMOUNT, true);
        assertEquals(CaseResult.Status.WAITING, r.status);
        assertEquals("WAITING FOR TAP", r.status.label);
        assertEquals("Waiting for a tap: hold this phone on the POS reader.", r.explanation());
        assertEquals("", r.tally());
        assertNull(r.latest());
        // the same case not selected stays NOT RUN; the two-argument form means not selected
        assertEquals(CaseResult.Status.NOT_RUN, CaseResult.of(taps, PhoneCase.DQRC_WITH_AMOUNT, false).status);
        assertEquals(CaseResult.Status.NOT_RUN, CaseResult.of(taps, PhoneCase.DQRC_WITH_AMOUNT).status);
    }

    // Row 47: selected and already run: the latest tap decides, never WAITING (in progress, pass, fail, check POS)
    @Test
    public void selectedCaseWithTapsShowsItsLatestTap() {
        for (TapRecord.Verdict v : TapRecord.Verdict.values()) {
            TapRecord t = tap(PhoneCase.ACCEPT, v, "r");
            CaseResult r = CaseResult.of(list(t), PhoneCase.ACCEPT, true);
            assertEquals(v.toString(), CaseResult.status(t), r.status);
            assertTrue(v.toString(), r.status != CaseResult.Status.WAITING && r.status != CaseResult.Status.NOT_RUN);
        }
    }

    // Row 48: Clear results while a case is selected: it goes back to WAITING FOR TAP, the others to NOT RUN
    @Test
    public void afterClearTheSelectedCaseWaitsAgain() {
        List<TapRecord> cleared = Collections.emptyList();
        for (PhoneCase c : PhoneCase.values()) {
            boolean selected = c == PhoneCase.SQRC_NO_AMOUNT;
            assertEquals(c.name(), selected ? CaseResult.Status.WAITING : CaseResult.Status.NOT_RUN,
                    CaseResult.of(cleared, c, selected).status);
        }
    }

    // Each verdict maps to its badge; only taps of this case count, newest first
    @Test
    public void latestTapOfThisCaseDecidesTheBadge() {
        Object[][] rows = {
                {TapRecord.Verdict.OPEN, CaseResult.Status.IN_PROGRESS},
                {TapRecord.Verdict.PASS, CaseResult.Status.PASS},
                {TapRecord.Verdict.FAIL, CaseResult.Status.FAIL},
                {TapRecord.Verdict.DONE, CaseResult.Status.CHECK_POS},
        };
        for (Object[] row : rows) {
            TapRecord other = tap(PhoneCase.ACCEPT, TapRecord.Verdict.FAIL, "other case");
            TapRecord newest = tap(PhoneCase.REJECT_DATA, (TapRecord.Verdict) row[0], "x");
            TapRecord older = tap(PhoneCase.REJECT_DATA, TapRecord.Verdict.PASS, "y");
            CaseResult r = CaseResult.of(Arrays.asList(other, newest, older), PhoneCase.REJECT_DATA);
            assertEquals(row[0].toString(), row[1], r.status);
            assertSame(newest, r.latest());
            assertEquals(Arrays.asList(newest, older), r.taps);
        }
    }

    @Test
    public void explanationTellsTheTesterWhatIsLeftToCheck() {
        PhoneCase c = PhoneCase.REJECT_DATA;
        assertEquals("Phone refused it. The phone's part is done: now check the POS shows: " + c.checkOnPos,
                CaseResult.of(list(tap(c, TapRecord.Verdict.DONE, "phone refused it")), c).explanation());
        assertEquals("Valid dynamic QR received and accepted. Also check the POS shows: "
                        + PhoneCase.ACCEPT.checkOnPos,
                CaseResult.of(list(tap(PhoneCase.ACCEPT, TapRecord.Verdict.PASS,
                        "valid dynamic QR received and accepted")), PhoneCase.ACCEPT).explanation());
        assertEquals("Accepted, but expected static QR without amount, got dynamic QR.",
                CaseResult.of(list(tap(PhoneCase.SQRC_NO_AMOUNT, TapRecord.Verdict.FAIL,
                        "accepted, but expected static QR without amount, got dynamic QR")),
                        PhoneCase.SQRC_NO_AMOUNT).explanation());
        // a reason that already ends with a full stop does not get a second one
        assertEquals("Bad.", CaseResult.of(list(tap(c, TapRecord.Verdict.FAIL, "bad.")), c).explanation());
        TapRecord open = tap(c, TapRecord.Verdict.OPEN, "in progress");
        assertEquals("Tap #" + open.number + " in progress.", CaseResult.of(list(open), c).explanation());
    }

    @Test
    public void tallyCountsPassAndFailOfThisCaseOnly() {
        PhoneCase c = PhoneCase.DQRC_WITH_AMOUNT;
        TapRecord one = tap(c, TapRecord.Verdict.PASS, "a");
        assertEquals("Tap #" + one.number, CaseResult.of(list(one), c).tally());

        TapRecord older = tap(c, TapRecord.Verdict.PASS, "a");
        TapRecord mid = tap(c, TapRecord.Verdict.FAIL, "b");
        TapRecord ignored = tap(PhoneCase.ACCEPT, TapRecord.Verdict.FAIL, "c");
        TapRecord newest = tap(c, TapRecord.Verdict.PASS, "d");
        CaseResult r = CaseResult.of(Arrays.asList(newest, ignored, mid, older), c);
        assertEquals(2, r.passed);
        assertEquals(1, r.failed);
        assertEquals("Tap #" + newest.number + " · 3 taps: 2 PASS, 1 FAIL", r.tally());

        TapRecord d1 = tap(PhoneCase.REJECT_DATA, TapRecord.Verdict.DONE, "x");
        TapRecord d2 = tap(PhoneCase.REJECT_DATA, TapRecord.Verdict.DONE, "x");
        assertEquals("Tap #" + d2.number + " · 2 taps", CaseResult.of(Arrays.asList(d2, d1), PhoneCase.REJECT_DATA).tally());
    }

    // The real responder: a tap made while case A was selected stays under A after the case changes
    @Test
    public void tapStaysWithTheCaseReadAtSelect() {
        final PhoneCase[] current = {PhoneCase.REJECT_DATA};
        final List<TapRecord> taps = new ArrayList<>();
        PhoneResponder r = new PhoneResponder(new PhoneResponder.Clock() {
            @Override
            public long monotonicMs() {
                return 1000L;
            }

            @Override
            public long wallMs() {
                return 1000L;
            }
        }, () -> current[0], new PhoneResponder.Listener() {
            @Override
            public void onTapChanged(TapRecord tap) {
                if (!taps.contains(tap)) taps.add(0, tap);
            }

            @Override
            public void onTapClosed(TapRecord tap) {
                onTapChanged(tap);
            }
        });
        r.onApdu(Apdu.SELECT);
        current[0] = PhoneCase.ACCEPT;
        for (byte[] p : PhoneFixtures.spec(PhoneFixtures.DYNAMIC)) r.onApdu(p);
        r.onDeactivated(true);
        assertEquals(CaseResult.Status.CHECK_POS, CaseResult.of(taps, PhoneCase.REJECT_DATA).status);
        assertEquals(CaseResult.Status.NOT_RUN, CaseResult.of(taps, PhoneCase.ACCEPT).status);
    }

    private static List<TapRecord> list(TapRecord t) {
        return Collections.singletonList(t);
    }
}
