package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** POS tap-session rows (G1-G8): double press, late callbacks, stop and timeout racing a detection. */
public class TapGateTest {
    // G1
    @Test
    public void idleGateStartsASearch() {
        TapGate g = new TapGate();
        int id = g.tryStart();
        assertTrue(id > 0);
        assertTrue(g.isSearching());
        assertFalse(g.isExchanging());
    }

    // G2 (double tap on a button)
    @Test
    public void secondStartWhileSearchingIsRefused() {
        TapGate g = new TapGate();
        g.tryStart();
        assertEquals(0, g.tryStart());
    }

    // G3 (second press arriving while the first exchange is still running)
    @Test
    public void startIsRefusedDuringExchange() {
        TapGate g = new TapGate();
        int id = g.tryStart();
        assertTrue(g.detected(id));
        assertFalse(g.isSearching());
        assertTrue(g.isExchanging());
        assertEquals(0, g.tryStart());
    }

    // G4
    @Test
    public void finishedExchangeAllowsTheNextStartWithANewId() {
        TapGate g = new TapGate();
        int first = g.tryStart();
        g.detected(first);
        g.finished();
        int second = g.tryStart();
        assertTrue(second > 0);
        assertTrue(second != first);
    }

    // G5 (late card callback after Stop, and a stale callback from an earlier search)
    @Test
    public void detectionAfterStopOrFromAnOldSearchIsIgnored() {
        TapGate g = new TapGate();
        int first = g.tryStart();
        assertTrue(g.stopSearch());
        assertFalse(g.detected(first));
        assertFalse(g.isExchanging());

        int second = g.tryStart();
        assertFalse(g.detected(first));
        assertTrue(g.isSearching());
        assertTrue(g.detected(second));
    }

    // G6 (timeout racing a detection, and a stale timeout from an earlier search)
    @Test
    public void timeoutOnlyEndsTheSearchItWasPostedFor() {
        TapGate g = new TapGate();
        int first = g.tryStart();
        assertTrue(g.detected(first));
        assertFalse(g.timedOut(first));
        assertTrue(g.isExchanging());
        g.finished();

        int second = g.tryStart();
        assertFalse(g.timedOut(first));
        assertTrue(g.isSearching());
        assertTrue(g.timedOut(second));
        assertFalse(g.isSearching());
        assertFalse(g.timedOut(second));
    }

    // G7 (Stop pressed mid-exchange: the exchange keeps the RF until it finishes)
    @Test
    public void stopDuringExchangeDoesNotReleaseTheGate() {
        TapGate g = new TapGate();
        g.detected(g.tryStart());
        assertFalse(g.stopSearch());
        assertTrue(g.isExchanging());
        assertEquals(0, g.tryStart());
        g.finished();
        assertTrue(g.tryStart() > 0);
    }

    // G8 (search failure callback, and finished() with nothing running)
    @Test
    public void failureAndSpuriousFinishLeaveTheGateIdle() {
        TapGate g = new TapGate();
        g.finished();
        int id = g.tryStart();
        assertTrue(g.stopSearch());
        assertFalse(g.stopSearch());
        g.finished();
        assertFalse(g.isSearching());
        assertFalse(g.isExchanging());
        assertFalse(g.detected(id));
        assertTrue(g.tryStart() > 0);
    }
}
