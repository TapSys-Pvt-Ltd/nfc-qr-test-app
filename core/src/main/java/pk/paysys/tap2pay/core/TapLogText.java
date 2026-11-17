package pk.paysys.tap2pay.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The log of a tap as plain text, for the "Copy log" buttons: what the screen shows, section by section (1 NFC
 * communication, 2 QR validation, 3 data extracted), with the raw bytes and the raw QR, so it can be pasted into a
 * chat or a ticket. Built only from the saved entry, so the text is the same whether the tap was just received or
 * is read back from the store. Never throws for a damaged or old entry: a missing part is said to be missing.
 */
public final class TapLogText {
    public static final String TITLE = "NFC QR Test App";

    private TapLogText() {}

    /** One tap. {@code zone} is the time zone the times are written in. */
    public static String of(TapHistory.Entry e, TimeZone zone) {
        StringBuilder out = new StringBuilder();
        append(out, e, zone);
        return out.toString();
    }

    /** All the taps, newest first as given, separated by a rule. */
    public static String all(List<TapHistory.Entry> entries, TimeZone zone) {
        StringBuilder out = new StringBuilder(TITLE).append(" - ").append(entries.size())
                .append(entries.size() == 1 ? " tap" : " taps").append('\n');
        for (TapHistory.Entry e : entries) {
            out.append("\n--------------------------------\n\n");
            append(out, e, zone);
        }
        return out.toString();
    }

    /** What the home list calls the tap: RECEIVED, REJECTED or INCOMPLETE. */
    public static String kindLabel(TapHistory.Kind kind) {
        return kind == TapHistory.Kind.ACCEPTED ? "RECEIVED" : kind.name();
    }

    private static void append(StringBuilder out, TapHistory.Entry e, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(zone);
        out.append(TITLE).append(" - tap log\n");
        out.append("Tap #").append(e.id).append("  ").append(f.format(new Date(e.atMs))).append("  ")
                .append(kindLabel(e.kind)).append('\n');
        if (e.reason != null) out.append("Reason: ").append(e.reason).append('\n');

        NfcReport nfc = NfcReport.of(e.session);
        out.append("\n1. NFC COMMUNICATION  ").append(mark(nfc == null ? null : nfc.status)).append('\n');
        if (nfc == null) {
            out.append("   no NFC log was saved for this tap\n");
        } else {
            out.append("   ").append(nfc.summary).append('\n');
            out.append("   Session\n");
            for (NfcReport.Field fld : nfc.session) field(out, "      ", fld.name, fld.expected, fld.got, fld.status, fld.note);
            for (NfcReport.Step s : nfc.steps) {
                out.append("   Step ").append(s.number).append(": ").append(s.title).append("  +").append(s.atMs)
                        .append(" ms  ").append(mark(s.status())).append('\n');
                for (NfcReport.Field fld : s.fields) {
                    field(out, "      ", fld.name, fld.expected, fld.got, fld.status, fld.note);
                }
                out.append("      POS sent:        ").append(s.command).append('\n');
                out.append("      Phone answered:  ").append(s.response).append('\n');
                if (s.data != null && !s.data.isEmpty()) out.append("      Data as text:    ").append(s.data).append('\n');
            }
        }

        boolean hasQr = e.payload != null && !e.payload.isEmpty();
        QrReport qr = hasQr ? QrReport.of(e.payload) : null;
        out.append("\n2. QR VALIDATION  ").append(mark(qr == null ? null : qr.status)).append('\n');
        if (qr == null) {
            out.append("   no QR reached the phone\n");
        } else {
            out.append("   ").append(qr.summary).append('\n');
            for (QrReport.Group g : qr.groups) {
                out.append("   ").append(g.title).append('\n');
                for (QrReport.Row r : g.rows) {
                    String name = (r.tag == null || r.tag.isEmpty() ? "" : r.tag + " ") + (r.element == null ? "" : r.element);
                    field(out, "      ", name.trim(), r.expected, r.got, r.status, r.note);
                }
                if (g.absentOptional > 0) {
                    out.append("      (").append(g.absentOptional).append(" optional tags absent)\n");
                }
            }
            out.append("   Raw QR: ").append(e.payload).append('\n');
        }

        out.append("\n3. DATA EXTRACTED\n");
        if (!e.accepted() || !hasQr) {
            out.append("   none: the tap was ").append(kindLabel(e.kind).toLowerCase(Locale.US)).append('\n');
        } else {
            BankQr q = e.qr();
            out.append("   QR type: ").append(q.type == null ? "-" : q.type).append('\n');
            for (BankQr.Check c : q.checks()) {
                out.append("   [").append(c.status.name()).append("] ").append(c.label);
                if (c.detail != null && !c.detail.isEmpty()) out.append(": ").append(c.detail);
                out.append('\n');
            }
            for (BankQr.Section s : q.sections()) {
                out.append("   ").append(s.title).append('\n');
                for (BankQr.Row r : s.rows) {
                    out.append("      ").append(r.label);
                    if (r.ref != null && !r.ref.isEmpty()) out.append(" (").append(r.ref).append(')');
                    out.append(": ").append(r.present() ? r.value : "-").append('\n');
                }
            }
        }
    }

    private static void field(StringBuilder out, String indent, String name, String expected, String got,
                              BankQr.Status status, String note) {
        out.append(indent).append(mark(status)).append("  ").append(name).append(": expected ")
                .append(expected == null ? "-" : expected).append(" | got ").append(got == null ? "-" : got);
        if (note != null && !note.isEmpty()) out.append(" | ").append(note);
        out.append('\n');
    }

    private static String mark(BankQr.Status s) {
        return s == null ? "[--]" : "[" + s.name() + "]";
    }
}
