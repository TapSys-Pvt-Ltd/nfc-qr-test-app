package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * An {@link NfcSession} judged field by field against what the POS should have sent (Tap2Pay via Raast
 * specification Clauses 3.2, 3.3, 3.7 and 3.8) and what the phone should have answered. Every command becomes a
 * {@link Step} whose {@link Field}s say what is expected, what was seen and whether it is fine:
 * <ul>
 *   <li>OK: as the spec says.</li>
 *   <li>WARN: the NBP app accepts it, but the spec does not (the Sunmi "index" header, a SELECT without Le, an
 *   oversized Lc) or the phone answers differently from the spec text (Lc 0 gets 6A80, not 6700).</li>
 *   <li>FAIL: the POS broke the protocol, or the transfer never completed.</li>
 * </ul>
 * The phone's own answers are compared with the spec's status word for the same defect, so a deviation of the NBP
 * logic shows up as a WARN on the "Phone answered" row, never as a failure of the POS.
 */
public final class NfcReport {
    public static final int T_CHUNK_GAP_MS = 2000;
    private static final int MAX_LC = 239;

    /** One expected-versus-seen line. */
    public static final class Field {
        public final String name;
        public final String expected;
        public final String got;
        public final BankQr.Status status;
        /** Why it is a WARN or FAIL; null when OK. */
        public final String note;

        Field(String name, String expected, String got, BankQr.Status status, String note) {
            this.name = name;
            this.expected = expected;
            this.got = got;
            this.status = status;
            this.note = note;
        }
    }

    /** One command from the POS and the phone's answer. */
    public static final class Step {
        public final int number;
        public final String title;
        public final long atMs;
        /** The command and the answer as spaced hex. */
        public final String command;
        public final String response;
        /** The data bytes of a PUSH as text (printable ASCII, '.' for the rest), or null. */
        public final String data;
        public final List<Field> fields;

        Step(int number, String title, long atMs, String command, String response, String data, List<Field> fields) {
            this.number = number;
            this.title = title;
            this.atMs = atMs;
            this.command = command;
            this.response = response;
            this.data = data;
            this.fields = Collections.unmodifiableList(fields);
        }

        public BankQr.Status status() {
            return worst(fields);
        }
    }

    public final List<Step> steps;
    /** Whole-session lines: SELECT first, transfer complete, header form, gaps, how it ended. */
    public final List<Field> session;
    public final BankQr.Status status;
    /** One line for a header chip. */
    public final String summary;

    private NfcReport(List<Step> steps, List<Field> session, BankQr.Status status, String summary) {
        this.steps = Collections.unmodifiableList(steps);
        this.session = Collections.unmodifiableList(session);
        this.status = status;
        this.summary = summary;
    }

    /** Null when there is no session (a tap saved without its log). */
    public static NfcReport of(NfcSession s) {
        if (s == null) return null;
        PushAssembler asm = new PushAssembler();
        boolean selected = false;
        PushAssembler.Form form = PushAssembler.Form.NONE;
        boolean firstIsSelect = false;
        int completions = 0;
        int lastBytes = 0;
        int lastApdus = 0;
        int lastAnswer = -1;
        int pushes = 0;
        long maxGap = 0;
        long prevAt = -1;
        List<Step> steps = new ArrayList<>();
        int n = 0;
        for (NfcSession.Exchange e : s.exchanges()) {
            n++;
            byte[] cmd = e.command();
            byte[] resp = e.response();
            if (n == 1) firstIsSelect = PhoneResponder.isSelect(cmd);
            if (prevAt >= 0) maxGap = Math.max(maxGap, e.atMs - prevAt);
            prevAt = e.atMs;
            if (PhoneResponder.isSelect(cmd)) {
                selected = true;
                asm.reset();
                steps.add(selectStep(n, e.atMs, cmd, resp));
                continue;
            }
            pushes++;
            PushStep ps = pushStep(n, e.atMs, cmd, resp, selected, asm);
            steps.add(ps.step);
            lastAnswer = swOf(resp);
            if (ps.result != null) {
                if (ps.result.form != PushAssembler.Form.NONE) form = ps.result.form;
                if (ps.result.complete()) {
                    completions++;
                    lastBytes = ps.result.payload.length;
                    lastApdus = ps.result.count;
                }
            }
        }

        List<Field> sess = new ArrayList<>();
        sess.add(firstIsSelect
                ? new Field("SELECT first", "SELECT F0010203030605 before any PUSH", "SELECT (step 1)", BankQr.Status.OK, null)
                : new Field("SELECT first", "SELECT F0010203030605 before any PUSH",
                        s.exchanges().isEmpty() ? "nothing" : "step 1 was not the SELECT", BankQr.Status.FAIL,
                        "the POS has to select the Tap2Pay AID first (Clause 3.2)"));
        if (completions > 0) {
            sess.add(new Field("QR delivered", "complete, at most " + PushAssembler.MAX_PAYLOAD + " bytes",
                    "complete: " + lastBytes + " bytes in " + lastApdus + (lastApdus == 1 ? " APDU" : " APDUs"),
                    BankQr.Status.OK, null));
        } else if (pushes == 0) {
            sess.add(new Field("QR delivered", "complete, at most " + PushAssembler.MAX_PAYLOAD + " bytes",
                    "the POS sent no PUSH after the SELECT", BankQr.Status.FAIL, "no QR reached the phone"));
        } else {
            sess.add(new Field("QR delivered", "complete, at most " + PushAssembler.MAX_PAYLOAD + " bytes",
                    "not completed, last answer " + sw(lastAnswer), BankQr.Status.FAIL, "no QR reached the phone"));
        }
        if (form != PushAssembler.Form.NONE) {
            sess.add(form == PushAssembler.Form.SPEC
                    ? new Field("Header form", "spec: P1 03", "spec (Urovo)", BankQr.Status.OK, null)
                    : new Field("Header form", "spec: P1 03", "index (Sunmi, P1 00 / 01)", BankQr.Status.WARN,
                            "not in the spec; this app accepts it"));
        }
        if (s.exchanges().size() > 1) {
            sess.add(new Field("Gap between APDUs", "at most " + T_CHUNK_GAP_MS + " ms (T_chunk_gap)",
                    "longest " + maxGap + " ms",
                    maxGap > T_CHUNK_GAP_MS ? BankQr.Status.WARN : BankQr.Status.OK,
                    maxGap > T_CHUNK_GAP_MS ? "the spec's phone would drop the buffer after this gap" : null));
        }
        if (s.end != null) sess.add(new Field("Session ended", "-", s.end, BankQr.Status.OK, null));

        List<Field> all = new ArrayList<>(sess);
        for (Step st : steps) all.addAll(st.fields);
        BankQr.Status overall = worst(all);
        return new NfcReport(steps, sess, overall, summary(overall, steps, sess, all, completions, pushes));
    }

    private static String summary(BankQr.Status overall, List<Step> steps, List<Field> sess, List<Field> all,
                                  int completions, int pushes) {
        if (overall == BankQr.Status.FAIL) {
            for (Field f : sess) if (f.status == BankQr.Status.FAIL) return f.name + ": " + f.got;
            for (Step st : steps) {
                for (Field f : st.fields) {
                    if (f.status == BankQr.Status.FAIL) {
                        return "step " + st.number + ", " + f.name + ": expected " + f.expected + ", got " + f.got;
                    }
                }
            }
        }
        int warns = 0;
        for (Field f : all) if (f.status == BankQr.Status.WARN) warns++;
        String base = "SELECT + " + pushes + (pushes == 1 ? " PUSH" : " PUSHes") + " answered";
        if (completions == 0) base = pushes + " PUSH, none completed";
        return warns == 0 ? base : base + ", " + warns + (warns == 1 ? " warning" : " warnings");
    }

    // ---------------------------------------------------------------- SELECT

    private static Step selectStep(int number, long atMs, byte[] c, byte[] resp) {
        List<Field> f = new ArrayList<>();
        f.add(byteField("CLA", 0x00, c[0] & 0xFF, "interindustry"));
        f.add(byteField("INS", 0xA4, c[1] & 0xFF, "SELECT"));
        f.add(new Field("P1 P2", "04 00", Hex.encode(c, 2, 2), eq(c, 2, new byte[] {0x04, 0x00}), "select by DF name"));
        f.add(byteField("Lc", 0x07, c[4] & 0xFF, "length of the AID"));
        f.add(new Field("Data (AID)", "F0 01 02 03 03 06 05", Hex.encode(c, 5, 7), BankQr.Status.OK, null));
        if (c.length > 12) {
            f.add(c[12] == 0 ? new Field("Le", "00", "00", BankQr.Status.OK, null)
                    : new Field("Le", "00", Hex.encode(c, 12, 1), BankQr.Status.WARN, "Table 5 says 00"));
        } else {
            f.add(new Field("Le", "00", "absent", BankQr.Status.WARN,
                    "Table 5 lists Le 00; this app does not need it"));
        }
        byte[] want = Apdu.withSw(Apdu.FCI, Apdu.SW_OK);
        boolean same = Arrays.equals(want, resp);
        f.add(new Field("Phone answered", Hex.encode(want), Hex.encode(resp),
                same ? BankQr.Status.OK : BankQr.Status.FAIL, same ? null : "FCI template + 9000 (Clause 3.2)"));
        return new Step(number, "SELECT Tap2Pay AID", atMs, Hex.encode(c), Hex.encode(resp), null, f);
    }

    // ---------------------------------------------------------------- PUSH

    private static final class PushStep {
        final Step step;
        /** The reassembly result, or null when the command never reached the assembler. */
        final PushAssembler.Result result;

        PushStep(Step step, PushAssembler.Result result) {
            this.step = step;
            this.result = result;
        }
    }

    private static PushStep pushStep(int number, long atMs, byte[] c, byte[] resp, boolean selected, PushAssembler asm) {
        List<Field> f = new ArrayList<>();
        int sw = swOf(resp);
        if (c.length < 4) {
            f.add(new Field("APDU", "at least 4 bytes (CLA INS P1 P2)", c.length + " bytes: " + Hex.encode(c),
                    BankQr.Status.FAIL, "too short to be a command"));
            f.add(answer(Apdu.SW_BAD_DATA, sw, null));
            return new PushStep(new Step(number, "Short APDU", atMs, Hex.encode(c), Hex.encode(resp), null, f), null);
        }
        int cla = c[0] & 0xFF;
        int ins = c[1] & 0xFF;
        int p1 = c[2] & 0xFF;
        int p2 = c[3] & 0xFF;
        int lc = c.length > 4 ? c[4] & 0xFF : 0;
        int offset = 5;
        boolean extended = false;
        if (lc == 0 && c.length >= 8) {
            lc = ((c[5] & 0xFF) << 8) | (c[6] & 0xFF);
            offset = 7;
            extended = true;
        }
        int dataLen = Math.max(0, c.length - offset);
        boolean usable = selected && cla == Apdu.CLA_PROPRIETARY && ins == Apdu.INS_PUSH_INVOICE;

        PushAssembler.Result r = usable ? asm.push(c) : null;

        f.add(byteField("CLA", Apdu.CLA_PROPRIETARY, cla, "proprietary"));
        f.add(byteField("INS", Apdu.INS_PUSH_INVOICE, ins, "PUSH INVOICE"));
        f.add(p1Field(p1, r, usable));
        f.add(p2Field(p2, r, usable));
        f.add(lcField(lc, extended));
        f.add(dataField(lc, dataLen));

        int specSw;
        String deviation = null;
        if (!selected) {
            specSw = Apdu.SW_CONDITIONS;
        } else if (cla != Apdu.CLA_PROPRIETARY) {
            specSw = PhoneResponder.SW_CLA_NOT_SUPPORTED;
        } else if (ins != Apdu.INS_PUSH_INVOICE) {
            specSw = Apdu.SW_INS_NOT_SUPPORTED;
        } else if (r != null && r.form == PushAssembler.Form.INDEX) {
            specSw = Apdu.SW_WRONG_P1P2;
            deviation = "Table 6b: only P1 03 is valid, anything else gets 6A86; this app accepts the Sunmi form";
        } else if (r != null && r.sw == Apdu.SW_WRONG_P1P2) {
            specSw = Apdu.SW_WRONG_P1P2;
        } else if (lc == 0) {
            specSw = Apdu.SW_WRONG_LENGTH;
            deviation = "Table 11: Lc 0 gets 6700; this app answers 6A80";
        } else if (r != null && r.note != null && r.note.startsWith("payload over")) {
            specSw = Apdu.SW_NO_MEMORY;
            deviation = "Table 11: an exceeded buffer gets 6A84; this app answers 6A80";
        } else if (dataLen < lc) {
            specSw = Apdu.SW_WRONG_LENGTH;
            deviation = "Table 11: a length that does not match the data gets 6700; this app answers 6A80";
        } else if (r != null && r.complete()) {
            specSw = RaastQr.problemWith(new String(r.payload, StandardCharsets.UTF_8)) == null
                    ? Apdu.SW_OK : Apdu.SW_BAD_DATA;
        } else {
            specSw = Apdu.SW_OK;
        }
        f.add(answer(specSw, sw, deviation));

        byte[] data = offset <= c.length ? Arrays.copyOfRange(c, offset, Math.min(c.length, offset + Math.min(lc, dataLen))) : new byte[0];
        return new PushStep(new Step(number, pushTitle(r), atMs, Hex.encode(c), Hex.encode(resp), printable(data), f), r);
    }

    private static String pushTitle(PushAssembler.Result r) {
        if (r == null) return "PUSH INVOICE (not processed)";
        if (r.sw != Apdu.SW_OK) return "PUSH INVOICE (refused)";
        if (r.complete()) {
            return r.count == 1 ? "PUSH INVOICE (single APDU)" : "PUSH INVOICE, chunk " + r.count + " (last)";
        }
        return "PUSH INVOICE, chunk " + r.count;
    }

    private static Field p1Field(int p1, PushAssembler.Result r, boolean usable) {
        String got = String.format(Locale.US, "%02X", p1);
        if (!usable) return new Field("P1", "03", got, BankQr.Status.OK, "not checked: the command was refused earlier");
        if (p1 == Apdu.P1_PUSH) return new Field("P1", "03", got, BankQr.Status.OK, null);
        if (r != null && r.form == PushAssembler.Form.INDEX) {
            return new Field("P1", "03", got, BankQr.Status.WARN,
                    "index form (Sunmi POS v2.7): not in the spec, accepted by this app");
        }
        return new Field("P1", "03", got, BankQr.Status.FAIL, "unsupported payment mode (Table 6b)");
    }

    private static Field p2Field(int p2, PushAssembler.Result r, boolean usable) {
        String got = String.format(Locale.US, "%02X", p2);
        String expected = r != null && r.form == PushAssembler.Form.INDEX
                ? "0-based chunk index"
                : "00 single, or 01-7F then 81-FF for the last, in order";
        if (!usable) return new Field("P2", expected, got, BankQr.Status.OK, "not checked: the command was refused earlier");
        if (r != null && r.sw == Apdu.SW_WRONG_P1P2 && r.note != null && !r.note.startsWith("unknown P1")) {
            return new Field("P2", expected, got, BankQr.Status.FAIL, r.note);
        }
        return new Field("P2", expected, got, BankQr.Status.OK, null);
    }

    private static Field lcField(int lc, boolean extended) {
        String got = lc + (extended ? " (extended Lc)" : "");
        if (lc == 0) return new Field("Lc", "1-239 (01-EF)", got, BankQr.Status.FAIL, "an empty chunk");
        if (lc > MAX_LC) {
            return new Field("Lc", "1-239 (01-EF)", got, BankQr.Status.WARN,
                    "above the spec's 239-byte chunk (Clause 3.8); this app accepts it");
        }
        return new Field("Lc", "1-239 (01-EF)", got, BankQr.Status.OK, null);
    }

    private static Field dataField(int lc, int dataLen) {
        String got = dataLen + " bytes";
        if (dataLen < lc) return new Field("Data", lc + " bytes (= Lc)", got, BankQr.Status.FAIL, "shorter than Lc");
        if (dataLen > lc) return new Field("Data", lc + " bytes (= Lc)", got, BankQr.Status.WARN, "longer than Lc, the rest is ignored");
        return new Field("Data", lc + " bytes (= Lc)", got, BankQr.Status.OK, null);
    }

    private static Field answer(int specSw, int got, String deviation) {
        if (specSw == got) return new Field("Phone answered", sw(specSw), sw(got), BankQr.Status.OK, null);
        return new Field("Phone answered", sw(specSw), sw(got), deviation != null ? BankQr.Status.WARN : BankQr.Status.FAIL,
                deviation != null ? deviation : "the spec's answer for this command is " + sw(specSw));
    }

    // ---------------------------------------------------------------- helpers

    private static Field byteField(String name, int expected, int got, String meaning) {
        String e = String.format(Locale.US, "%02X", expected);
        String g = String.format(Locale.US, "%02X", got);
        return expected == got ? new Field(name, e, g, BankQr.Status.OK, null)
                : new Field(name, e, g, BankQr.Status.FAIL, "should be " + meaning);
    }

    private static BankQr.Status eq(byte[] c, int off, byte[] want) {
        return Arrays.equals(Arrays.copyOfRange(c, off, off + want.length), want) ? BankQr.Status.OK : BankQr.Status.FAIL;
    }

    private static BankQr.Status worst(List<Field> fields) {
        BankQr.Status w = BankQr.Status.OK;
        for (Field f : fields) {
            if (f.status == BankQr.Status.FAIL) return BankQr.Status.FAIL;
            if (f.status == BankQr.Status.WARN) w = BankQr.Status.WARN;
        }
        return w;
    }

    /** The status word at the end of an answer, or -1 when it is shorter than 2 bytes. */
    private static int swOf(byte[] resp) {
        return resp.length < 2 ? -1 : Apdu.swOf(resp);
    }

    private static String sw(int sw) {
        return sw < 0 ? "no answer" : String.format(Locale.US, "%04X", sw);
    }

    private static String printable(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length);
        for (byte x : b) {
            int c = x & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        return sb.toString();
    }
}
