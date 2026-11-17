package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The test phone's HCE logic, without Android: answers each APDU for the {@link PhoneCase} read at SELECT and
 * records the tap in a {@link TapRecord}. A tap opens at SELECT (or at a PUSH with no SELECT) and closes on
 * deactivation or the next SELECT. All calls must come from one thread (HostApduService calls on main).
 */
public final class PhoneResponder {
    public interface Clock {
        /** Monotonic ms (SystemClock.elapsedRealtime). */
        long monotonicMs();

        long wallMs();
    }

    public interface CaseSource {
        PhoneCase current();
    }

    public interface Listener {
        void onTapChanged(TapRecord tap);

        void onTapClosed(TapRecord tap);
    }

    public static final byte[] SELECT_HEADER = {0x00, (byte) 0xA4, 0x04, 0x00, 0x07};
    static final int SW_CLA_NOT_SUPPORTED = 0x6E00;
    private static final int SAME_QR_MEMORY = 50;

    private final Clock clock;
    private final CaseSource cases;
    private final Listener listener;
    private final PushAssembler assembler = new PushAssembler();
    private final Map<String, Integer> seenQr = new LinkedHashMap<>();

    private int taps;
    private TapRecord open;
    private boolean selected;
    private boolean protocolError;
    private String firstError;
    private int pushesInTransfer;

    public PhoneResponder(Clock clock, CaseSource cases, Listener listener) {
        this.clock = clock;
        this.cases = cases;
        this.listener = listener;
    }

    public TapRecord openTap() {
        return open;
    }

    /** The response to send now; every APDU is answered at once. */
    public byte[] onApdu(byte[] apdu) {
        long now = clock.monotonicMs();
        if (isSelect(apdu)) {
            if (open != null) close("POS sent a new SELECT without dropping the field");
            begin(now);
            log(now, true, apdu);
            selected = true;
            return answer(now, Apdu.withSw(Apdu.FCI, Apdu.SW_OK), null);
        }
        if (open == null) begin(now);
        log(now, true, apdu);
        if (apdu == null || apdu.length < 4) return error(now, Apdu.SW_BAD_DATA, "APDU shorter than 4 bytes");
        if (!selected) return error(now, Apdu.SW_CONDITIONS, "PUSH without SELECT");
        if ((apdu[0] & 0xFF) != 0x80) return error(now, SW_CLA_NOT_SUPPORTED, String.format("CLA %02X", apdu[0] & 0xFF));
        if ((apdu[1] & 0xFF) != 0xCA) return error(now, Apdu.SW_INS_NOT_SUPPORTED, String.format("INS %02X", apdu[1] & 0xFF));

        PushAssembler.Result r = assembler.push(apdu);
        if (r.form != PushAssembler.Form.NONE) open.form = r.form;
        if (r.sw != Apdu.SW_OK) {
            pushesInTransfer = 0;
            return error(now, r.sw, r.note);
        }
        pushesInTransfer = r.count;
        open.chunks = r.count;
        if (!r.complete()) return answer(now, Apdu.sw(Apdu.SW_OK), r.note);
        pushesInTransfer = 0;
        return onComplete(now, r);
    }

    private byte[] onComplete(long now, PushAssembler.Result r) {
        String text = new String(r.payload, StandardCharsets.UTF_8);
        open.qr = RaastQr.of(text);
        Integer before = seenQr.get(text);
        if (before != null) open.notes.add("same QR as tap #" + before);
        seenQr.remove(text);
        seenQr.put(text, open.number);
        if (seenQr.size() > SAME_QR_MEMORY) seenQr.remove(seenQr.keySet().iterator().next());

        if (open.phoneCase == PhoneCase.REJECT_DATA) {
            return answer(now, Apdu.sw(Apdu.SW_BAD_DATA), String.format("%04X sent (case)", Apdu.SW_BAD_DATA));
        }
        boolean ok = open.qr.valid;
        return answer(now, Apdu.sw(ok ? Apdu.SW_OK : Apdu.SW_BAD_DATA), ok ? r.note : "QR rejected");
    }

    /** RF link gone (HostApduService.onDeactivated). Returns the closed tap, or null when none was open. */
    public TapRecord onDeactivated(boolean linkLoss) {
        if (open == null) return null;
        return close(linkLoss ? "link lost" : "deselected");
    }

    private void begin(long now) {
        taps++;
        open = new TapRecord(taps, cases.current(), clock.wallMs(), now);
        selected = false;
        protocolError = false;
        firstError = null;
        pushesInTransfer = 0;
        assembler.reset();
    }

    private TapRecord close(String how) {
        TapRecord t = open;
        long now = clock.monotonicMs();
        t.durationMs = now - t.startedMs;
        t.notes.add("ended: " + how);
        decide(t);
        open = null;
        selected = false;
        assembler.reset();
        if (listener != null) listener.onTapClosed(t);
        return t;
    }

    private void decide(TapRecord t) {
        boolean midTransfer = pushesInTransfer > 0;
        String incomplete = midTransfer ? "link lost after chunk " + pushesInTransfer + ", QR never completed"
                : t.chunks == 0 && t.qr == null ? "POS sent no QR" : "QR never completed";
        if (protocolError) {
            set(t, TapRecord.Verdict.FAIL, firstError);
            return;
        }
        if (t.qr == null) {
            set(t, TapRecord.Verdict.FAIL, incomplete);
            return;
        }
        if (t.phoneCase == PhoneCase.REJECT_DATA) {
            set(t, TapRecord.Verdict.DONE, String.format("phone received the whole QR and refused it with %04X",
                    Apdu.SW_BAD_DATA));
            return;
        }
        if (!t.qr.valid) {
            set(t, TapRecord.Verdict.FAIL, "QR rejected: " + t.qr.problem);
            return;
        }
        // The phone already answered 9000 like the NBP app; a wrong QR kind is the POS's mistake, shown here.
        String mismatch = t.phoneCase.kindMismatch(t.qr);
        if (mismatch != null) set(t, TapRecord.Verdict.FAIL, "accepted, but " + mismatch);
        else if (t.phoneCase.checksQrKind()) {
            set(t, TapRecord.Verdict.PASS, "valid " + t.phoneCase.expectedKind() + " received and accepted");
        } else set(t, TapRecord.Verdict.PASS, "valid " + t.qr.type + " QR received and accepted");
    }

    private static void set(TapRecord t, TapRecord.Verdict v, String reason) {
        t.verdict = v;
        t.reason = reason;
    }

    private byte[] error(long now, int sw, String why) {
        protocolError = true;
        if (firstError == null) firstError = why;
        open.notes.add("error: " + why + " -> " + Apdu.swName(sw));
        return answer(now, Apdu.sw(sw), why);
    }

    private byte[] answer(long now, byte[] resp, String note) {
        logOut(now, resp, note);
        changed();
        return resp;
    }

    private void log(long now, boolean fromPos, byte[] apdu) {
        open.exchanges.add(new TapRecord.Exchange(now - open.startedMs, fromPos, commandSummary(apdu),
                apdu == null ? "" : Hex.encode(apdu)));
    }

    private void logOut(long now, byte[] resp, String note) {
        String data = resp.length > 2 ? Hex.encode(resp, 0, resp.length - 2) + "  " : "";
        String summary = data + Apdu.swName(Apdu.swOf(resp)) + (note != null ? "  (" + note + ")" : "");
        open.exchanges.add(new TapRecord.Exchange(now - open.startedMs, false, summary, Hex.encode(resp)));
    }

    private void changed() {
        if (listener != null && open != null) listener.onTapChanged(open);
    }

    static boolean isSelect(byte[] apdu) {
        if (apdu == null || apdu.length < SELECT_HEADER.length + Apdu.AID.length) return false;
        for (int i = 0; i < SELECT_HEADER.length; i++) if (apdu[i] != SELECT_HEADER[i]) return false;
        return Arrays.equals(Arrays.copyOfRange(apdu, 5, 5 + Apdu.AID.length), Apdu.AID);
    }

    /** "SELECT Tap2Pay AID" or header hex + Lc + the data as text (QR data is ASCII). */
    static String commandSummary(byte[] apdu) {
        if (apdu == null) return "(null)";
        if (isSelect(apdu)) return "SELECT F0010203030605  [" + Hex.encode(apdu) + "]";
        if (apdu.length < 5) return Hex.encode(apdu);
        StringBuilder sb = new StringBuilder(Hex.encode(apdu, 0, 4));
        int lc = apdu[4] & 0xFF;
        int off = 5;
        if (lc == 0 && apdu.length >= 8) {
            lc = ((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF);
            off = 7;
            sb.append("  Lc=").append(lc).append(" (extended)");
        } else {
            sb.append("  Lc=").append(lc);
        }
        int n = Math.max(0, Math.min(lc, apdu.length - off));
        if (n > 0) sb.append("  \"").append(printable(apdu, off, n)).append('"');
        if (apdu.length - off != lc) sb.append("  [").append(apdu.length - off).append(" data bytes]");
        return sb.toString();
    }

    private static String printable(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        return sb.toString();
    }
}
