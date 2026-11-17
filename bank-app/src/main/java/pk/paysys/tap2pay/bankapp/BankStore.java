package pk.paysys.tap2pay.bankapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.List;

import pk.paysys.tap2pay.core.NfcSession;
import pk.paysys.tap2pay.core.TapHistory;

/**
 * The taps received, shared by the HCE service and the screen. The service can run with no activity alive
 * (the app is closed), so the list lives in preferences and is the only way a tap reaches the screen: the
 * screen reads {@link #pending()} when it comes to the front. All methods run on the main thread
 * (HostApduService and the activity both do), and are synchronised anyway.
 */
final class BankStore {
    static final String TAG = "Tap2PayBank";
    private static final String PREFS = "bank_taps";
    private static final String KEY = "history";

    interface Listener {
        void onChanged();
    }

    private static BankStore instance;

    private final SharedPreferences prefs;
    private TapHistory history;
    private Listener listener;

    private BankStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        history = TapHistory.parse(prefs.getString(KEY, null));
    }

    static synchronized BankStore get(Context context) {
        if (instance == null) instance = new BankStore(context.getApplicationContext());
        return instance;
    }

    synchronized void setListener(Listener l) {
        listener = l;
    }

    synchronized List<TapHistory.Entry> entries() {
        return history.entries();
    }

    synchronized TapHistory.Entry get(long id) {
        return history.get(id);
    }

    /** The accepted tap no screen has shown yet, within the five-minute window, or null. */
    synchronized TapHistory.Entry pending() {
        return history.pending(System.currentTimeMillis());
    }

    synchronized TapHistory.Entry record(TapHistory.Kind kind, String payload, String reason, NfcSession session) {
        TapHistory.Entry e = history.add(System.currentTimeMillis(), kind, reason, payload, session);
        save();
        Log.d(TAG, "tap #" + e.id + " " + kind + (reason == null ? "" : ": " + reason) + ", " + e.payload.length()
                + " chars, " + (session == null ? 0 : session.exchanges().size()) + " APDU exchange(s)");
        if (listener != null) listener.onChanged();
        return e;
    }

    synchronized void markShown(long id) {
        history.markShown(id);
        save();
    }

    synchronized void clear() {
        history.clear();
        save();
        if (listener != null) listener.onChanged();
    }

    private void save() {
        prefs.edit().putString(KEY, history.serialize()).apply();
    }
}
