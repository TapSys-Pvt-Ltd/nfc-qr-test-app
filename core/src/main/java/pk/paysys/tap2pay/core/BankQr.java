package pk.paysys.tap2pay.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a bank app reads out of a pushed Raast P2M QR to send the payment. The field mapping is the one the NBP
 * app uses after login ({@code LoginQrpayment.js}, which builds {@code qrDetails}): merchant name 59, city 60,
 * category 52, amount 54, tip indicator 55 / fixed 56 / percent 57, template 28 (00 GUID, 01 BIC, 02 IBAN),
 * template 62 (01 bill no, 02 mobile, 03 store label / merchant id, 04 loyalty, 05 reference, 06 customer label,
 * 07 terminal, 08 purpose, 09 additional data request, 10 tax id, 11 channel, 50 due date, 51 amount after due
 * date), 80 context, 84 RTP (00 GUID, 01 RTP id). The NBP app only looks in template 28 for the payee account, so
 * so does this class.
 *
 * Parsing is lenient, like the app: a QR with odd TLV still gets whatever fields could be read.
 */
public final class BankQr {
    public enum Status { OK, WARN, FAIL }

    /** One line of the details screen. {@code value} is null when the QR does not carry the field. */
    public static final class Row {
        public final String label;
        public final String value;
        /** Where it sits in the QR, e.g. "28.02". */
        public final String ref;
        /** A payment cannot be sent without it. */
        public final boolean required;

        public Row(String label, String value, String ref, boolean required) {
            this.label = label;
            this.value = value == null || value.isEmpty() ? null : value;
            this.ref = ref;
            this.required = required;
        }

        public boolean present() {
            return value != null;
        }
    }

    public static final class Section {
        public final String title;
        public final List<Row> rows;

        Section(String title, List<Row> rows) {
            this.title = title;
            this.rows = Collections.unmodifiableList(rows);
        }
    }

    /** One item of the "can the bank app send this payment" list. */
    public static final class Check {
        public final String label;
        public final Status status;
        public final String detail;

        Check(String label, Status status, String detail) {
            this.label = label;
            this.status = status;
            this.detail = detail;
        }
    }

    /** One element of the QR in wire order; {@code depth} 1 is inside a template. */
    public static final class Node {
        public final int depth;
        public final String tag;
        public final int length;
        public final String value;
        /** True for a template whose children follow it in the list; its {@code value} is then null. */
        public final boolean template;

        Node(int depth, String tag, int length, String value, boolean template) {
            this.depth = depth;
            this.tag = tag;
            this.length = length;
            this.value = value;
            this.template = template;
        }
    }

    public final String raw;
    /** The NBP app's acceptance rule (see {@link RaastQr}). */
    public final boolean valid;
    public final String problem;

    /** "dynamic" (01 = 12), "static" (01 = 11), else the raw 01 value or "unknown". */
    public final String type;
    public final String merchantName;
    public final String city;
    public final String country;
    public final String postalCode;
    public final String categoryCode;
    public final String currencyCode;
    public final String amount;
    public final String tipIndicator;
    public final String fixedFee;
    public final String feePercent;
    public final String guid;
    public final String bic;
    public final String iban;
    public final String billNumber;
    public final String mobile;
    public final String storeLabel;
    public final String loyalty;
    public final String referenceLabel;
    public final String customerLabel;
    public final String terminalId;
    public final String purpose;
    public final String additionalDataRequest;
    public final String taxId;
    public final String channel;
    public final String billDueDate;
    public final String amountAfterDueDate;
    public final String context;
    public final String rtpGuid;
    public final String rtpId;

    private final Map<String, String> top;

    private BankQr(String raw) {
        this.raw = raw;
        String why = RaastQr.problemWith(raw);
        this.valid = why == null;
        this.problem = why;
        this.top = RaastQr.parse(raw);
        Map<String, String> account = RaastQr.parse(top.get("28"));
        Map<String, String> additional = RaastQr.parse(top.get("62"));
        Map<String, String> rtp = RaastQr.parse(top.get("84"));

        String pim = top.get("01");
        this.type = "12".equals(pim) ? RaastQr.TYPE_DYNAMIC : "11".equals(pim) ? RaastQr.TYPE_STATIC
                : pim == null ? "unknown" : pim;
        this.merchantName = top.get("59");
        this.city = top.get("60");
        this.country = top.get("58");
        this.postalCode = top.get("61");
        this.categoryCode = top.get("52");
        this.currencyCode = top.get("53");
        this.amount = top.get("54");
        this.tipIndicator = top.get("55");
        this.fixedFee = top.get("56");
        this.feePercent = top.get("57");
        this.guid = account.get("00");
        this.bic = account.get("01");
        this.iban = account.get("02");
        this.billNumber = additional.get("01");
        this.mobile = additional.get("02");
        this.storeLabel = additional.get("03");
        this.loyalty = additional.get("04");
        this.referenceLabel = additional.get("05");
        this.customerLabel = additional.get("06");
        this.terminalId = additional.get("07");
        this.purpose = additional.get("08");
        this.additionalDataRequest = additional.get("09");
        this.taxId = additional.get("10");
        this.channel = additional.get("11");
        this.billDueDate = additional.get("50");
        this.amountAfterDueDate = additional.get("51");
        this.context = top.get("80");
        this.rtpGuid = rtp.get("00");
        this.rtpId = rtp.get("01");
    }

    public static BankQr of(String qr) {
        return new BankQr(qr);
    }

    public boolean isDynamic() {
        return RaastQr.TYPE_DYNAMIC.equals(type);
    }

    /** What tag 54 says: absent, above 0, or present but zero / not a number. */
    public RaastQr.AmountState amountState() {
        if (amount == null) return RaastQr.AmountState.NONE;
        BigDecimal v = decimal(amount);
        return v != null && v.signum() > 0 ? RaastQr.AmountState.POSITIVE : RaastQr.AmountState.UNUSABLE;
    }

    /**
     * Amount to debit as the NBP app works it out: 54, plus 56 when 55 = 02, plus 54 * 57 / 100 when 55 = 03.
     * Null when 54 is absent or unusable, or the fee tag the indicator points to is missing or not a number. When
     * 55 = 01 the payer types the tip, so the amount is the base and {@link #totalNote()} says so.
     */
    public BigDecimal totalAmount() {
        if (amountState() != RaastQr.AmountState.POSITIVE) return null;
        BigDecimal base = decimal(amount);
        if ("02".equals(tipIndicator)) {
            BigDecimal fee = decimal(fixedFee);
            return fee == null ? null : base.add(fee).setScale(2, RoundingMode.HALF_UP);
        }
        if ("03".equals(tipIndicator)) {
            BigDecimal pct = decimal(feePercent);
            return pct == null ? null
                    : base.add(base.multiply(pct).divide(BigDecimal.valueOf(100))).setScale(2, RoundingMode.HALF_UP);
        }
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    /** Short words for what is still to be decided on the payer's side, or null. */
    public String totalNote() {
        switch (amountState()) {
            case NONE: return "payer enters the amount";
            case UNUSABLE: return "unusable amount in the QR (54 = " + amount + ")";
            default: break;
        }
        if ("01".equals(tipIndicator)) return "plus a tip the payer enters";
        if (("02".equals(tipIndicator) && decimal(fixedFee) == null)
                || ("03".equals(tipIndicator) && decimal(feePercent) == null)) {
            return "fee tag " + ("02".equals(tipIndicator) ? "56" : "57") + " missing or not a number";
        }
        return null;
    }

    /** "PKR" for 586, the raw code for another, null when 53 is absent. */
    public String currency() {
        if (currencyCode == null) return null;
        return "586".equals(currencyCode) ? "PKR" : currencyCode;
    }

    public static String money(BigDecimal v) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(v);
    }

    public static String tipText(String indicator) {
        if (indicator == null) return null;
        switch (indicator) {
            case "01": return "01 · payer enters a tip";
            case "02": return "02 · fixed fee (56)";
            case "03": return "03 · percentage fee (57)";
            default: return indicator;
        }
    }

    /** The rows a bank app shows before it sends the payment, in groups. A row the QR lacks has a null value. */
    public List<Section> sections() {
        List<Section> out = new ArrayList<>();

        List<Row> merchant = new ArrayList<>();
        merchant.add(new Row("Merchant name", merchantName, "59", true));
        merchant.add(new Row("City", city, "60", false));
        merchant.add(new Row("Country", country, "58", false));
        merchant.add(new Row("Category code (MCC)", categoryCode, "52", false));
        merchant.add(new Row("Postal code", postalCode, "61", false));
        out.add(new Section("Pay to", merchant));

        List<Row> account = new ArrayList<>();
        account.add(new Row("IBAN", iban, "28.02", true));
        account.add(new Row("Bank BIC", bic, "28.01", true));
        account.add(new Row("Raast GUID", guid, "28.00", false));
        out.add(new Section("Payee account", account));

        List<Row> money = new ArrayList<>();
        money.add(new Row("QR type", typeText(), "01", false));
        money.add(new Row("Amount", amount, "54", false));
        money.add(new Row("Currency", currencyCode == null ? null
                : "586".equals(currencyCode) ? "PKR (586)" : currencyCode, "53", true));
        money.add(new Row("Tip / fee", tipText(tipIndicator), "55", false));
        money.add(new Row("Fixed fee", fixedFee, "56", false));
        money.add(new Row("Fee percent", feePercent, "57", false));
        BigDecimal total = totalAmount();
        money.add(new Row("Total to debit", total == null ? null : currency() + " " + money(total), "", false));
        money.add(new Row("Bill due date", billDueDate, "62.50", false));
        money.add(new Row("Amount after due date", amountAfterDueDate, "62.51", false));
        out.add(new Section("Amount", money));

        List<Row> refs = new ArrayList<>();
        refs.add(new Row("Reference label", referenceLabel, "62.05", false));
        refs.add(new Row("RTP id", rtpId, "84.01", false));
        refs.add(new Row("RTP GUID", rtpGuid, "84.00", false));
        refs.add(new Row("Bill number", billNumber, "62.01", false));
        refs.add(new Row("Store label / merchant id", storeLabel, "62.03", false));
        refs.add(new Row("Terminal id", terminalId, "62.07", false));
        refs.add(new Row("Customer label", customerLabel, "62.06", false));
        refs.add(new Row("Mobile number", mobile, "62.02", false));
        refs.add(new Row("Loyalty number", loyalty, "62.04", false));
        refs.add(new Row("Purpose of payment", purpose, "62.08", false));
        refs.add(new Row("Additional data request", additionalDataRequest, "62.09", false));
        refs.add(new Row("Merchant tax id", taxId, "62.10", false));
        refs.add(new Row("Merchant channel", channel, "62.11", false));
        refs.add(new Row("Context of transaction", context, "80", false));
        out.add(new Section("References", refs));
        return out;
    }

    private String typeText() {
        if (isDynamic()) return "Dynamic QR (12)";
        if (RaastQr.TYPE_STATIC.equals(type)) return "Static QR (11)";
        return "unknown".equals(type) ? null : type;
    }

    /** Can the bank app send this payment from the QR alone. */
    public List<Check> checks() {
        List<Check> out = new ArrayList<>();
        out.add(valid ? new Check("QR accepted", Status.OK, "starts 000201, CRC matches")
                : new Check("QR accepted", Status.FAIL, problem));

        if (iban == null) {
            out.add(new Check("Payee IBAN (28.02)", Status.FAIL, top.containsKey("28")
                    ? "template 28 has no sub-tag 02" : "no template 28; a bank app reads the payee only from it"));
        } else if (!iban.matches("PK\\d{2}[A-Z0-9]{20}")) {
            out.add(new Check("Payee IBAN (28.02)", Status.WARN, iban + " is not a 24-character PK IBAN"));
        } else {
            out.add(new Check("Payee IBAN (28.02)", Status.OK, iban));
        }
        out.add(bic == null ? new Check("Payee bank BIC (28.01)", Status.FAIL, "missing")
                : new Check("Payee bank BIC (28.01)", Status.OK, bic));
        out.add(merchantName == null ? new Check("Merchant name (59)", Status.FAIL, "missing")
                : new Check("Merchant name (59)", Status.OK, merchantName));
        if (currencyCode == null) {
            out.add(new Check("Currency (53)", Status.FAIL, "missing"));
        } else if (!"586".equals(currencyCode)) {
            out.add(new Check("Currency (53)", Status.WARN, currencyCode + " is not PKR (586); a bank app pays in PKR"));
        } else {
            out.add(new Check("Currency (53)", Status.OK, "PKR"));
        }
        switch (amountState()) {
            case POSITIVE:
                String note = totalNote();
                out.add(new Check("Amount (54)", note == null ? Status.OK : Status.WARN,
                        amount + (note == null ? "" : ", " + note)));
                break;
            case NONE:
                out.add(new Check("Amount (54)", Status.WARN, "not in the QR, the payer enters it"));
                break;
            default:
                out.add(new Check("Amount (54)", Status.FAIL, "54 = " + amount + " cannot be paid"));
                break;
        }
        if (isDynamic()) {
            boolean ref = referenceLabel != null || rtpId != null;
            out.add(ref ? new Check("Dynamic QR reference", Status.OK,
                    (referenceLabel != null ? "62.05 " + referenceLabel : "") + (referenceLabel != null && rtpId != null ? ", " : "")
                            + (rtpId != null ? "RTP " + rtpId : ""))
                    : new Check("Dynamic QR reference", Status.WARN, "no 62.05 or 84.01 to tie the payment to this QR"));
        }
        return out;
    }

    /**
     * Every element in wire order, with templates unfolded. A value is unfolded when its tag is in a template
     * range of EMV merchant QR (26-51, 62, 64, 80-99) and it parses cleanly to its last character; anything else is
     * a leaf. A top level that stops parsing early ends with a "--" node holding the rest.
     */
    public List<Node> tree() {
        List<Node> out = new ArrayList<>();
        int end = walk(raw == null ? "" : raw, 0, out, true);
        if (raw != null && end < raw.length()) out.add(new Node(0, "--", raw.length() - end, raw.substring(end), false));
        return out;
    }

    /** Appends the elements of {@code s}; returns how many characters were consumed. */
    private static int walk(String s, int depth, List<Node> out, boolean lenient) {
        int i = 0;
        while (i + 4 <= s.length()) {
            int len;
            try {
                len = Integer.parseInt(s.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                break;
            }
            if (len < 0 || i + 4 + len > s.length()) break;
            String tag = s.substring(i, i + 2);
            String value = s.substring(i + 4, i + 4 + len);
            if (isTemplateTag(tag) && parsesFully(value)) {
                out.add(new Node(depth, tag, len, null, true));
                walk(value, depth + 1, out, false);
            } else {
                out.add(new Node(depth, tag, len, value, false));
            }
            i += 4 + len;
        }
        return i;
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

    static boolean isTemplateTag(String tag) {
        try {
            int t = Integer.parseInt(tag);
            return (t >= 26 && t <= 51) || t == 62 || t == 64 || t >= 80;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static BigDecimal decimal(String s) {
        if (s == null || !s.matches("\\d+(\\.\\d+)?")) return null;
        return new BigDecimal(s);
    }

    /** Values keyed by tag, in wire order, for tests and logs. */
    Map<String, String> topLevel() {
        return new LinkedHashMap<>(top);
    }
}
