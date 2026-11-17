package pk.paysys.tap2pay.core;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a phone needs to know about a pushed Raast merchant QR: the NBP app's acceptance check (starts 000201,
 * ends 6304 + matching CRC) and the readable fields. Parsing is lenient: a QR that passes the check but has
 * odd TLV still gets {@link #valid} = true, with whatever fields could be read.
 */
public final class RaastQr {
    public static final String TYPE_DYNAMIC = "dynamic";
    public static final String TYPE_STATIC = "static";

    /** What tag 54 says: absent, an amount above 0, or present but zero / not a number. */
    public enum AmountState {
        NONE,
        POSITIVE,
        UNUSABLE
    }

    public final String raw;
    /** The NBP app's rule: what decides 9000 or 6A80. */
    public final boolean valid;
    public final String problem;
    /** "dynamic" (01 = 12), "static" (01 = 11) or the raw value / "unknown". */
    public final String type;
    public final String amount;
    public final String merchant;
    public final String city;
    public final String mid;
    public final String tid;
    /** IBAN from template 28 (live Raast) or 26 (spec), when present. */
    public final String iban;
    public final boolean hasRtp;

    private RaastQr(String raw, boolean valid, String problem, Map<String, String> top) {
        this.raw = raw;
        this.valid = valid;
        this.problem = problem;
        String pim = top.get("01");
        this.type = "12".equals(pim) ? TYPE_DYNAMIC : "11".equals(pim) ? TYPE_STATIC : pim == null ? "unknown" : pim;
        this.amount = top.get("54");
        this.merchant = top.get("59");
        this.city = top.get("60");
        Map<String, String> additional = parse(top.get("62"));
        this.mid = additional.get("03");
        this.tid = additional.get("07");
        String account = top.containsKey("28") ? top.get("28") : top.get("26");
        this.iban = findIban(parse(account));
        this.hasRtp = top.containsKey("84");
    }

    public AmountState amountState() {
        if (amount == null) return AmountState.NONE;
        if (!amount.matches("\\d+(\\.\\d+)?")) return AmountState.UNUSABLE;
        return new BigDecimal(amount).signum() > 0 ? AmountState.POSITIVE : AmountState.UNUSABLE;
    }

    public static RaastQr of(String qr) {
        String problem = problemWith(qr);
        Map<String, String> top = parse(qr);
        return new RaastQr(qr, problem == null, problem, top);
    }

    /** Null when the NBP app would accept the QR, else why not. */
    static String problemWith(String qr) {
        if (qr == null || qr.isEmpty()) return "empty payload";
        if (qr.length() < 12) return "too short for a QR (" + qr.length() + " chars)";
        if (!qr.startsWith("000201")) return "does not start with 000201";
        int crcAt = qr.length() - 8;
        if (!qr.startsWith("6304", crcAt)) return "does not end with 6304 + CRC";
        String got = qr.substring(crcAt + 4).toUpperCase();
        String want = Crc16.forPayloadPrefix(qr.substring(0, crcAt + 4));
        if (!want.equals(got)) return "CRC " + got + " should be " + want;
        return null;
    }

    /** Lenient ID(2) + length(2) + value parse; stops at the first element that does not fit. */
    static Map<String, String> parse(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null) return out;
        int i = 0;
        while (i + 4 <= s.length()) {
            int len;
            try {
                len = Integer.parseInt(s.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                break;
            }
            if (len < 0 || i + 4 + len > s.length()) break;
            String id = s.substring(i, i + 2);
            if (!out.containsKey(id)) out.put(id, s.substring(i + 4, i + 4 + len));
            i += 4 + len;
        }
        return out;
    }

    private static String findIban(Map<String, String> account) {
        for (String v : account.values()) {
            if (v.matches("PK\\d{2}[A-Z0-9]{20}")) return v;
        }
        return null;
    }

    /** One line per field, for the tap result. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("QR: ").append(raw == null ? 0 : raw.length()).append(" chars, ")
                .append(valid ? "CRC ok" : "REJECTED: " + problem).append('\n');
        sb.append("Type: ").append(type);
        if (amountState() == AmountState.NONE) sb.append(" (payer enters the amount)");
        sb.append('\n');
        if (amount != null) sb.append("Amount: PKR ").append(amount).append('\n');
        if (merchant != null) sb.append("Merchant: ").append(merchant).append(city != null ? ", " + city : "").append('\n');
        if (mid != null || tid != null) sb.append("MID / TID: ").append(mid).append(" / ").append(tid).append('\n');
        if (iban != null) sb.append("IBAN: ").append(iban).append('\n');
        sb.append("RTP id (84): ").append(hasRtp ? "present" : "none");
        return sb.toString();
    }
}
