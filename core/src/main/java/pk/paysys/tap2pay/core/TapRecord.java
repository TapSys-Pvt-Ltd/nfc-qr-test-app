package pk.paysys.tap2pay.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One tap as the phone saw it: every APDU in and out, the QR, and the result. Written by {@link PhoneResponder}. */
public final class TapRecord {
    public enum Verdict {
        /** Tap still in progress. */
        OPEN,
        /** The case's expected outcome happened. */
        PASS,
        /** Something went wrong in the exchange (the POS or the link, not the injected fault). */
        FAIL,
        /** The fault was injected; whether the POS handled it is checked on the POS screen. */
        DONE
    }

    /** One APDU in either direction. */
    public static final class Exchange {
        public final long atMs;
        public final boolean fromPos;
        public final String summary;
        public final String hex;

        Exchange(long atMs, boolean fromPos, String summary, String hex) {
            this.atMs = atMs;
            this.fromPos = fromPos;
            this.summary = summary;
            this.hex = hex;
        }
    }

    public final int number;
    public final PhoneCase phoneCase;
    public final long startedWallMs;
    final long startedMs;
    final List<Exchange> exchanges = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    PushAssembler.Form form = PushAssembler.Form.NONE;
    int chunks;
    RaastQr qr;
    Verdict verdict = Verdict.OPEN;
    String reason = "in progress";
    long durationMs;

    TapRecord(int number, PhoneCase phoneCase, long startedWallMs, long startedMs) {
        this.number = number;
        this.phoneCase = phoneCase;
        this.startedWallMs = startedWallMs;
        this.startedMs = startedMs;
    }

    public Verdict verdict() {
        return verdict;
    }

    public String reason() {
        return reason;
    }

    public PushAssembler.Form form() {
        return form;
    }

    public RaastQr qr() {
        return qr;
    }

    public List<Exchange> exchanges() {
        return Collections.unmodifiableList(exchanges);
    }

    public List<String> notes() {
        return Collections.unmodifiableList(notes);
    }

    /** "spec (P1 03, as the Urovo app sends)" etc. */
    public static String formName(PushAssembler.Form f) {
        switch (f) {
            case SPEC: return "spec header, P1 03 (Urovo app form)";
            case INDEX: return "index header, P1 00/01 (Sunmi Mindcrafts form)";
            default: return "no PUSH received";
        }
    }

    /** Text for the screen; {@code fullHex} adds every byte of every APDU (for the log file). */
    public String describe(boolean fullHex) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tap #").append(number).append("  ").append(verdict).append(": ").append(reason).append('\n');
        sb.append("Case: ").append(phoneCase.label).append('\n');
        if (phoneCase.checksQrKind()) sb.append("Expected: ").append(phoneCase.expectedKind()).append('\n');
        sb.append("Header: ").append(formName(form));
        if (chunks > 0) sb.append(", ").append(chunks).append(chunks == 1 ? " APDU" : " chunks");
        sb.append('\n');
        if (verdict != Verdict.OPEN) sb.append("Duration: ").append(durationMs).append(" ms\n");
        if (qr != null) sb.append(qr.describe()).append('\n');
        for (String n : notes) sb.append("Note: ").append(n).append('\n');
        if (verdict == Verdict.DONE || verdict == Verdict.PASS) {
            sb.append("Check on POS: ").append(phoneCase.checkOnPos).append('\n');
        }
        sb.append("Communication:\n");
        for (Exchange e : exchanges) {
            sb.append(String.format("  +%4d ms %s %s%n", e.atMs, e.fromPos ? "POS->phone" : "phone->POS", e.summary));
            if (fullHex) sb.append("           ").append(e.hex).append('\n');
        }
        return sb.toString();
    }
}
