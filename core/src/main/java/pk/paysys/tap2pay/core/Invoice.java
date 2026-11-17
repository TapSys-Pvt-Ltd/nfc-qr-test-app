package pk.paysys.tap2pay.core;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Builds the Tap2Pay invoice object (spec Table 8) in the canonical element order of Clause 3.9. */
public final class Invoice {
    /** Canonical example from spec Clause 3.9 (234 bytes, CRC B00A). Its timestamp is stale by design. */
    public static final String CANONICAL =
            "00020101021226560024PK29PAYS000000112345670201092299999990211PAYSPKKAXXX"
                    + "5204541153035865406500.005802PK5911PAYSYS LABS6007KARACHI"
                    + "62390108INV123450508REF987650711PAYSYS-KHI1"
                    + "8010PAYSYS000181163F2A9C011B7E4D55821620261008T101530Z6304B00A";

    /** 12 = one-time invoice, 11 = static reusable (amount may be null). */
    public String pointOfInitiation = "12";
    public String iban = "PK29PAYS0000001123456702";
    public String alias = "229999999";
    public String bic = "PAYSPKKAXXX";
    public String mcc = "5411";
    public String currency = "586";
    public String amount = "500.00";
    public String country = "PK";
    public String dba = "PAYSYS LABS";
    public String city = "KARACHI";
    public String billNumber = "INV12345";
    /** Optional 62-03 (UTF-8 allowed); null = absent. */
    public String storeLabel;
    public String referenceLabel = "REF98765";
    public String terminalLabel = "PAYSYS-KHI1";
    /** Optional 62-08; null = absent. */
    public String purpose;
    public String terminalId = "PAYSYS0001";
    public String nonce = "3F2A9C011B7E4D55";
    public String timestamp = "20261008T101530Z";

    /** Canonical merchant data with a fresh nonce, current UTC timestamp and a new reference label. */
    public static Invoice fresh(String amount) {
        Invoice inv = new Invoice();
        byte[] n = new byte[8];
        new SecureRandom().nextBytes(n);
        inv.nonce = Hex.encode(n).replace(" ", "");
        inv.timestamp = timestampUtc(System.currentTimeMillis());
        inv.referenceLabel = "REF" + inv.timestamp.substring(9, 15);
        inv.amount = amount;
        return inv;
    }

    /** Static QR (point of initiation 11, no amount): what a sticker or the tag route offers. */
    public static String staticQr() {
        Invoice inv = fresh(null);
        inv.pointOfInitiation = "11";
        return inv.toPayload();
    }

    public static String timestampUtc(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    /** Serializes in the canonical element order of Clause 3.9; null optional elements are omitted. */
    public String toPayload() {
        String account = Tlv.element("00", iban) + opt("01", alias) + opt("02", bic);
        String additional = opt("01", billNumber) + opt("03", storeLabel) + Tlv.element("05", referenceLabel)
                + Tlv.element("07", terminalLabel) + opt("08", purpose);
        String body = Tlv.element("00", "01") + Tlv.element("01", pointOfInitiation)
                + Tlv.element("26", account)
                + Tlv.element("52", mcc) + Tlv.element("53", currency) + opt("54", amount)
                + Tlv.element("58", country) + Tlv.element("59", dba) + opt("60", city)
                + Tlv.element("62", additional)
                + Tlv.element("80", terminalId) + Tlv.element("81", nonce) + Tlv.element("82", timestamp)
                + "6304";
        return body + Crc16.forPayloadPrefix(body);
    }

    private static String opt(String tag, String value) {
        return value == null ? "" : Tlv.element(tag, value);
    }
}
