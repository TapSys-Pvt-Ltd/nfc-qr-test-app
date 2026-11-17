package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

/** Table 8 element rules and Table 10 rules 3-8 beyond the Table 23 vectors. */
public class InvoiceValidatorTest {
    private InvoiceValidator v;

    @Before
    public void setUp() {
        v = new InvoiceValidator();
    }

    private InvoiceValidator.Result check(Invoice inv) {
        return new InvoiceValidator().validate(inv.toPayload(), Fixtures.NOW);
    }

    private void assertRejected(Invoice inv, String reasonPrefix) {
        InvoiceValidator.Result r = check(inv);
        assertEquals(r.reason, Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_SYNTAX, r.rule);
        assertTrue(r.reason, r.reason.startsWith(reasonPrefix));
    }

    private void assertAccepted(Invoice inv) {
        InvoiceValidator.Result r = check(inv);
        assertEquals(r.reason, Apdu.SW_OK, r.sw);
    }

    // --- 59 DBA / 60 City byte limits (UTF-8 byte counts, Clause 3.4) ---

    @Test
    public void dbaLimitIs30Bytes() {
        Invoice inv = new Invoice();
        inv.dba = repeat('A', 30);
        assertAccepted(inv);
        inv.dba = repeat('A', 31);
        assertRejected(inv, "59");
    }

    @Test
    public void dbaLimitCountsUtf8Bytes() {
        Invoice inv = new Invoice();
        inv.dba = repeat('ل', 15); // 15 Urdu letters = 30 bytes
        assertEquals(30, inv.dba.getBytes(StandardCharsets.UTF_8).length);
        assertAccepted(inv);
        inv.dba = repeat('ل', 16); // 32 bytes, only 16 characters
        assertRejected(inv, "59");
    }

    @Test
    public void cityLimitIs20BytesAndOptional() {
        Invoice inv = new Invoice();
        inv.city = repeat('B', 20);
        assertAccepted(inv);
        inv.city = repeat('B', 21);
        assertRejected(inv, "60");
        inv.city = null;
        assertAccepted(inv);
    }

    @Test
    public void emptyDbaRejected() {
        Invoice inv = new Invoice();
        inv.dba = "";
        assertRejected(inv, "59");
    }

    // --- Template 62 labels ---

    @Test
    public void referenceAndTerminalLabelLimits() {
        Invoice inv = new Invoice();
        inv.referenceLabel = repeat('R', 30);
        inv.terminalLabel = repeat('T', 30);
        assertAccepted(inv);
        inv.referenceLabel = repeat('R', 31);
        assertRejected(inv, "62-05");
        inv.referenceLabel = "REF1";
        inv.terminalLabel = repeat('T', 31);
        assertRejected(inv, "62-07");
        inv.terminalLabel = "";
        assertRejected(inv, "62-07");
    }

    @Test
    public void labelsMustBePrintableAscii() {
        Invoice inv = new Invoice();
        inv.terminalLabel = "KHI-ل";
        assertRejected(inv, "62-07");
    }

    @Test
    public void optionalAdditionalDataLimits() {
        Invoice inv = new Invoice();
        inv.billNumber = repeat('1', 31);
        assertRejected(inv, "62-01");
        inv = new Invoice();
        inv.storeLabel = repeat('ل', 12); // 24 bytes, UTF-8 allowed
        assertAccepted(inv);
        inv.storeLabel = repeat('S', 26);
        assertRejected(inv, "62-03");
        inv = new Invoice();
        inv.purpose = "ABCDE";
        assertRejected(inv, "62-08");
    }

    @Test
    public void missingReferenceLabelRejected() {
        String p = Fixtures.mutate("62390108INV123450508REF987650711PAYSYS-KHI1", "62270108INV123450711PAYSYS-KHI1");
        InvoiceValidator.Result r = v.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertTrue(r.reason, r.reason.startsWith("62-05"));
    }

    // --- Template 26 ---

    @Test
    public void bicMustBeAn8OrAn11() {
        Invoice inv = new Invoice();
        inv.bic = "PAYSPKKA";
        assertAccepted(inv);
        inv.bic = null;
        assertAccepted(inv);
        for (String bad : new String[]{"PAYSPKK", "PAYSPKKAX", "PAYSPKKAXXXX", "PAYS-PKKAXX", "payspkkaxxx"}) {
            inv.bic = bad;
            assertRejected(inv, "26-02");
        }
    }

    @Test
    public void aliasLimit() {
        Invoice inv = new Invoice();
        inv.alias = repeat('9', 20);
        assertAccepted(inv);
        inv.alias = repeat('9', 21);
        assertRejected(inv, "26-01");
    }

    @Test
    public void ibanMustBePakistani24Chars() {
        Invoice inv = new Invoice();
        inv.iban = "GB82WEST12345698765432";
        assertRejected(inv, "26-00");
    }

    @Test
    public void unknownSubTagInsideTemplateIgnored() {
        // 26-03 is reserved (Annex A); template length 56 -> 64
        String p = Fixtures.mutate("26560024PK29PAYS0000001123456702", "26640024PK29PAYS00000011234567020304ABCD");
        InvoiceValidator.Result r = v.validate(p, Fixtures.NOW);
        assertEquals(r.reason, Apdu.SW_OK, r.sw);
        assertEquals("PAYSPKKAXXX", r.account.get("02"));
    }

    @Test
    public void malformedTemplateRejectedAsTlvError() {
        // Template 26 length unchanged but inner IBAN length claims 25 bytes -> sub-element overrun
        String p = Fixtures.mutate("0024PK29PAYS", "0025PK29PAYS");
        InvoiceValidator.Result r = v.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_TLV, r.rule);
    }

    // --- Other top-level rules ---

    @Test
    public void staticInvoiceMayOmitAmount() {
        Invoice inv = new Invoice();
        inv.pointOfInitiation = "11";
        inv.amount = null;
        assertAccepted(inv);
        inv.pointOfInitiation = "12";
        assertRejected(inv, "54");
    }

    @Test
    public void amountPattern() {
        Invoice inv = new Invoice();
        for (String bad : new String[]{"500", "500.0", "500.000", ".50", "-1.00", "1e3.00"}) {
            inv.amount = bad;
            assertRejected(inv, "54");
        }
    }

    @Test
    public void terminalIdIs8To10Alphanumerics() {
        Invoice inv = new Invoice();
        inv.terminalId = "PAYSYS01";
        assertAccepted(inv);
        inv.terminalId = "PAYSYS1";
        assertRejected(inv, "80");
        inv.terminalId = "PAYSYS00001";
        assertRejected(inv, "80");
    }

    @Test
    public void nonceAndTimestampSyntax() {
        Invoice inv = new Invoice();
        inv.nonce = "3F2A9C011B7E4D5";
        assertRejected(inv, "81");
        inv = new Invoice();
        inv.timestamp = "20261332T101530Z";
        assertRejected(inv, "82");
    }

    @Test
    public void tag00MustComeFirst() {
        String body = Invoice.CANONICAL.substring(6, Invoice.CANONICAL.length() - 8);
        InvoiceValidator.Result r = v.validate(Fixtures.resign("010212" + "000201" + body.substring(6)), Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_TLV, r.rule);
    }

    @Test
    public void tag63MustComeLast() {
        String p = Invoice.CANONICAL + "7500";
        InvoiceValidator.Result r = v.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_CRC, r.rule);
    }

    @Test
    public void duplicateDefinedTagRejected() {
        String p = Fixtures.mutate("5406500.00", "5406500.0054061.00");
        InvoiceValidator.Result r = v.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_TLV, r.rule);
    }

    @Test
    public void truncatedPayloadRejected() {
        InvoiceValidator.Result r = v.validate(Invoice.CANONICAL.substring(0, 100), Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_TLV, r.rule);
    }

    // --- Rule 6 freshness boundaries ---

    @Test
    public void freshnessWindowIsSymmetric300s() {
        assertEquals(Apdu.SW_OK, new InvoiceValidator().validate(Invoice.CANONICAL, Fixtures.NOW + 300_000).sw);
        assertEquals(Apdu.SW_OK, new InvoiceValidator().validate(Invoice.CANONICAL, Fixtures.NOW - 300_000).sw);
        assertEquals(Apdu.SW_CONDITIONS, new InvoiceValidator().validate(Invoice.CANONICAL, Fixtures.NOW + 301_000).sw);
        assertEquals(Apdu.SW_CONDITIONS, new InvoiceValidator().validate(Invoice.CANONICAL, Fixtures.NOW - 301_000).sw);
    }

    @Test
    public void configurableFreshness() {
        InvoiceValidator strict = new InvoiceValidator(60_000, 900_000);
        assertEquals(Apdu.SW_CONDITIONS, strict.validate(Invoice.CANONICAL, Fixtures.NOW + 61_000).sw);
    }

    @Test(expected = IllegalArgumentException.class)
    public void freshnessOutsideTable14RangeRefused() {
        new InvoiceValidator(30_000, 900_000);
    }

    // --- Rule 7 / Clause 4.3 idempotency ---

    @Test
    public void rePresentationBeforeAuthorizationIsRefresh() {
        InvoiceValidator.Result first = v.validate(Invoice.CANONICAL, Fixtures.NOW);
        assertFalse(first.refresh);
        InvoiceValidator.Result again = v.validate(Invoice.CANONICAL, Fixtures.NOW + 5_000);
        assertEquals(Apdu.SW_OK, again.sw);
        assertTrue(again.refresh);
    }

    @Test
    public void nonceMatchIsCaseInsensitive() {
        v.validate(Invoice.CANONICAL, Fixtures.NOW);
        v.markAuthorized(Fixtures.NONCE.toLowerCase(), Fixtures.NOW);
        assertEquals(Apdu.SW_CONDITIONS, v.validate(Invoice.CANONICAL, Fixtures.NOW + 1_000).sw);
    }

    @Test
    public void authorizedNonceReleasedAfterDedupeWindow() {
        // Same nonce, re-issued with a timestamp fresh at the later "now"
        v.markAuthorized(Fixtures.NONCE, Fixtures.NOW);
        long later = Fixtures.NOW + 901_000;
        Invoice inv = new Invoice();
        inv.timestamp = Invoice.timestampUtc(later);
        assertEquals(Apdu.SW_OK, v.validate(inv.toPayload(), later).sw);
    }

    @Test
    public void staleIsReportedBeforeDuplicate() {
        v.markAuthorized(Fixtures.NONCE, Fixtures.NOW);
        InvoiceValidator.Result r = v.validate(Invoice.CANONICAL, Fixtures.NOW + 600_000);
        assertEquals(InvoiceValidator.RULE_FRESHNESS, r.rule);
    }

    // --- Rule 8 device policy ---

    @Test
    public void devicePolicyBlocksPresentation() {
        v.setPresentationAllowed(false);
        InvoiceValidator.Result r = v.validate(Invoice.CANONICAL, Fixtures.NOW);
        assertEquals(Apdu.SW_CONDITIONS, r.sw);
        assertEquals(InvoiceValidator.RULE_POLICY, r.rule);
        v.setPresentationAllowed(true);
        assertEquals(Apdu.SW_OK, v.validate(Invoice.CANONICAL, Fixtures.NOW).sw);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
