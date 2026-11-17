package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The taps the NFC QR Test App has received, newest first, kept as text between runs (an older format is dropped,
 * not converted). Also decides which tap is still waiting to be shown: the newest accepted one that no screen has shown yet and that is at most
 * {@link #PENDING_TTL_MS} old (the NBP app drops a tapped QR that is not paid within 5 minutes, so a later,
 * unrelated launch does not open a payment). Not thread-safe; callers synchronise.
 */
public final class TapHistory {
    public static final int MAX = 30;
    public static final long PENDING_TTL_MS = 5 * 60 * 1000L;
    private static final String VERSION = "v2";

    public enum Kind {
        /** A QR that passed the acceptance rule; the app opens for it. */
        ACCEPTED,
        /** A complete payload the acceptance rule refused (answered 6A80). */
        REJECTED,
        /** A session that ended without any complete payload: a broken, torn or empty tap. */
        INCOMPLETE
    }

    /** One tap: a complete QR (accepted or refused), or a session that never delivered one. */
    public static final class Entry {
        public final long id;
        /** Wall clock ms when it arrived. */
        public final long atMs;
        public final Kind kind;
        /** Why it was rejected or incomplete; null when accepted. */
        public final String reason;
        /** The QR text; empty for an incomplete session. */
        public final String payload;
        /** Every APDU of the session, or null when it could not be read back. */
        public final NfcSession session;

        Entry(long id, long atMs, Kind kind, String reason, String payload, NfcSession session) {
            this.id = id;
            this.atMs = atMs;
            this.kind = kind;
            this.reason = reason;
            this.payload = payload;
            this.session = session;
        }

        public boolean accepted() {
            return kind == Kind.ACCEPTED;
        }

        public BankQr qr() {
            return BankQr.of(payload);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private long lastId;
    private long shownId;

    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public Entry get(long id) {
        for (Entry e : entries) if (e.id == id) return e;
        return null;
    }

    public Entry add(long atMs, Kind kind, String reason, String payload, NfcSession session) {
        Entry e = new Entry(++lastId, atMs, kind, kind == Kind.ACCEPTED ? null : reason == null ? "rejected" : reason,
                payload == null ? "" : payload, session);
        entries.add(0, e);
        while (entries.size() > MAX) entries.remove(entries.size() - 1);
        return e;
    }

    /** The tap to open the details screen for now, or null. */
    public Entry pending(long nowMs) {
        for (Entry e : entries) {
            if (e.id <= shownId) break;
            if (e.accepted() && nowMs - e.atMs <= PENDING_TTL_MS && nowMs - e.atMs >= -PENDING_TTL_MS) return e;
        }
        return null;
    }

    /** A screen has shown this tap (and so every older one is stale). */
    public void markShown(long id) {
        if (id > shownId) shownId = id;
    }

    /** Empties the list. Ids keep counting so a stored "shown" id never matches a later tap. */
    public void clear() {
        entries.clear();
        shownId = lastId;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(VERSION).append('\t').append(lastId).append('\t').append(shownId).append('\n');
        for (Entry e : entries) {
            sb.append(e.id).append('\t').append(e.atMs).append('\t').append(kindCode(e.kind)).append('\t')
                    .append(hex(e.reason == null ? "" : e.reason)).append('\t').append(hex(e.payload)).append('\t')
                    .append(e.session == null ? "" : e.session.serialize()).append('\n');
        }
        return sb.toString();
    }

    /** Anything unreadable is skipped; a damaged store gives an empty history, never an exception. */
    public static TapHistory parse(String text) {
        TapHistory h = new TapHistory();
        if (text == null) return h;
        String[] lines = text.split("\n");
        if (lines.length == 0) return h;
        String[] head = lines[0].split("\t", -1);
        if (head.length != 3 || !VERSION.equals(head[0])) return h;
        try {
            h.lastId = Long.parseLong(head[1]);
            h.shownId = Long.parseLong(head[2]);
        } catch (NumberFormatException e) {
            return new TapHistory();
        }
        for (int i = 1; i < lines.length; i++) {
            Entry e = parseEntry(lines[i]);
            if (e != null && e.id <= h.lastId) h.entries.add(e);
        }
        Collections.sort(h.entries, (a, b) -> Long.compare(b.id, a.id));
        while (h.entries.size() > MAX) h.entries.remove(h.entries.size() - 1);
        return h;
    }

    private static Entry parseEntry(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 6) return null;
        try {
            long id = Long.parseLong(f[0]);
            long at = Long.parseLong(f[1]);
            Kind kind = kindOf(f[2]);
            if (kind == null) return null;
            String reason = unhex(f[3]);
            String payload = unhex(f[4]);
            if (reason == null || payload == null) return null;
            NfcSession session = f[5].isEmpty() ? null : NfcSession.parse(f[5]);
            return new Entry(id, at, kind, kind == Kind.ACCEPTED ? null : reason, payload, session);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static char kindCode(Kind k) {
        return k == Kind.ACCEPTED ? 'A' : k == Kind.REJECTED ? 'R' : 'I';
    }

    private static Kind kindOf(String code) {
        if ("A".equals(code)) return Kind.ACCEPTED;
        if ("R".equals(code)) return Kind.REJECTED;
        if ("I".equals(code)) return Kind.INCOMPLETE;
        return null;
    }

    private static String hex(String s) {
        return Hex.encode(s.getBytes(StandardCharsets.UTF_8)).replace(" ", "");
    }

    /** Null when the text is not valid hex. */
    private static String unhex(String s) {
        if (s.length() % 2 != 0 || !s.matches("[0-9A-Fa-f]*")) return null;
        return new String(Hex.decode(s), StandardCharsets.UTF_8);
    }
}
