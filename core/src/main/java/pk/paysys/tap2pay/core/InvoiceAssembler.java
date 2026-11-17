package pk.paysys.tap2pay.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Phone-side PUSH INVOICE reassembly (spec Clauses 3.3, 3.8, 4.3): Table 10 rules 1 (command header) and
 * 2 (reassembly buffer). Feed every PUSH INVOICE APDU to {@link #accept}; when a step is {@link Step#complete()},
 * hand {@link Step#payload} to {@link InvoiceValidator}. Any error discards the buffer, so the terminal restarts
 * from chunk 1 (with the same nonce). Call {@link #reset()} on SELECT and on deactivation. Not thread-safe;
 * use one instance per HCE service.
 */
public final class InvoiceAssembler {
    /** Table 14 default T_chunk_gap. */
    public static final long T_CHUNK_GAP_MS = 2000L;
    /** Reassembly buffer limit. The spec names the 6A84 rule but no size; 1024 B fits 4 full chunks. */
    public static final int DEFAULT_MAX_BUFFER = 1024;

    /** Monotonic millisecond clock (e.g. SystemClock.elapsedRealtime on Android). */
    public interface Clock {
        long nowMillis();
    }

    public static final class Step {
        public final int sw;
        public final String reason;
        /** The reassembled invoice object (UTF-8 bytes) once the final or only chunk arrived; otherwise null. */
        public final byte[] payload;

        Step(int sw, String reason, byte[] payload) {
            this.sw = sw;
            this.reason = reason;
            this.payload = payload;
        }

        /** True when the object is complete and must now be validated; its SW comes from the validator. */
        public boolean complete() {
            return payload != null;
        }

        public String payloadString() {
            return payload == null ? null : new String(payload, StandardCharsets.UTF_8);
        }
    }

    private final long chunkGapMs;
    private final int maxBuffer;
    private final Clock clock;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int expectChunk = 1;
    private long lastChunkAt;

    public InvoiceAssembler() {
        this(T_CHUNK_GAP_MS, DEFAULT_MAX_BUFFER, new Clock() {
            @Override
            public long nowMillis() {
                return System.nanoTime() / 1_000_000L;
            }
        });
    }

    /** @param chunkGapMs T_chunk_gap, Table 14 range 1-5 s */
    public InvoiceAssembler(long chunkGapMs, int maxBuffer, Clock clock) {
        if (chunkGapMs < 1000L || chunkGapMs > 5000L) throw new IllegalArgumentException("T_chunk_gap out of range");
        if (maxBuffer < Apdu.MAX_CHUNK) throw new IllegalArgumentException("buffer smaller than one chunk");
        this.chunkGapMs = chunkGapMs;
        this.maxBuffer = maxBuffer;
        this.clock = clock;
    }

    /** True while a multi-chunk transfer is in progress. */
    public boolean inProgress() {
        return expectChunk > 1;
    }

    public void reset() {
        buffer.reset();
        expectChunk = 1;
        lastChunkAt = 0;
    }

    /** Processes one PUSH INVOICE command APDU (CLA 80, INS CA). */
    public Step accept(byte[] apdu) {
        if (apdu == null || apdu.length < 4) return error(Apdu.SW_WRONG_LENGTH, "APDU shorter than header");
        int cla = apdu[0] & 0xFF, ins = apdu[1] & 0xFF, p1 = apdu[2] & 0xFF, p2 = apdu[3] & 0xFF;
        if (cla != Apdu.CLA_PROPRIETARY || ins != Apdu.INS_PUSH_INVOICE) {
            return error(Apdu.SW_INS_NOT_SUPPORTED, "not PUSH INVOICE");
        }

        // Rule 1: P1 (Table 6b)
        if (p1 != Apdu.P1_PUSH) return error(Apdu.SW_WRONG_P1P2, String.format("unsupported P1 %02X", p1));

        // Rule 1: Lc 01-EF, data present, Le absent (one trailing Le byte is tolerated)
        if (apdu.length < 5) return error(Apdu.SW_WRONG_LENGTH, "Lc missing");
        int lc = apdu[4] & 0xFF;
        if (lc == 0) return error(Apdu.SW_WRONG_LENGTH, "Lc = 0");
        if (lc > Apdu.MAX_CHUNK) return error(Apdu.SW_WRONG_LENGTH, "Lc " + lc + " > " + Apdu.MAX_CHUNK);
        if (apdu.length != 5 + lc && apdu.length != 6 + lc) {
            return error(Apdu.SW_WRONG_LENGTH, "Lc " + lc + " inconsistent with APDU length " + apdu.length);
        }
        byte[] data = Arrays.copyOfRange(apdu, 5, 5 + lc);

        long now = clock.nowMillis();
        boolean timedOut = inProgress() && now - lastChunkAt > chunkGapMs;
        if (timedOut) reset(); // T_chunk_gap expired: the old buffer is gone regardless of what arrives now

        // Rule 1: P2 sequencing (Table 6c)
        if (p2 == 0x00) {
            if (inProgress()) return error(Apdu.SW_NOT_ALLOWED, "single-APDU invoice during chunked transfer");
            return new Step(Apdu.SW_OK, "complete (single APDU)", data);
        }
        int idx = p2 & 0x7F;
        boolean last = (p2 & 0x80) != 0;
        if (idx != expectChunk) {
            return error(Apdu.SW_WRONG_P1P2, timedOut
                    ? "chunk gap timeout before chunk " + idx
                    : "chunk sequence error: got " + idx + ", expected " + expectChunk);
        }

        // Rule 2: reassembly buffer
        if (buffer.size() + data.length > maxBuffer) {
            return error(Apdu.SW_NO_MEMORY, "reassembly buffer exceeded (" + (buffer.size() + data.length)
                    + " > " + maxBuffer + ")");
        }
        buffer.write(data, 0, data.length);
        expectChunk++;
        lastChunkAt = now;
        if (!last) return new Step(Apdu.SW_OK, "chunk " + idx + " buffered, more follows", null);
        byte[] complete = buffer.toByteArray();
        reset();
        return new Step(Apdu.SW_OK, "complete (" + idx + " chunks)", complete);
    }

    private Step error(int sw, String reason) {
        reset();
        return new Step(sw, reason, null);
    }
}
