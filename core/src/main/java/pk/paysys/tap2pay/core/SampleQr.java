package pk.paysys.tap2pay.core;

import java.math.BigDecimal;

/**
 * Made-up Raast merchant QRs in the live FBL layout, for the POC POS reader to push all four kinds the phone test
 * app checks: static or dynamic, with or without an amount. The real Urovo FBL app only makes a dynamic QR with an
 * amount and (frontend) a static QR without one, so the other two kinds come from here. Merchant name
 * "Tap2Pay Test" marks them as synthetic.
 */
public final class SampleQr {
    private SampleQr() {}

    /**
     * @param dynamic   01 = 12 (with a one-time reference 62.05 and an RTP template 84) or 01 = 11 (neither)
     * @param amount    tag 54, e.g. "2.00"; null or blank for none. Must be a plain positive decimal.
     * @param reference 62.05 for a dynamic QR (1 to 25 chars, so repeated taps can differ); ignored for static
     */
    public static String build(boolean dynamic, String amount, String reference) {
        String amt = amount == null ? "" : amount.trim();
        if (!amt.isEmpty() && (!amt.matches("\\d+(\\.\\d+)?") || new BigDecimal(amt).signum() <= 0
                || amt.length() > 13)) {
            throw new IllegalArgumentException("amount must be a positive decimal of up to 13 chars: " + amount);
        }
        if (dynamic && (reference == null || reference.isEmpty() || reference.length() > 25)) {
            throw new IllegalArgumentException("a dynamic QR needs a 1-25 char reference");
        }
        StringBuilder qr = new StringBuilder();
        qr.append(tlv("00", "01"));
        qr.append(tlv("01", dynamic ? "12" : "11"));
        qr.append(tlv("28", tlv("00", "2611061516150000001") + tlv("01", "FAYSPKKA")
                + tlv("02", "PK00TEST0000000000000001")));
        qr.append(tlv("52", "1750"));
        qr.append(tlv("53", "586"));
        if (!amt.isEmpty()) qr.append(tlv("54", amt));
        qr.append(tlv("58", "PK"));
        qr.append(tlv("59", "Tap2Pay Test"));
        qr.append(tlv("60", "Karachi"));
        String additional = tlv("03", "999000000000001") + (dynamic ? tlv("05", reference) : "")
                + tlv("07", "00009999");
        qr.append(tlv("62", additional));
        if (dynamic) {
            qr.append(tlv("84", tlv("00", "2611061516150000003")
                    + tlv("01", "00000000-1111-2222-3333-444444444444")));
        }
        qr.append("6304");
        return qr.append(Crc16.forPayloadPrefix(qr.toString())).toString();
    }

    static String tlv(String id, String value) {
        if (value.length() > 99) throw new IllegalArgumentException("tag " + id + " longer than 99");
        return id + (value.length() < 10 ? "0" : "") + value.length() + value;
    }
}
