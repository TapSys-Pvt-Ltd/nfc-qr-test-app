package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigInteger;

/** One test per QrReport row of bank-app/README.md (rows 90-103). */
public class QrReportTest {
    private static String tlv(String id, String v) {
        return SampleQr.tlv(id, v);
    }

    /** A PK IBAN whose mod-97 check digits are right. */
    private static String goodIban() {
        String bban = "NBPA0000000000000001";
        StringBuilder digits = new StringBuilder();
        for (char c : (bban + "PK00").toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        int cc = 98 - new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
        return "PK" + String.format("%02d", cc) + bban;
    }

    private static String account(String iban) {
        return tlv("28", tlv("00", "2611061516150000001") + tlv("01", "FAYSPKKA") + tlv("02", iban));
    }

    /** A signed QR; pim is 01's value, parts go after the account template. */
    private static String qr(String pim, String parts) {
        return PhoneFixtures.resign(tlv("00", "01") + tlv("01", pim) + account(goodIban()) + parts);
    }

    private static String full(String pim, String amount) {
        return qr(pim, tlv("52", "5411") + tlv("53", "586") + (amount == null ? "" : tlv("54", amount))
                + tlv("58", "PK") + tlv("59", "Test Cafe") + tlv("60", "Karachi")
                + tlv("62", tlv("03", "S1") + tlv("05", "REF123") + tlv("07", "T01")));
    }

    private static QrReport.Row row(QrReport r, String tag) {
        for (QrReport.Group g : r.groups) for (QrReport.Row row : g.rows) if (row.tag.equals(tag)) return row;
        return null;
    }

    private static QrReport.Row need(QrReport r, String tag) {
        QrReport.Row row = row(r, tag);
        assertNotNull("no row for tag " + tag, row);
        return row;
    }

    private static QrReport.Group group(QrReport r, int i) {
        return r.groups.get(i);
    }

    // row 90: a QR with everything in order
    @Test
    public void aWellFormedDynamicQrHasNoProblemAndNoWarning() {
        QrReport r = QrReport.of(full("12", "2.00"));
        for (QrReport.Group g : r.groups) {
            for (QrReport.Row row : g.rows) assertEquals(row.tag + " " + row.note, BankQr.Status.OK, row.status);
        }
        assertEquals(BankQr.Status.OK, r.status);
        assertEquals("every expected tag present and well formed", r.summary);
        assertEquals(4, group(r, 0).rows.size());
        assertEquals("000201", need(r, "-").got);
        assertEquals("586", need(r, "53").got);
        assertTrue(group(r, 1).absentOptional > 0);
        assertEquals("template, 3 sub-tags", need(r, "28").got);
        assertEquals(goodIban(), need(r, "28.02").got);
    }

    // row 91: the tags a bank app cannot pay without
    @Test
    public void everyRequiredTagThatIsMissingFails() {
        QrReport r = QrReport.of(PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11")));
        for (String tag : new String[] {"28", "28.01", "28.02", "52", "53", "58", "59"}) {
            QrReport.Row row = need(r, tag);
            assertEquals(tag, BankQr.Status.FAIL, row.status);
            assertEquals(tag, "absent", row.got);
            assertTrue(tag, row.expected.startsWith("required"));
        }
        assertEquals(BankQr.Status.FAIL, r.status);
        assertTrue(r.summary, r.summary.contains("problems"));
        assertEquals(BankQr.Status.OK, need(r, "00").status);
        assertEquals(BankQr.Status.OK, need(r, "63").status);
    }

    // row 92: the amount depends on the QR type
    @Test
    public void theAmountIsRequiredOnlyForADynamicQr() {
        QrReport dyn = QrReport.of(full("12", null));
        assertEquals(BankQr.Status.FAIL, need(dyn, "54").status);
        assertEquals("missing; a dynamic QR needs it", need(dyn, "54").note);
        QrReport stat = QrReport.of(full("11", null));
        assertNull("a static QR may leave it out", row(stat, "54"));
        assertEquals(BankQr.Status.OK, stat.status);
    }

    // row 93: amount formats
    @Test
    public void amountFormatsAreJudged() {
        assertEquals(BankQr.Status.OK, need(QrReport.of(full("12", "1250.50")), "54").status);
        assertEquals(BankQr.Status.WARN, need(QrReport.of(full("12", "2.0")), "54").status);
        assertEquals(BankQr.Status.WARN, need(QrReport.of(full("12", "100")), "54").status);
        for (String bad : new String[] {"0.00", "0", "abc", "1,5", "-5", "12345678901.00"}) {
            assertEquals(bad, BankQr.Status.FAIL, need(QrReport.of(full("12", bad)), "54").status);
        }
        assertEquals("zero cannot be paid", need(QrReport.of(full("12", "0.00")), "54").note);
    }

    // row 94: tip and fee indicators
    @Test
    public void aFeeIndicatorNeedsItsFeeTag() {
        String base = tlv("52", "5411") + tlv("53", "586") + tlv("54", "100.00");
        String tail = tlv("58", "PK") + tlv("59", "Cafe") + tlv("62", tlv("05", "R"));
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(qr("12", base + tlv("55", "02") + tail)), "55").status);
        assertEquals(BankQr.Status.OK, need(QrReport.of(qr("12", base + tlv("55", "02") + tlv("56", "5.50") + tail)), "55").status);
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(qr("12", base + tlv("55", "03") + tail)), "55").status);
        assertEquals(BankQr.Status.OK, need(QrReport.of(qr("12", base + tlv("55", "03") + tlv("57", "2.5") + tail)), "55").status);
        assertEquals(BankQr.Status.OK, need(QrReport.of(qr("12", base + tlv("55", "01") + tail)), "55").status);
        assertEquals(BankQr.Status.WARN, need(QrReport.of(qr("12", base + tlv("55", "09") + tail)), "55").status);
        assertEquals(BankQr.Status.WARN, need(QrReport.of(qr("12", base + tlv("56", "x") + tail)), "56").status);
    }

    // row 95: length limits
    @Test
    public void nameAndCityLengthsHaveAnEmvLimitAndALooserSpecLimit() {
        String a25 = "ABCDEFGHIJKLMNOPQRSTUVWXY";
        assertEquals(BankQr.Status.OK, need(QrReport.of(nameQr(a25, "Karachi")), "59").status);
        assertEquals(BankQr.Status.WARN, need(QrReport.of(nameQr(a25 + "Z", "Karachi")), "59").status);
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(nameQr(a25 + "ZZZZZZ", "Karachi")), "59").status);
        assertEquals(BankQr.Status.OK, need(QrReport.of(nameQr("N", "123456789012345")), "60").status);
        assertEquals(BankQr.Status.WARN, need(QrReport.of(nameQr("N", "1234567890123456")), "60").status);
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(nameQr("N", "123456789012345678901")), "60").status);
    }

    private static String nameQr(String name, String city) {
        return qr("11", tlv("52", "5411") + tlv("53", "586") + tlv("58", "PK") + tlv("59", name) + tlv("60", city));
    }

    // row 96: tags only the spec's invoice uses
    @Test
    public void specOnlyTagsAreShownSeparatelyAndNeverRequired() {
        QrReport plain = QrReport.of(full("12", "2.00"));
        assertEquals(0, group(plain, 2).rows.size());
        assertEquals(3, group(plain, 2).absentOptional);
        assertEquals(BankQr.Status.OK, group(plain, 2).status());

        String withSpec = PhoneFixtures.resign(tlv("00", "01") + tlv("01", "12") + account(goodIban())
                + tlv("26", tlv("00", goodIban())) + tlv("52", "5411") + tlv("53", "586") + tlv("54", "2.00")
                + tlv("58", "PK") + tlv("59", "Cafe") + tlv("62", tlv("05", "R")) + tlv("81", "0123456789ABCDEF")
                + tlv("82", "20261117T101500Z"));
        QrReport r = QrReport.of(withSpec);
        assertEquals("template, 1 sub-tag", need(r, "26").got);
        assertEquals(BankQr.Status.OK, need(r, "81").status);
        assertEquals(BankQr.Status.OK, need(r, "82").status);
        assertEquals(3, group(r, 2).rows.size());

        String badSpec = PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11") + tlv("81", "xyz") + tlv("82", "yesterday"));
        QrReport b = QrReport.of(badSpec);
        assertEquals(BankQr.Status.WARN, need(b, "81").status);
        assertEquals(BankQr.Status.WARN, need(b, "82").status);
    }

    // row 97: unknown tags
    @Test
    public void anUnknownTagIsListedAsIgnored() {
        QrReport r = QrReport.of(qr("11", tlv("52", "5411") + tlv("53", "586") + tlv("58", "PK") + tlv("59", "C")
                + tlv("75", "mystery")));
        QrReport.Row row = need(r, "75");
        assertEquals("Unknown tag", row.element);
        assertEquals("mystery", row.got);
        assertEquals(BankQr.Status.OK, row.status);
        assertEquals("Other tags in the QR", r.groups.get(r.groups.size() - 1).title);
        assertEquals(3, QrReport.of(full("11", null)).groups.size());
    }

    // row 98: what decides 9000 or 6A80
    @Test
    public void aBadCrcOrAWrongStartFailsTheAcceptanceGroupAndShowsTheCrcItShouldHave() {
        String good = full("12", "2.00");
        String bad = good.substring(0, good.length() - 4) + "0000";
        QrReport r = QrReport.of(bad);
        QrReport.Row crc = need(r, "63");
        assertEquals(BankQr.Status.FAIL, group(r, 0).status());
        assertEquals(good.substring(good.length() - 4), crc.expected);
        assertEquals("0000", crc.got);

        for (String junk : new String[] {null, "", "MR:4210112345671|Amount:100", "000201", "x"}) {
            QrReport j = QrReport.of(junk);
            assertEquals(String.valueOf(junk), BankQr.Status.FAIL, group(j, 0).status());
            assertEquals(BankQr.Status.FAIL, j.status);
        }
        QrReport mr = QrReport.of("MR:4210112345671|Amount:100");
        assertEquals("MR:421", group(mr, 0).rows.get(0).got);
    }

    // row 99: IBAN
    @Test
    public void theIbanIsCheckedForShapeAndMod97() {
        assertTrue(QrReport.mod97("PK36SCBL0000001123456702"));
        assertFalse(QrReport.mod97("PK37SCBL0000001123456702"));
        assertFalse(QrReport.mod97("PK36SCBL000000112345670!"));
        assertEquals(BankQr.Status.OK, need(QrReport.of(full("12", "2.00")), "28.02").status);
        QrReport test = QrReport.of(PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11") + account("PK00TEST0000000000000001")));
        assertEquals(BankQr.Status.WARN, need(test, "28.02").status);
        assertTrue(need(test, "28.02").note, need(test, "28.02").note.contains("mod-97"));
        QrReport shape = QrReport.of(PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11") + account("1234567890")));
        assertEquals("not a 24 character PK IBAN", need(shape, "28.02").note);
    }

    // row 100: optional tags that are absent are counted, not listed
    @Test
    public void absentOptionalTagsAreOnlyCounted() {
        QrReport r = QrReport.of(full("12", "2.00"));
        assertNull(row(r, "62.04"));
        assertNull(row(r, "84"));
        assertNull(row(r, "56"));
        int listed = group(r, 1).rows.size();
        assertTrue(listed > 10);
        assertTrue(group(r, 1).absentOptional >= 10);
    }

    // row 101: a QR whose tags stop parsing
    @Test
    public void aTailThatDoesNotParseIsAWarningInTheAcceptanceGroup() {
        String s = PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11") + "5999abc");
        QrReport r = QrReport.of(s);
        QrReport.Row row = group(r, 0).rows.get(3);
        assertEquals("Every tag parses to the end", row.element);
        assertEquals(BankQr.Status.WARN, row.status);
        assertTrue(row.got, row.got.startsWith("stops at: 5999abc"));
        assertEquals(BankQr.Status.OK, group(r, 0).rows.get(2).status);
    }

    // row 102: the reference of a dynamic QR
    @Test
    public void aDynamicQrWithoutAReferenceOrAdditionalDataWarns() {
        String noRef = qr("12", tlv("52", "5411") + tlv("53", "586") + tlv("54", "2.00") + tlv("58", "PK") + tlv("59", "C"));
        QrReport r = QrReport.of(noRef);
        assertEquals(BankQr.Status.WARN, need(r, "62").status);
        assertEquals(BankQr.Status.WARN, need(r, "62.05").status);
        assertEquals(BankQr.Status.WARN, need(r, "62.07").status);
        QrReport stat = QrReport.of(qr("11", tlv("52", "5411") + tlv("53", "586") + tlv("58", "PK") + tlv("59", "C")));
        assertNull(row(stat, "62"));
        assertNull(row(stat, "62.05"));
        assertEquals(BankQr.Status.WARN, need(stat, "62.07").status);
    }

    // row 103: templates that are not TLV inside
    @Test
    public void aTemplateThatIsNotTlvInsideFailsAndItsSubTagsAreMissing() {
        String s = PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11") + tlv("28", "garbage!!") + tlv("59", "C"));
        QrReport r = QrReport.of(s);
        assertEquals(BankQr.Status.FAIL, need(r, "28").status);
        assertEquals("not valid TLV inside", need(r, "28").note);
        assertEquals(BankQr.Status.FAIL, need(r, "28.02").status);
    }

    // row 104: currency and country
    @Test
    public void currencyAndCountryAreJudged() {
        assertEquals(BankQr.Status.WARN, need(QrReport.of(qr("11", tlv("53", "840") + tlv("58", "US") + tlv("59", "C"))), "53").status);
        assertEquals(BankQr.Status.WARN, need(QrReport.of(qr("11", tlv("53", "840") + tlv("58", "US") + tlv("59", "C"))), "58").status);
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(qr("11", tlv("53", "PKR") + tlv("58", "P") + tlv("59", "C"))), "53").status);
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(qr("11", tlv("53", "PKR") + tlv("58", "P") + tlv("59", "C"))), "58").status);
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(qr("11", tlv("52", "54") + tlv("59", "C"))), "52").status);
    }

    // row 105: 00 and 01
    @Test
    public void thePayloadFormatAndInitiationValuesAreChecked() {
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(PhoneFixtures.resign(tlv("00", "02") + tlv("01", "11"))), "00").status);
        assertEquals(BankQr.Status.FAIL, need(QrReport.of(PhoneFixtures.resign(tlv("00", "01") + tlv("01", "13"))), "01").status);
        assertEquals(BankQr.Status.OK, need(QrReport.of(PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11"))), "01").status);
    }
}
