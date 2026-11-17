package pk.paysys.tap2pay.core;

import java.util.ArrayList;
import java.util.List;

/** Shared test data: the Clause 3.9 canonical object and helpers to mutate it. */
final class Fixtures {
    private Fixtures() {}

    /** Canonical timestamp 20261008T101530Z, used as "now" so the canonical object is fresh. */
    static final long NOW = java.time.Instant.parse("2026-10-08T10:15:30Z").toEpochMilli();
    static final String NONCE = "3F2A9C011B7E4D55";

    /** Replaces {@code from} with {@code to} in the canonical object and recomputes the CRC. */
    static String mutate(String from, String to) {
        String body = Invoice.CANONICAL.substring(0, Invoice.CANONICAL.length() - 8);
        if (!body.contains(from)) throw new AssertionError("canonical has no " + from);
        return resign(body.replace(from, to));
    }

    /** Appends 6304 + CRC to a payload body that ends just before tag 63. */
    static String resign(String bodyWithout63) {
        String b = bodyWithout63 + "6304";
        return b + Crc16.forPayloadPrefix(b);
    }

    /** Monotonic clock the test advances by hand. */
    static final class FakeClock implements InvoiceAssembler.Clock {
        long now = 1_000_000L;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    static InvoiceAssembler.Step feedAll(InvoiceAssembler a, List<byte[]> apdus) {
        InvoiceAssembler.Step s = null;
        for (byte[] apdu : apdus) {
            s = a.accept(apdu);
            if (s.sw != Apdu.SW_OK) return s;
        }
        return s;
    }

    static List<byte[]> list(byte[]... apdus) {
        List<byte[]> out = new ArrayList<>();
        for (byte[] a : apdus) out.add(a);
        return out;
    }

    static String header(byte[] apdu) {
        return Hex.encode(apdu, 0, 5);
    }
}
