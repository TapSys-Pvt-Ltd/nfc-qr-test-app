package pk.paysys.tap2pay.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Phone-side validation of a reassembled invoice, rules 3-8 of spec Table 10, in order.
 * Rules 1-2 (command header, reassembly buffer) are enforced by {@link InvoiceAssembler}.
 */
public final class InvoiceValidator {
    /** Table 14 defaults. */
    public static final long T_FRESHNESS_MS = 300_000L;
    public static final long T_DEDUPE_MS = 900_000L;

    /** Table 10 rule numbers, reported in {@link Result#rule}. */
    public static final int RULE_TLV = 3;
    public static final int RULE_CRC = 4;
    public static final int RULE_SYNTAX = 5;
    public static final int RULE_FRESHNESS = 6;
    public static final int RULE_DUPLICATE = 7;
    public static final int RULE_POLICY = 8;

    /** Tags defined by Table 8; a duplicate of one of these is rejected (unknown tags are skipped). */
    private static final Set<String> DEFINED_TOP = new HashSet<>(Arrays.asList(
            "00", "01", "26", "52", "53", "54", "58", "59", "60", "62", "63", "80", "81", "82"));
    private static final Set<String> DEFINED_26 = new HashSet<>(Arrays.asList("00", "01", "02"));
    private static final Set<String> DEFINED_62 = new HashSet<>(Arrays.asList("01", "03", "05", "07", "08"));

    private static final String PRINTABLE = "[\\x20-\\x7E]";

    public static final class Result {
        public final int sw;
        public final String reason;
        public final Map<String, String> top;
        public final Map<String, String> account;
        public final Map<String, String> additional;
        /** Table 10 rule that decided the outcome (0 when accepted). */
        public final int rule;
        /**
         * True when this nonce was already accepted within T_dedupe and not yet authorized: an idempotent
         * re-presentation (Clause 4.3) that should refresh the displayed invoice, not start a second payment.
         */
        public final boolean refresh;

        Result(int sw, String reason, Map<String, String> top, Map<String, String> account,
               Map<String, String> additional) {
            this(sw, reason, top, account, additional, 0, false);
        }

        Result(int sw, String reason, Map<String, String> top, Map<String, String> account,
               Map<String, String> additional, int rule, boolean refresh) {
            this.sw = sw;
            this.reason = reason;
            this.top = top;
            this.account = account;
            this.additional = additional;
            this.rule = rule;
            this.refresh = refresh;
        }

        public boolean ok() {
            return sw == Apdu.SW_OK;
        }
    }

    private static final class Seen {
        long at;
        boolean authorized;

        Seen(long at) {
            this.at = at;
        }
    }

    private final long freshnessMs;
    private final long dedupeMs;
    /** Nonce (upper case) -> first acceptance / authorization time (Clause 4.3 duplicate detection). */
    private final Map<String, Seen> seenNonces = new LinkedHashMap<>();
    private volatile boolean presentationAllowed = true;

    public InvoiceValidator() {
        this(T_FRESHNESS_MS, T_DEDUPE_MS);
    }

    /** MSP-configured timings (Table 14 ranges: T_freshness 60-900 s, T_dedupe 300-3600 s). */
    public InvoiceValidator(long freshnessMs, long dedupeMs) {
        if (freshnessMs < 60_000L || freshnessMs > 900_000L) throw new IllegalArgumentException("T_freshness out of range");
        if (dedupeMs < 300_000L || dedupeMs > 3_600_000L) throw new IllegalArgumentException("T_dedupe out of range");
        this.freshnessMs = freshnessMs;
        this.dedupeMs = dedupeMs;
    }

    /** Rule 8: device policy (e.g. a restrictive admin state). When false every valid invoice gets 6985. */
    public void setPresentationAllowed(boolean allowed) {
        this.presentationAllowed = allowed;
    }

    /**
     * Records that the consumer authorized the invoice with this nonce. A later presentation of the same nonce
     * within T_dedupe is then rejected with 6985 (Clause 4.3, test T-05).
     */
    public synchronized void markAuthorized(String nonce, long nowMillis) {
        String key = nonce.toUpperCase(Locale.US);
        Seen s = seenNonces.get(key);
        if (s == null) {
            s = new Seen(nowMillis);
            seenNonces.put(key, s);
        }
        s.authorized = true;
        s.at = nowMillis;
    }

    public synchronized Result validate(String payload, long nowMillis) {
        // Rule 3: TLV parse (recursive into 26 and 62), tag 00 = 01 first, tag 63 last
        List<Tlv.Element> elements;
        try {
            elements = Tlv.parseElements(payload);
        } catch (IllegalArgumentException e) {
            return fail(Apdu.SW_BAD_DATA, RULE_TLV, "TLV: " + e.getMessage());
        }
        if (elements.isEmpty() || !elements.get(0).tag.equals("00") || !"01".equals(elements.get(0).value)) {
            return fail(Apdu.SW_BAD_DATA, RULE_TLV, "tag 00 must be first and = 01");
        }
        String dup = firstDuplicate(elements, DEFINED_TOP, "");
        if (dup == null) {
            for (Tlv.Element e : elements) {
                if (e.tag.equals("26")) dup = firstDuplicate(e.children, DEFINED_26, "26-");
                else if (e.tag.equals("62")) dup = firstDuplicate(e.children, DEFINED_62, "62-");
                if (dup != null) break;
            }
        }
        if (dup != null) return fail(Apdu.SW_BAD_DATA, RULE_TLV, "duplicate tag " + dup);
        Map<String, String> top = Tlv.parse(payload);
        Map<String, String> account = templateMap(elements, "26");
        Map<String, String> additional = templateMap(elements, "62");

        // Rule 4: CRC-16 over payload + "6304"
        Tlv.Element last = elements.get(elements.size() - 1);
        if (!last.tag.equals("63") || last.length != 4 || !last.value.matches("[0-9A-F]{4}")) {
            return fail(Apdu.SW_BAD_DATA, RULE_CRC, "tag 63 missing, not last or malformed");
        }
        String expected = Crc16.forPayloadPrefix(payload.substring(0, payload.length() - 4));
        if (!expected.equals(last.value)) {
            return fail(Apdu.SW_BAD_DATA, RULE_CRC, "CRC " + last.value + " != " + expected);
        }

        // Rule 5: mandatory elements and syntax (Table 8)
        String err = syntaxError(top, account, additional);
        if (err != null) return new Result(Apdu.SW_BAD_DATA, err, top, account, additional, RULE_SYNTAX, false);
        String nonce = top.get("81");
        long ts = parseTimestamp(top.get("82"));

        // Rule 6: freshness
        long skew = Math.abs(nowMillis - ts);
        if (skew > freshnessMs) {
            return new Result(Apdu.SW_CONDITIONS, "stale timestamp, skew " + skew / 1000 + "s",
                    top, account, additional, RULE_FRESHNESS, false);
        }

        // Rule 7: duplicate nonce (Clause 4.3: re-presentation before authorization is an idempotent refresh)
        for (Iterator<Map.Entry<String, Seen>> it = seenNonces.entrySet().iterator(); it.hasNext(); ) {
            if (nowMillis - it.next().getValue().at > dedupeMs) it.remove();
        }
        String key = nonce.toUpperCase(Locale.US);
        Seen seen = seenNonces.get(key);
        if (seen != null && seen.authorized) {
            return new Result(Apdu.SW_CONDITIONS, "duplicate nonce " + nonce + " (already authorized)",
                    top, account, additional, RULE_DUPLICATE, false);
        }

        // Rule 8: device policy
        if (!presentationAllowed) {
            return new Result(Apdu.SW_CONDITIONS, "device policy blocks presentation",
                    top, account, additional, RULE_POLICY, false);
        }

        if (seen != null) {
            return new Result(Apdu.SW_OK, "accepted (re-presentation of nonce " + nonce + ")",
                    top, account, additional, 0, true);
        }
        seenNonces.put(key, new Seen(nowMillis));
        return new Result(Apdu.SW_OK, "accepted", top, account, additional, 0, false);
    }

    /** First rule-5 violation, or null. Lengths are UTF-8 byte counts (Clause 3.4). */
    static String syntaxError(Map<String, String> top, Map<String, String> account, Map<String, String> additional) {
        // Template 26 is mandatory; template 62 is mandatory
        if (!top.containsKey("26")) return "26 merchant account template missing";
        if (!top.containsKey("62")) return "62 additional data template missing";

        String pim = top.get("01");
        if (!"12".equals(pim) && !"11".equals(pim)) return "01 must be 11 or 12";

        String iban = account.get("00");
        if (iban == null || !iban.matches("PK\\d{2}[A-Z0-9]{20}") || !ibanMod97(iban)) {
            return "26-00 IBAN invalid: " + iban;
        }
        if (account.containsKey("01") && !text(account.get("01"), 1, 20, false)) return "26-01 alias invalid";
        if (account.containsKey("02") && !matches(account.get("02"), "[A-Z0-9]{8}([A-Z0-9]{3})?")) {
            return "26-02 BIC must be an8 or an11: " + account.get("02");
        }

        if (!matches(top.get("52"), "\\d{4}")) return "52 MCC invalid";
        if (!matches(top.get("53"), "\\d{3}")) return "53 currency invalid";
        String amount = top.get("54");
        if (amount == null && "12".equals(pim)) return "54 amount missing for one-time invoice";
        if (amount != null && !matches(amount, "\\d+\\.\\d{2}")) return "54 amount invalid: " + amount;
        if (!matches(top.get("58"), "[A-Z]{2}")) return "58 country invalid";
        if (top.get("59") == null) return "59 DBA missing";
        if (!text(top.get("59"), 1, 30, true)) return "59 DBA must be 1-30 bytes";
        if (top.containsKey("60") && !text(top.get("60"), 1, 20, true)) return "60 city must be 1-20 bytes";

        if (additional.containsKey("01") && !text(additional.get("01"), 1, 30, false)) return "62-01 bill number invalid";
        if (additional.containsKey("03") && !text(additional.get("03"), 1, 25, true)) return "62-03 store label invalid";
        if (additional.get("05") == null) return "62-05 reference label missing";
        if (!text(additional.get("05"), 1, 30, false)) return "62-05 reference label must be 1-30 bytes";
        if (additional.get("07") == null) return "62-07 terminal label missing";
        if (!text(additional.get("07"), 1, 30, false)) return "62-07 terminal label must be 1-30 bytes";
        if (additional.containsKey("08") && !text(additional.get("08"), 1, 4, false)) return "62-08 purpose invalid";

        if (!matches(top.get("80"), "[A-Za-z0-9]{8,10}")) return "80 terminal id invalid";
        if (!matches(top.get("81"), "[0-9A-Fa-f]{16}")) return "81 nonce invalid/missing";
        if (top.get("82") == null) return "82 timestamp missing";
        if (parseTimestamp(top.get("82")) == Long.MIN_VALUE) return "82 timestamp invalid";
        return null;
    }

    private static Result fail(int sw, int rule, String reason) {
        return new Result(sw, reason, null, null, null, rule, false);
    }

    private static String firstDuplicate(List<Tlv.Element> elements, Set<String> defined, String prefix) {
        Set<String> seen = new HashSet<>();
        for (Tlv.Element e : elements) {
            if (defined.contains(e.tag) && !seen.add(e.tag)) return prefix + e.tag;
        }
        return null;
    }

    private static Map<String, String> templateMap(List<Tlv.Element> elements, String tag) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Tlv.Element e : elements) {
            if (!e.tag.equals(tag)) continue;
            for (Tlv.Element c : e.children) if (!out.containsKey(c.tag)) out.put(c.tag, c.value);
            break;
        }
        return out;
    }

    private static boolean matches(String v, String regex) {
        return v != null && v.matches(regex);
    }

    /** ans value of minBytes..maxBytes UTF-8 bytes; printable ASCII only unless utf8 is allowed (Clause 3.4). */
    private static boolean text(String v, int minBytes, int maxBytes, boolean utf8) {
        if (v == null) return false;
        int len = v.getBytes(StandardCharsets.UTF_8).length;
        if (len < minBytes || len > maxBytes) return false;
        if (utf8) {
            for (int i = 0; i < v.length(); i++) if (Character.isISOControl(v.charAt(i))) return false;
            return true;
        }
        return v.matches(PRINTABLE + "*");
    }

    static boolean ibanMod97(String iban) {
        String r = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : r.toCharArray()) digits.append(Character.getNumericValue(c));
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** Epoch millis, or Long.MIN_VALUE when not a valid YYYYMMDDThhmmssZ UTC timestamp. */
    static long parseTimestamp(String v) {
        if (!matches(v, "\\d{8}T\\d{6}Z")) return Long.MIN_VALUE;
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        f.setLenient(false);
        try {
            return f.parse(v).getTime();
        } catch (ParseException e) {
            return Long.MIN_VALUE;
        }
    }
}
