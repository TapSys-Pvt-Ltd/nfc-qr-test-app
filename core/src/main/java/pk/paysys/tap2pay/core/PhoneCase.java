package pk.paysys.tap2pay.core;

/**
 * How the test phone answers a POS push, listed on the screen in this order. {@link #ACCEPT} is the NBP app's
 * behaviour. The QR-kind cases answer exactly like it too, and also check that the POS sent the QR kind the tester
 * expected (static or dynamic, with or without an amount). {@link #REJECT_DATA} makes the bank app refuse the QR.
 */
public enum PhoneCase {
    ACCEPT(null, null, "Generic QR",
            "A normal customer phone with a bank app: any valid Raast QR is accepted.",
            "Takes the whole QR. Answers 9000 (accepted) if its format and CRC are valid, 6A80 (invalid data) if "
                    + "not. Any QR kind passes.",
            "\"Sent to the customer's phone\" or the same success message the POS shows for a real phone."),
    SQRC_NO_AMOUNT(RaastQr.TYPE_STATIC, Boolean.FALSE, "SQRC without amount",
            "A static merchant QR (SQRC, 01 = 11) with no amount: the customer types the amount in the app.",
            "Answers like a bank app (9000). PASS only if the QR is static and has no amount (no tag 54); "
                    + "any other kind is a FAIL that names what arrived.",
            "\"Sent to the customer's phone\"; the static QR screen stays up."),
    SQRC_WITH_AMOUNT(RaastQr.TYPE_STATIC, Boolean.TRUE, "SQRC with amount",
            "A static merchant QR (SQRC, 01 = 11) that also carries the amount the cashier entered.",
            "Answers like a bank app (9000). PASS only if the QR is static and has an amount above 0 (tag 54).",
            "\"Sent to the customer's phone\"; the amount on the POS matches the amount in the details."),
    DQRC_WITH_AMOUNT(RaastQr.TYPE_DYNAMIC, Boolean.TRUE, "DQRC with amount",
            "The normal Raast QR sale: a one-time dynamic QR (DQRC, 01 = 12) for the amount the cashier entered.",
            "Answers like a bank app (9000). PASS only if the QR is dynamic and has an amount above 0 (tag 54).",
            "\"Sent to the customer's phone\"; the amount on the POS matches the amount in the details."),
    DQRC_NO_AMOUNT(RaastQr.TYPE_DYNAMIC, Boolean.FALSE, "DQRC without amount",
            "A dynamic QR (01 = 12) with no amount. The Tap2Pay spec requires an amount on a dynamic QR, so a POS "
                    + "should normally never send this; use it to catch one that does.",
            "Answers like a bank app (9000). PASS only if the QR is dynamic and has no amount (no tag 54).",
            "\"Sent to the customer's phone\"."),
    REJECT_DATA(null, null, "Invalid QR",
            "The bank app cannot read the QR it was sent: wrong format, bad CRC or a missing field.",
            "Receives the whole QR, then answers 6A80 \"invalid data\" instead of 9000.",
            "A failure message (not \"sent\"), and the cashier can retry or show the QR to scan.");

    /** {@link RaastQr#TYPE_STATIC}, {@link RaastQr#TYPE_DYNAMIC}, or null for any. */
    public final String expectType;
    /** TRUE = tag 54 with an amount above 0, FALSE = no tag 54, null = either. */
    public final Boolean expectAmount;
    public final String label;
    /** The real-world situation the case stands for. */
    public final String simulates;
    /** What the test phone does, and when the phone-side result is PASS or FAIL. */
    public final String phoneDoes;
    /** What a correct POS shows. */
    public final String checkOnPos;

    PhoneCase(String expectType, Boolean expectAmount, String label, String simulates,
              String phoneDoes, String checkOnPos) {
        this.expectType = expectType;
        this.expectAmount = expectAmount;
        this.label = label;
        this.simulates = simulates;
        this.phoneDoes = phoneDoes;
        this.checkOnPos = checkOnPos;
    }

    /** True when the result also checks the QR kind. */
    public boolean checksQrKind() {
        return expectType != null || expectAmount != null;
    }

    /** "static QR without amount" etc., or null when any QR will do. */
    public String expectedKind() {
        if (!checksQrKind()) return null;
        String type = expectType == null ? "any" : expectType;
        String amount = expectAmount == null ? "" : expectAmount ? " with amount" : " without amount";
        return type + " QR" + amount;
    }

    /**
     * Null when {@code qr} is the kind this case expects, else why not. A tag 54 that is present but zero or not
     * a number fails both "with" and "without amount": the POS sent something the NBP app cannot show sensibly.
     */
    public String kindMismatch(RaastQr qr) {
        if (!checksQrKind()) return null;
        StringBuilder got = new StringBuilder(qr.type).append(" QR");
        RaastQr.AmountState a = qr.amountState();
        if (a == RaastQr.AmountState.POSITIVE) got.append(" with amount PKR ").append(qr.amount);
        else if (a == RaastQr.AmountState.NONE) got.append(" without amount");
        else got.append(" with an unusable amount \"").append(qr.amount).append('"');

        boolean typeOk = expectType == null || expectType.equals(qr.type);
        boolean amountOk = expectAmount == null
                || (expectAmount ? a == RaastQr.AmountState.POSITIVE : a == RaastQr.AmountState.NONE);
        if (typeOk && amountOk) return null;
        return "expected " + expectedKind() + ", got " + got;
    }

    /** The case with this name, or {@link #ACCEPT} for null or an unknown name. */
    public static PhoneCase parse(String name) {
        if (name != null) {
            for (PhoneCase c : values()) if (c.name().equalsIgnoreCase(name.trim())) return c;
        }
        return ACCEPT;
    }
}
