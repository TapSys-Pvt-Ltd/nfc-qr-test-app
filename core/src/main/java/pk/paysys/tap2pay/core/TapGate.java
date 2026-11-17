package pk.paysys.tap2pay.core;

/**
 * Who owns the POS RF field: idle, searching (id = current search) or exchanging. Callbacks carry the id of the
 * search they belong to, so a late card or timeout callback from a stopped search is ignored. Thread-safe.
 */
public final class TapGate {
    private int current;
    private boolean searching;
    private boolean exchanging;

    /** Starts a search and returns its id (> 0), or 0 if a search or exchange is already running. */
    public synchronized int tryStart() {
        if (searching || exchanging) return 0;
        searching = true;
        return ++current;
    }

    /** Card found for search {@code id}: true if the caller now owns the exchange. */
    public synchronized boolean detected(int id) {
        if (!searching || id != current) return false;
        searching = false;
        exchanging = true;
        return true;
    }

    /** Timeout for search {@code id}: true if that search was still waiting and is now ended. */
    public synchronized boolean timedOut(int id) {
        if (!searching || id != current) return false;
        searching = false;
        return true;
    }

    /** Ends the current search (Stop, pause, search failure); true if one was waiting. Never ends an exchange. */
    public synchronized boolean stopSearch() {
        boolean was = searching;
        searching = false;
        return was;
    }

    public synchronized void finished() {
        exchanging = false;
    }

    public synchronized boolean isSearching() {
        return searching;
    }

    public synchronized boolean isExchanging() {
        return exchanging;
    }
}
