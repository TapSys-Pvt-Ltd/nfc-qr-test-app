package pk.paysys.tap2pay.core;

import java.io.ByteArrayOutputStream;

/**
 * PUSH INVOICE (CLA 80, INS CA) reassembly as the NBP app does it (nbp-mobile-master
 * {@code PushInvoiceAssembler}), so a test against this predicts the real wallet. Two header forms:
 * <ul>
 *   <li>{@link Form#SPEC} (spec Clause 3.8, Urovo FBL app): P1 = 03; P2 = 00 for one APDU, else 01, 02, ...
 *       with the last chunk flagged 0x80.</li>
 *   <li>{@link Form#INDEX} (Mindcrafts Sunmi POS v2.7): P1 = 00 before the last chunk, 01 on it; P2 = 0-based
 *       chunk index.</li>
 * </ul>
 * A transfer stays in one form; a chunk out of sequence or in the other form drops the buffer. Unlike
 * {@link InvoiceAssembler} there is no chunk-gap timer, matching the NBP app. Not thread-safe.
 */
public final class PushAssembler {
    public static final int MAX_PAYLOAD = 4096;

    public enum Form { NONE, SPEC, INDEX }

    public static final class Result {
        public final int sw;
        /** Set only when the object is complete (sw 9000). */
        public final byte[] payload;
        public final Form form;
        /** Chunk number as the POS numbered it (spec: 1-based, 0 for one APDU; index: 0-based), or -1. */
        public final int chunk;
        public final String note;
        /** APDUs of this transfer accepted so far, this one included (0 on an error). */
        public final int count;

        Result(int sw, byte[] payload, Form form, int chunk, String note, int count) {
            this.sw = sw;
            this.payload = payload;
            this.form = form;
            this.chunk = chunk;
            this.note = note;
            this.count = count;
        }

        public boolean complete() {
            return payload != null;
        }
    }

    private final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    private Form form = Form.NONE;
    private int nextChunk;
    private int received;

    public void reset() {
        chunks.reset();
        form = Form.NONE;
        nextChunk = 0;
        received = 0;
    }

    /** Chunks buffered in the transfer now in progress (0 when none is). */
    public int chunksBuffered() {
        return received;
    }

    /** {@code apdu} is a command already checked to be CLA 80, INS CA. */
    public Result push(byte[] apdu) {
        if (apdu == null || apdu.length < 6) return fail(Apdu.SW_BAD_DATA, Form.NONE, -1, "APDU too short");
        int p1 = apdu[2] & 0xFF;
        int p2 = apdu[3] & 0xFF;

        // Short Lc (spec), or extended Lc 00 hi lo in case a reader sends that form.
        int lc = apdu[4] & 0xFF;
        int offset = 5;
        if (lc == 0 && apdu.length >= 8) {
            lc = ((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF);
            offset = 7;
        }
        if (lc == 0) return fail(Apdu.SW_BAD_DATA, Form.NONE, -1, "Lc = 0");
        if (apdu.length < offset + lc) {
            return fail(Apdu.SW_BAD_DATA, Form.NONE, -1, "Lc " + lc + " longer than the data");
        }

        if (p1 == Apdu.P1_PUSH) {
            if (p2 == 0x00) {
                reset();
                return append(apdu, offset, lc, true, Form.SPEC, 0);
            }
            int seq = p2 & 0x7F;
            if (form == Form.NONE && seq == 1) {
                form = Form.SPEC;
                nextChunk = 1;
            }
            if (form != Form.SPEC || seq != nextChunk) {
                return fail(Apdu.SW_WRONG_P1P2, Form.SPEC, seq, sequenceError(Form.SPEC, seq));
            }
            return append(apdu, offset, lc, (p2 & 0x80) != 0, Form.SPEC, seq);
        }
        if (p1 == 0x00 || p1 == 0x01) {
            if (form == Form.NONE && p2 == 0) {
                form = Form.INDEX;
                nextChunk = 0;
            }
            if (form != Form.INDEX || p2 != nextChunk) {
                return fail(Apdu.SW_WRONG_P1P2, Form.INDEX, p2, sequenceError(Form.INDEX, p2));
            }
            return append(apdu, offset, lc, p1 == 0x01, Form.INDEX, p2);
        }
        return fail(Apdu.SW_WRONG_P1P2, Form.NONE, -1, String.format("unknown P1 %02X", p1));
    }

    private String sequenceError(Form got, int chunk) {
        if (form != Form.NONE && form != got) return "header form changed from " + form + " to " + got;
        if (form == Form.NONE) return "transfer started at chunk " + chunk;
        return "chunk " + chunk + " out of sequence, expected " + nextChunk;
    }

    private Result append(byte[] apdu, int offset, int lc, boolean last, Form f, int chunk) {
        if (chunks.size() + lc > MAX_PAYLOAD) {
            return fail(Apdu.SW_BAD_DATA, f, chunk, "payload over " + MAX_PAYLOAD + " bytes");
        }
        chunks.write(apdu, offset, lc);
        nextChunk++;
        received++;
        if (!last) return new Result(Apdu.SW_OK, null, f, chunk, "chunk buffered, more follows", received);
        byte[] payload = chunks.toByteArray();
        int n = received;
        reset();
        return new Result(Apdu.SW_OK, payload, f, chunk, "complete, " + n + (n == 1 ? " APDU" : " chunks"), n);
    }

    private Result fail(int sw, Form f, int chunk, String note) {
        reset();
        return new Result(sw, null, f, chunk, note, 0);
    }
}
