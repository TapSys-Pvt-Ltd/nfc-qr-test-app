package pk.paysys.tap2pay.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A pushed QR judged tag by tag: which tags a Raast P2M QR should carry, what this one carried, and whether each is
 * fine. Three groups:
 * <ol>
 *   <li>the NBP app's acceptance rule (starts 000201, ends 6304 + CRC, CRC matches), which alone decides 9000 or 6A80;</li>
 *   <li>the tags the bank app needs and reads (merchant, payee account in template 28, amount, references), with the
 *   formats of EMVCo merchant-presented QR and the Tap2Pay specification (Table 8);</li>
 *   <li>tags that only the spec's invoice object uses (26, 81, 82), which the NBP flow does not read.</li>
 * </ol>
 * Unknown top-level tags are listed as ignored (Clause 3.4: unknown tags are skipped by length). The rule table is the
 * single source of both the checks and the "expected" text, so what is shown is what is tested.
 * Status: OK as expected; WARN the NBP app copes but the format or limit is outside the spec; FAIL the bank app
 * cannot send the payment from this tag, or the tag breaks its format.
 */
public final class QrReport {
    private enum Req {
        REQUIRED("required"),
        DYNAMIC("required for a dynamic QR, optional for a static one"),
        DYNAMIC_WARN("expected in a dynamic QR, optional in a static one"),
        RECOMMENDED("recommended"),
        OPTIONAL("optional"),
        SPEC_ONLY("spec invoice only");

        final String text;

        Req(String text) {
            this.text = text;
        }
    }

    /** One tag, expected versus seen. */
    public static final class Row {
        /** "28.02" for a sub-tag. */
        public final String tag;
        public final String element;
        public final String expected;
        public final String got;
        public final BankQr.Status status;
        public final String note;

        Row(String tag, String element, String expected, String got, BankQr.Status status, String note) {
            this.tag = tag;
            this.element = element;
            this.expected = expected;
            this.got = got;
            this.status = status;
            this.note = note;
        }
    }

    public static final class Group {
        public final String title;
        public final List<Row> rows;
        /** Optional tags the QR does not carry; listed only as a count. */
        public final int absentOptional;

        Group(String title, List<Row> rows, int absentOptional) {
            this.title = title;
            this.rows = Collections.unmodifiableList(rows);
            this.absentOptional = absentOptional;
        }

        public BankQr.Status status() {
            BankQr.Status w = BankQr.Status.OK;
            for (Row r : rows) {
                if (r.status == BankQr.Status.FAIL) return BankQr.Status.FAIL;
                if (r.status == BankQr.Status.WARN) w = BankQr.Status.WARN;
            }
            return w;
        }
    }

    public final List<Group> groups;
    public final BankQr.Status status;
    /** One line for a header chip. */
    public final String summary;

    private QrReport(List<Group> groups, BankQr.Status status, String summary) {
        this.groups = Collections.unmodifiableList(groups);
        this.status = status;
        this.summary = summary;
    }

    // ---------------------------------------------------------------- rule table

    private static final class Verdict {
        final BankQr.Status status;
        final String note;

        Verdict(BankQr.Status status, String note) {
            this.status = status;
            this.note = note;
        }
    }

    private static final Verdict GOOD = new Verdict(BankQr.Status.OK, null);

    private static Verdict warn(String note) {
        return new Verdict(BankQr.Status.WARN, note);
    }

    private static Verdict fail(String note) {
        return new Verdict(BankQr.Status.FAIL, note);
    }

    private static final class Ctx {
        final boolean dynamic;
        final Map<String, String> top;

        Ctx(boolean dynamic, Map<String, String> top) {
            this.dynamic = dynamic;
            this.top = top;
        }
    }

    private interface Check {
        Verdict apply(String value, Ctx ctx);
    }

    private static final class Rule {
        final String path;
        final String element;
        final Req req;
        final String format;
        final boolean template;
        final Check check;

        Rule(String path, String element, Req req, String format, boolean template, Check check) {
            this.path = path;
            this.element = element;
            this.req = req;
            this.format = format;
            this.template = template;
            this.check = check;
        }
    }

    private static Rule rule(String path, String element, Req req, String format, Check check) {
        return new Rule(path, element, req, format, false, check);
    }

    private static Rule template(String path, String element, Req req, String format) {
        return new Rule(path, element, req, format, true, (v, c) -> parsesFully(v) ? GOOD : fail("not valid TLV inside"));
    }

    private static Check length(int ok, int specMax) {
        return (v, c) -> v.isEmpty() ? fail("empty")
                : v.length() <= ok ? GOOD
                : v.length() <= specMax ? warn(v.length() + " characters: over EMVCo's " + ok + ", within the spec's " + specMax)
                : fail(v.length() + " characters, the limit is " + specMax);
    }

    private static Check maxLength(int max) {
        return (v, c) -> v.length() <= max ? GOOD : warn(v.length() + " characters, the limit is " + max);
    }

    private static Check pattern(String regex, boolean failOnMismatch, String note) {
        return (v, c) -> v.matches(regex) ? GOOD : failOnMismatch ? fail(note) : warn(note);
    }

    private static final List<Rule> RULES = buildRules();

    private static List<Rule> buildRules() {
        List<Rule> r = new ArrayList<>();
        r.add(rule("00", "Payload format indicator", Req.REQUIRED, "01",
                (v, c) -> "01".equals(v) ? GOOD : fail("must be 01")));
        r.add(rule("01", "Point of initiation", Req.REQUIRED, "11 static, 12 dynamic",
                (v, c) -> "11".equals(v) || "12".equals(v) ? GOOD : fail("must be 11 or 12")));
        r.add(template("28", "Raast merchant account", Req.REQUIRED, "template: 00 GUID, 01 BIC, 02 IBAN"));
        r.add(rule("28.00", "Raast GUID", Req.OPTIONAL, "text", (v, c) -> GOOD));
        r.add(rule("28.01", "Payee bank BIC", Req.REQUIRED, "8 or 11 letters/digits",
                pattern("[A-Za-z0-9]{8}([A-Za-z0-9]{3})?", false, "not an 8 or 11 character BIC")));
        r.add(rule("28.02", "Payee IBAN", Req.REQUIRED, "PK + 2 digits + 20 letters/digits (24)", (v, c) -> {
            if (!v.matches("PK\\d{2}[A-Z0-9]{20}")) return warn("not a 24 character PK IBAN");
            return mod97(v) ? GOOD : warn("mod-97 check fails (a test IBAN?)");
        }));
        r.add(rule("52", "Merchant category code", Req.REQUIRED, "4 digits",
                pattern("\\d{4}", true, "must be 4 digits")));
        r.add(rule("53", "Currency", Req.REQUIRED, "3 digits, 586 = PKR", (v, c) -> {
            if ("586".equals(v)) return GOOD;
            return v.matches("\\d{3}") ? warn("not PKR; a bank app pays in PKR") : fail("must be 3 digits");
        }));
        r.add(rule("54", "Amount", Req.DYNAMIC, "positive, 2 decimals, up to 13 characters", (v, c) -> {
            if (!v.matches("\\d+(\\.\\d+)?")) return fail("not a plain decimal number");
            if (new BigInteger(v.replace(".", "")).signum() == 0) return fail("zero cannot be paid");
            if (v.length() > 13) return fail(v.length() + " characters, the limit is 13");
            return v.matches("\\d+\\.\\d{2}") ? GOOD : warn("the spec wants exactly 2 decimals (Table 8)");
        }));
        r.add(rule("55", "Tip / fee indicator", Req.OPTIONAL, "01 payer tip, 02 fixed fee (56), 03 percent (57)", (v, c) -> {
            if (!v.matches("0[123]")) return warn("not 01, 02 or 03");
            if ("02".equals(v) && !c.top.containsKey("56")) return fail("55 = 02 needs tag 56");
            if ("03".equals(v) && !c.top.containsKey("57")) return fail("55 = 03 needs tag 57");
            return GOOD;
        }));
        r.add(rule("56", "Fixed fee", Req.OPTIONAL, "decimal", pattern("\\d+(\\.\\d+)?", false, "not a number")));
        r.add(rule("57", "Fee percent", Req.OPTIONAL, "decimal", pattern("\\d+(\\.\\d+)?", false, "not a number")));
        r.add(rule("58", "Country", Req.REQUIRED, "2 letters, PK", (v, c) -> {
            if ("PK".equals(v)) return GOOD;
            return v.matches("[A-Z]{2}") ? warn("not PK") : fail("must be 2 letters");
        }));
        r.add(rule("59", "Merchant name", Req.REQUIRED, "1-25 characters (the spec allows 30)", length(25, 30)));
        r.add(rule("60", "Merchant city", Req.OPTIONAL, "1-15 characters (the spec allows 20)", length(15, 20)));
        r.add(rule("61", "Postal code", Req.OPTIONAL, "up to 10 characters", maxLength(10)));
        r.add(template("62", "Additional data", Req.DYNAMIC_WARN, "template: 01 bill, 03 store, 05 reference, 07 terminal"));
        r.add(rule("62.01", "Bill number", Req.OPTIONAL, "1-30 characters", maxLength(30)));
        r.add(rule("62.02", "Mobile number", Req.OPTIONAL, "digits, or *** to ask the payer", (v, c) -> GOOD));
        r.add(rule("62.03", "Store label / merchant id", Req.OPTIONAL, "1-25 characters", maxLength(25)));
        r.add(rule("62.04", "Loyalty number", Req.OPTIONAL, "text, or *** to ask the payer", (v, c) -> GOOD));
        r.add(rule("62.05", "Reference label", Req.DYNAMIC_WARN, "1-30 characters, unique per terminal per day",
                (v, c) -> v.isEmpty() ? fail("empty") : v.length() <= 30 ? GOOD : warn(v.length() + " characters, the limit is 30")));
        r.add(rule("62.06", "Customer label", Req.OPTIONAL, "text", (v, c) -> GOOD));
        r.add(rule("62.07", "Terminal label", Req.RECOMMENDED, "1-30 characters", maxLength(30)));
        r.add(rule("62.08", "Purpose of payment", Req.OPTIONAL, "1-4 characters", maxLength(4)));
        r.add(rule("62.09", "Additional data request", Req.OPTIONAL, "E, A, M flags", (v, c) -> GOOD));
        r.add(rule("62.10", "Merchant tax id", Req.OPTIONAL, "text", (v, c) -> GOOD));
        r.add(rule("62.11", "Merchant channel", Req.OPTIONAL, "text", (v, c) -> GOOD));
        r.add(rule("62.50", "Bill due date", Req.OPTIONAL, "DDMMYYYY", (v, c) -> GOOD));
        r.add(rule("62.51", "Amount after due date", Req.OPTIONAL, "decimal", (v, c) -> GOOD));
        r.add(rule("63", "CRC", Req.REQUIRED, "4 hex digits, CRC-16/CCITT-FALSE", (v, c) -> {
            if (!v.matches("[0-9A-Fa-f]{4}")) return fail("must be 4 hex digits");
            return GOOD;
        }));
        r.add(rule("80", "Context / terminal id (spec)", Req.OPTIONAL, "text", (v, c) -> GOOD));
        r.add(template("84", "Request to pay", Req.OPTIONAL, "template: 00 GUID, 01 RTP id"));
        r.add(rule("84.00", "RTP GUID", Req.OPTIONAL, "text", (v, c) -> GOOD));
        r.add(rule("84.01", "RTP id", Req.OPTIONAL, "text", (v, c) -> GOOD));
        r.add(template("26", "Raast merchant account (spec form)", Req.SPEC_ONLY, "template: 26-00 IBAN"));
        r.add(rule("81", "Transaction nonce", Req.SPEC_ONLY, "16 hex digits",
                pattern("[0-9A-Fa-f]{16}", false, "not 16 hex digits")));
        r.add(rule("82", "Timestamp", Req.SPEC_ONLY, "YYYYMMDDThhmmssZ",
                pattern("\\d{8}T\\d{6}Z", false, "not YYYYMMDDThhmmssZ")));
        return Collections.unmodifiableList(r);
    }

    /** Top-level tags the table knows, derived from the table. */
    private static final Set<String> KNOWN = knownTop();

    private static Set<String> knownTop() {
        Set<String> s = new HashSet<>();
        for (Rule r : RULES) s.add(r.path.split("\\.")[0]);
        return s;
    }

    // ---------------------------------------------------------------- build

    public static QrReport of(String qr) {
        String raw = qr == null ? "" : qr;
        Map<String, String> top = RaastQr.parse(raw);
        boolean dynamic = "12".equals(top.get("01"));
        Ctx ctx = new Ctx(dynamic, top);

        List<Group> groups = new ArrayList<>();
        groups.add(new Group("Does the phone accept it? (decides 9000 or 6A80)", acceptRows(raw), 0));

        List<Row> needed = new ArrayList<>();
        List<Row> specOnly = new ArrayList<>();
        int absentNeeded = 0;
        int absentSpec = 0;
        for (Rule rule : RULES) {
            String value = lookup(top, rule.path);
            List<Row> into = rule.req == Req.SPEC_ONLY ? specOnly : needed;
            if (value == null) {
                Row absent = absentRow(rule, dynamic);
                if (absent == null) {
                    if (rule.req == Req.SPEC_ONLY) absentSpec++;
                    else absentNeeded++;
                } else {
                    into.add(absent);
                }
                continue;
            }
            Verdict v = rule.check.apply(value, ctx);
            into.add(new Row(rule.path, rule.element, expected(rule), show(rule, value), v.status, v.note));
        }
        groups.add(new Group("Tags the bank app reads", needed, absentNeeded));
        groups.add(new Group("Tap2Pay spec invoice tags (this app does not read them)", specOnly, absentSpec));

        List<Row> unknown = new ArrayList<>();
        for (Map.Entry<String, String> e : top.entrySet()) {
            if (KNOWN.contains(e.getKey())) continue;
            unknown.add(new Row(e.getKey(), "Unknown tag", "ignored by its length (Annex A)", clip(e.getValue()),
                    BankQr.Status.OK, null));
        }
        if (!unknown.isEmpty()) groups.add(new Group("Other tags in the QR", unknown, 0));

        int fails = 0;
        int warns = 0;
        for (Group g : groups) {
            for (Row row : g.rows) {
                if (row.status == BankQr.Status.FAIL) fails++;
                else if (row.status == BankQr.Status.WARN) warns++;
            }
        }
        BankQr.Status overall = fails > 0 ? BankQr.Status.FAIL : warns > 0 ? BankQr.Status.WARN : BankQr.Status.OK;
        String summary = fails == 0 && warns == 0 ? "every expected tag present and well formed"
                : (fails > 0 ? fails + (fails == 1 ? " problem" : " problems") : "")
                + (fails > 0 && warns > 0 ? ", " : "")
                + (warns > 0 ? warns + (warns == 1 ? " warning" : " warnings") : "");
        return new QrReport(groups, overall, summary);
    }

    private static List<Row> acceptRows(String qr) {
        List<Row> rows = new ArrayList<>();
        boolean starts = qr.startsWith("000201");
        rows.add(new Row("-", "Starts with", "000201", clip(qr.length() < 6 ? qr : qr.substring(0, 6)),
                starts ? BankQr.Status.OK : BankQr.Status.FAIL, starts ? null : "the phone refuses it: 6A80"));
        int crcAt = qr.length() - 8;
        boolean ends = qr.length() >= 12 && qr.startsWith("6304", crcAt);
        rows.add(new Row("-", "Ends with", "6304 + 4 hex digits",
                clip(qr.length() < 8 ? qr : qr.substring(qr.length() - 8)),
                ends ? BankQr.Status.OK : BankQr.Status.FAIL, ends ? null : "no CRC tag at the end: 6A80"));
        if (ends) {
            String got = qr.substring(crcAt + 4).toUpperCase(Locale.US);
            String want = Crc16.forPayloadPrefix(qr.substring(0, crcAt + 4));
            boolean same = want.equals(got);
            rows.add(new Row("63", "CRC value", want, got, same ? BankQr.Status.OK : BankQr.Status.FAIL,
                    same ? null : "the CRC does not match the QR: 6A80"));
        } else {
            rows.add(new Row("63", "CRC value", "(cannot be computed)", "-", BankQr.Status.FAIL, "no CRC to compare"));
        }
        BankQr.Node tail = lastNode(BankQr.of(qr));
        boolean whole = tail == null || !"--".equals(tail.tag);
        rows.add(new Row("-", "Every tag parses to the end", "each tag's length fits the QR",
                whole ? "yes" : "stops at: " + clip(tail.value),
                whole ? BankQr.Status.OK : BankQr.Status.WARN,
                whole ? null : "the phone only checks the CRC, but the bank app cannot read tags after this"));
        return rows;
    }

    private static BankQr.Node lastNode(BankQr q) {
        List<BankQr.Node> t = q.tree();
        return t.isEmpty() ? null : t.get(t.size() - 1);
    }

    /** A row for a tag the QR does not carry, or null when it is optional and only counted. */
    private static Row absentRow(Rule rule, boolean dynamic) {
        BankQr.Status s;
        String note;
        switch (rule.req) {
            case REQUIRED:
                s = BankQr.Status.FAIL;
                note = "missing";
                break;
            case DYNAMIC:
                if (!dynamic) return null;
                s = BankQr.Status.FAIL;
                note = "missing; a dynamic QR needs it";
                break;
            case DYNAMIC_WARN:
                if (!dynamic) return null;
                s = BankQr.Status.WARN;
                note = "missing from a dynamic QR";
                break;
            case RECOMMENDED:
                s = BankQr.Status.WARN;
                note = "missing";
                break;
            default:
                return null;
        }
        return new Row(rule.path, rule.element, expected(rule), "absent", s, note);
    }

    private static String expected(Rule rule) {
        return rule.req.text + " · " + rule.format;
    }

    private static String show(Rule rule, String value) {
        if (rule.template) {
            if (!parsesFully(value)) return clip(value);
            int n = RaastQr.parse(value).size();
            return "template, " + n + (n == 1 ? " sub-tag" : " sub-tags");
        }
        return clip(value);
    }

    private static String lookup(Map<String, String> top, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) return top.get(path);
        String parent = top.get(path.substring(0, dot));
        if (parent == null) return null;
        return RaastQr.parse(parent).get(path.substring(dot + 1));
    }

    private static String clip(String s) {
        return s.length() <= 44 ? s : s.substring(0, 43) + "…";
    }

    private static boolean parsesFully(String s) {
        if (s.isEmpty()) return false;
        int i = 0;
        while (i < s.length()) {
            if (i + 4 > s.length()) return false;
            int len;
            try {
                len = Integer.parseInt(s.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                return false;
            }
            if (len < 0 || i + 4 + len > s.length()) return false;
            i += 4 + len;
        }
        return true;
    }

    /** ISO 7064 mod 97-10 over the IBAN with its first four characters moved to the end. */
    static boolean mod97(String iban) {
        String s = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else if (c >= 'A' && c <= 'Z') digits.append(c - 'A' + 10);
            else return false;
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
