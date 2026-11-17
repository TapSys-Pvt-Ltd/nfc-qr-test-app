package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apple VAS reachability probe: the POS sends SELECT OSE.VAS.01 and classifies the iPhone's answer. Expected FCI
 * (Proxmark3 {@code hf vas}): 6F { 50 "ApplePay", 9F21 version 01 00, 9F24 nonce (4 B), 9F23 capabilities (4 B,
 * first three bytes 00, last byte = bit flags below) }. GET VAS DATA is not sent: it needs a pass type ID and an
 * Apple NFC pass certificate.
 */
public final class VasProbe {
    private VasProbe() {}

    public static final byte[] AID = "OSE.VAS.01".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] SELECT = Hex.decode("00A404000A4F53452E5641532E303100");

    public static final int SW_APP_NOT_FOUND = 0x6A82;

    private static final int TAG_WALLET = 0x50;
    private static final int TAG_VERSION = 0x9F21;
    private static final int TAG_CAPABILITIES = 0x9F23;
    private static final int TAG_NONCE = 0x9F24;
    private static final int CAP_VAS_MAY_BE_PERFORMED = 0x08;
    /** Bit names of the last capabilities byte, bit 0 first. */
    private static final String[] CAP_BITS = {
            "plaintext VAS data supported", "encrypted VAS data supported", "VAS may be skipped",
            "VAS may be performed", "payment may be skipped", "payment may be performed", "bit 6", "bit 7"};

    public enum Verdict {
        VAS_READY("Apple VAS answered without ECP and offers VAS: the POS-to-Wallet link works"),
        VAS_NOT_OFFERED("Apple VAS answered, but its capabilities do not allow VAS (bit 3 clear)"),
        NOT_APPLE("OSE.VAS.01 answered, but the wallet is not \"ApplePay\""),
        UNEXPECTED("Apple VAS answered, but version or capabilities are not as expected; see the decoded FCI"),
        NOT_SELECTED("OSE.VAS.01 not selected: no Apple VAS on this device, or Wallet not presented"),
        MALFORMED("9000, but the FCI is empty or not valid BER-TLV; see raw"),
        NO_RESPONSE("No response to SELECT OSE.VAS.01");

        public final String summary;

        Verdict(String summary) {
            this.summary = summary;
        }
    }

    public static final class Result {
        public final Verdict verdict;
        /** Log lines: status word, raw response, decoded FCI. */
        public final List<String> lines;

        Result(Verdict verdict, List<String> lines) {
            this.verdict = verdict;
            this.lines = Collections.unmodifiableList(lines);
        }
    }

    public static Result describe(byte[] resp) {
        List<String> lines = new ArrayList<>();
        if (resp == null || resp.length < 2) {
            lines.add("raw: " + (resp == null ? "null" : Hex.encode(resp)));
            return new Result(Verdict.NO_RESPONSE, lines);
        }
        int sw = Apdu.swOf(resp);
        lines.add("SW " + swName(sw));
        lines.add("raw: " + Hex.encode(resp));
        if (sw != Apdu.SW_OK) return new Result(Verdict.NOT_SELECTED, lines);
        if (resp.length == 2) return new Result(Verdict.MALFORMED, lines);

        List<BerTlv.Node> nodes;
        try {
            nodes = BerTlv.parse(resp, 0, resp.length - 2);
        } catch (IllegalArgumentException e) {
            lines.add("FCI not BER-TLV: " + e.getMessage());
            return new Result(Verdict.MALFORMED, lines);
        }
        decode(nodes, "", lines);

        BerTlv.Node wallet = BerTlv.find(nodes, TAG_WALLET);
        if (wallet == null || !"ApplePay".equals(new String(wallet.value, StandardCharsets.US_ASCII))) {
            return new Result(Verdict.NOT_APPLE, lines);
        }
        BerTlv.Node version = BerTlv.find(nodes, TAG_VERSION);
        if (version == null || version.value.length != 2 || version.value[0] != 0x01 || version.value[1] != 0x00) {
            lines.add("check: 9F21 version must be 01 00");
            return new Result(Verdict.UNEXPECTED, lines);
        }
        BerTlv.Node caps = BerTlv.find(nodes, TAG_CAPABILITIES);
        if (caps == null || caps.value.length != 4 || caps.value[0] != 0 || caps.value[1] != 0 || caps.value[2] != 0) {
            lines.add("check: 9F23 capabilities must be 4 bytes, 00 00 00 xx");
            return new Result(Verdict.UNEXPECTED, lines);
        }
        return new Result((caps.value[3] & CAP_VAS_MAY_BE_PERFORMED) != 0 ? Verdict.VAS_READY : Verdict.VAS_NOT_OFFERED,
                lines);
    }

    private static String swName(int sw) {
        return sw == SW_APP_NOT_FOUND ? "6A82 application not found" : Apdu.swName(sw);
    }

    private static void decode(List<BerTlv.Node> nodes, String indent, List<String> lines) {
        for (BerTlv.Node n : nodes) {
            if (n.constructed()) {
                lines.add(indent + n.tagHex() + " {");
                decode(n.children, indent + "  ", lines);
                lines.add(indent + "}");
                continue;
            }
            String v = Hex.encode(n.value);
            switch (n.tag) {
                case TAG_WALLET:
                    lines.add(indent + "50 wallet = " + (printable(n.value)
                            ? "\"" + new String(n.value, StandardCharsets.US_ASCII) + "\"" : v));
                    break;
                case TAG_VERSION:
                    lines.add(indent + "9F21 VAS version = " + v);
                    break;
                case TAG_NONCE:
                    lines.add(indent + "9F24 mobile nonce = " + v);
                    break;
                case TAG_CAPABILITIES:
                    lines.add(indent + "9F23 mobile capabilities = " + v
                            + (n.value.length == 4 ? " (" + capabilityNames(n.value[3]) + ")" : ""));
                    break;
                default:
                    lines.add(indent + n.tagHex() + " = " + v);
            }
        }
    }

    private static String capabilityNames(byte flags) {
        StringBuilder sb = new StringBuilder();
        for (int bit = CAP_BITS.length - 1; bit >= 0; bit--) {
            if ((flags & (1 << bit)) == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(CAP_BITS[bit]);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static boolean printable(byte[] b) {
        for (byte c : b) if (c < 0x20 || c > 0x7E) return false;
        return b.length > 0;
    }
}
