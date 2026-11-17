package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;

/** One test per BankQr row of bank-app/README.md (rows named in each test). */
public class BankQrTest {
    private static String tlv(String id, String v) {
        return SampleQr.tlv(id, v);
    }

    private static String account(String guid, String bic, String iban) {
        return tlv("00", guid) + tlv("01", bic) + tlv("02", iban);
    }

    /** A signed QR with the given extra elements between the account template and the merchant name. */
    private static String qr(String pim, String extra) {
        return PhoneFixtures.resign(tlv("00", "01") + tlv("01", pim)
                + tlv("28", account("2611061516150000001", "FAYSPKKA", "PK00TEST0000000000000001"))
                + tlv("52", "1750") + tlv("53", "586") + extra
                + tlv("58", "PK") + tlv("59", "Test Merchant") + tlv("60", "Karachi"));
    }

    private static BankQr.Row row(BankQr q, String label) {
        for (BankQr.Section s : q.sections()) for (BankQr.Row r : s.rows) if (r.label.equals(label)) return r;
        throw new AssertionError("no row " + label);
    }

    private static BankQr.Check check(BankQr q, String label) {
        for (BankQr.Check c : q.checks()) if (c.label.equals(label)) return c;
        throw new AssertionError("no check " + label + " in " + q.checks().size() + " checks");
    }

    // rows 18, 19: the fields the NBP app reads for a dynamic QR
    @Test
    public void dynamicQrGivesEveryFieldTheBankAppUses() {
        BankQr q = BankQr.of(PhoneFixtures.DYNAMIC);
        assertTrue(q.problem, q.valid);
        assertTrue(q.isDynamic());
        assertEquals("Test Merchant", q.merchantName);
        assertEquals("Karachi", q.city);
        assertEquals("PK", q.country);
        assertEquals("1750", q.categoryCode);
        assertEquals("PKR", q.currency());
        assertEquals("2.0", q.amount);
        assertEquals("2611061516150000001", q.guid);
        assertEquals("FAYSPKKA", q.bic);
        assertEquals("PK00TEST0000000000000001", q.iban);
        assertEquals("999000000000001", q.storeLabel);
        assertEquals("2611061516150000002", q.referenceLabel);
        assertEquals("00009999", q.terminalId);
        assertEquals("2611061516150000003", q.rtpGuid);
        assertEquals("00000000-1111-2222-3333-444444444444", q.rtpId);
        assertEquals(new BigDecimal("2.00"), q.totalAmount());
        assertNull(q.totalNote());
        assertEquals("PKR 2.00", row(q, "Total to debit").value);
        assertEquals("28.02", row(q, "IBAN").ref);
        assertTrue(row(q, "IBAN").required);
        for (BankQr.Check c : q.checks()) assertEquals(c.label + ": " + c.detail, BankQr.Status.OK, c.status);
    }

    // row 19: static QR without an amount
    @Test
    public void staticQrWithoutAmountLeavesTheAmountToThePayer() {
        BankQr q = BankQr.of(PhoneFixtures.STATIC);
        assertTrue(q.valid);
        assertEquals("static", q.type);
        assertEquals(RaastQr.AmountState.NONE, q.amountState());
        assertNull(q.totalAmount());
        assertEquals("payer enters the amount", q.totalNote());
        assertFalse(row(q, "Amount").present());
        assertFalse(row(q, "Total to debit").present());
        assertEquals(BankQr.Status.WARN, check(q, "Amount (54)").status);
        assertEquals("Static QR (11)", row(q, "QR type").value);
        // a static QR is not asked for a dynamic reference
        for (BankQr.Check c : q.checks()) assertFalse(c.label, c.label.equals("Dynamic QR reference"));
    }

    // row 20: tip / convenience fee indicators, as LoginQrpayment adds them
    @Test
    public void fixedFeeIsAddedWhen55Is02() {
        BankQr q = BankQr.of(qr("12", tlv("54", "100.00") + tlv("55", "02") + tlv("56", "5.50")));
        assertEquals(new BigDecimal("105.50"), q.totalAmount());
        assertNull(q.totalNote());
        assertEquals("PKR 105.50", row(q, "Total to debit").value);
    }

    @Test
    public void percentFeeIsAddedWhen55Is03() {
        BankQr q = BankQr.of(qr("12", tlv("54", "1000") + tlv("55", "03") + tlv("57", "2.5")));
        assertEquals(new BigDecimal("1025.00"), q.totalAmount());
        assertEquals("PKR 1,025.00", row(q, "Total to debit").value);
    }

    @Test
    public void tipTheCustomerEntersLeavesTheBaseAmountAndSaysSo() {
        BankQr q = BankQr.of(qr("12", tlv("54", "100.00") + tlv("55", "01")));
        assertEquals(new BigDecimal("100.00"), q.totalAmount());
        assertEquals("plus a tip the payer enters", q.totalNote());
        assertEquals(BankQr.Status.WARN, check(q, "Amount (54)").status);
    }

    @Test
    public void feeIndicatorWithoutItsFeeTagHasNoTotal() {
        BankQr fixed = BankQr.of(qr("12", tlv("54", "100.00") + tlv("55", "02")));
        assertNull(fixed.totalAmount());
        assertTrue(fixed.totalNote(), fixed.totalNote().contains("56"));
        BankQr pct = BankQr.of(qr("12", tlv("54", "100.00") + tlv("55", "03") + tlv("57", "abc")));
        assertNull(pct.totalAmount());
        assertTrue(pct.totalNote(), pct.totalNote().contains("57"));
    }

    @Test
    public void moneyUsesThousandsSeparators() {
        assertEquals("1,234,567.50", BankQr.money(new BigDecimal("1234567.5")));
        assertEquals("0.05", BankQr.money(new BigDecimal("0.05")));
    }

    // row 21: tag 54 present but zero or not a number
    @Test
    public void unusableAmountFailsTheCheckAndHasNoTotal() {
        for (String bad : new String[] {"0.00", "0", "abc", "-5", "1,5"}) {
            BankQr q = BankQr.of(qr("12", tlv("54", bad)));
            assertEquals(bad, RaastQr.AmountState.UNUSABLE, q.amountState());
            assertNull(bad, q.totalAmount());
            assertEquals(bad, BankQr.Status.FAIL, check(q, "Amount (54)").status);
            assertTrue(bad, q.totalNote().contains("unusable"));
        }
    }

    // row 22: the payee account is read from template 28 only, as the NBP app does
    @Test
    public void payeeTemplate26IsNotUsedBecauseTheNbpAppIgnoresIt() {
        String spec = PhoneFixtures.resign(tlv("00", "01") + tlv("01", "12")
                + tlv("26", account("2611061516150000001", "FAYSPKKA", "PK00TEST0000000000000001"))
                + tlv("53", "586") + tlv("54", "2.00") + tlv("58", "PK") + tlv("59", "M") + tlv("60", "K"));
        BankQr q = BankQr.of(spec);
        assertTrue(q.valid);
        assertNull(q.iban);
        assertEquals(BankQr.Status.FAIL, check(q, "Payee IBAN (28.02)").status);
        assertTrue(check(q, "Payee IBAN (28.02)").detail.contains("no template 28"));
        assertEquals(BankQr.Status.FAIL, check(q, "Payee bank BIC (28.01)").status);
    }

    @Test
    public void templateWithoutAnIbanSaysWhichSubTagIsMissing() {
        String s = PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11")
                + tlv("28", tlv("00", "G") + tlv("01", "FAYSPKKA")) + tlv("53", "586") + tlv("59", "M"));
        BankQr q = BankQr.of(s);
        assertEquals("template 28 has no sub-tag 02", check(q, "Payee IBAN (28.02)").detail);
        assertEquals(BankQr.Status.OK, check(q, "Payee bank BIC (28.01)").status);
    }

    @Test
    public void ibanThatIsNotAPkIbanIsAWarningNotAFailure() {
        String s = PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11")
                + tlv("28", account("G", "FAYSPKKA", "1234567890")) + tlv("53", "586") + tlv("59", "M"));
        assertEquals(BankQr.Status.WARN, check(BankQr.of(s), "Payee IBAN (28.02)").status);
    }

    // row 23: currency
    @Test
    public void currencyOtherThanPkrIsAWarningAndMissingIsAFailure() {
        BankQr usd = BankQr.of(PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11") + tlv("53", "840") + tlv("59", "M")));
        assertEquals("840", usd.currency());
        assertEquals(BankQr.Status.WARN, check(usd, "Currency (53)").status);
        BankQr none = BankQr.of(PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11") + tlv("59", "M")));
        assertNull(none.currency());
        assertEquals(BankQr.Status.FAIL, check(none, "Currency (53)").status);
    }

    // row 24: dynamic QR reference
    @Test
    public void dynamicQrWithNeitherReferenceNorRtpIsAWarning() {
        BankQr q = BankQr.of(qr("12", tlv("54", "2.00")));
        assertEquals(BankQr.Status.WARN, check(q, "Dynamic QR reference").status);
        BankQr withRef = BankQr.of(qr("12", tlv("54", "2.00") + tlv("62", tlv("05", "REF1"))));
        assertEquals(BankQr.Status.OK, check(withRef, "Dynamic QR reference").status);
        assertEquals("62.05 REF1", check(withRef, "Dynamic QR reference").detail);
    }

    // row 25: every optional template-62 sub-tag the NBP app reads
    @Test
    public void additionalDataSubTagsAreAllRead() {
        // template 62 is capped at 99 characters, so the values are short
        String add = tlv("01", "B1") + tlv("02", "0300") + tlv("03", "S") + tlv("04", "L")
                + tlv("05", "R") + tlv("06", "C") + tlv("07", "T") + tlv("08", "050") + tlv("09", "E")
                + tlv("10", "X") + tlv("11", "H") + tlv("50", "31122026") + tlv("51", "250.00");
        BankQr q = BankQr.of(qr("11", tlv("62", add) + tlv("80", "CTX")));
        assertEquals("B1", q.billNumber);
        assertEquals("0300", q.mobile);
        assertEquals("S", q.storeLabel);
        assertEquals("L", q.loyalty);
        assertEquals("R", q.referenceLabel);
        assertEquals("C", q.customerLabel);
        assertEquals("T", q.terminalId);
        assertEquals("050", q.purpose);
        assertEquals("E", q.additionalDataRequest);
        assertEquals("X", q.taxId);
        assertEquals("H", q.channel);
        assertEquals("31122026", q.billDueDate);
        assertEquals("250.00", q.amountAfterDueDate);
        assertEquals("CTX", q.context);
    }

    // row 26: a QR the NBP app would refuse
    @Test
    public void rejectedQrKeepsTheReasonAndStillBuildsTheScreenData() {
        BankQr q = BankQr.of(PhoneFixtures.DYNAMIC.replace("6304C871", "6304C872"));
        assertFalse(q.valid);
        assertTrue(q.problem, q.problem.contains("should be C871"));
        assertEquals(BankQr.Status.FAIL, q.checks().get(0).status);
        assertEquals("Test Merchant", q.merchantName);

        for (String junk : new String[] {null, "", "MR:4210112345671|Amount:100", "000201"}) {
            BankQr j = BankQr.of(junk);
            assertFalse(String.valueOf(junk), j.valid);
            assertNotNull(j.sections());
            assertNotNull(j.checks());
            assertNotNull(j.tree());
            assertEquals("unknown", j.type);
        }
    }

    // row 27: duplicated tags
    @Test
    public void duplicateTagKeepsTheFirstValueAsTheNbpAppDoes() {
        BankQr q = BankQr.of(PhoneFixtures.resign(tlv("00", "01") + tlv("01", "11") + tlv("59", "First")
                + tlv("59", "Second") + tlv("53", "586")));
        assertEquals("First", q.merchantName);
    }

    // row 28: the raw tag view
    @Test
    public void treeUnfoldsTemplatesAndAccountsForEveryCharacter() {
        BankQr q = BankQr.of(PhoneFixtures.DYNAMIC);
        List<BankQr.Node> nodes = q.tree();
        int top = 0;
        for (BankQr.Node n : nodes) {
            assertFalse("leftover node", n.tag.equals("--"));
            if (n.depth == 0) top += 4 + n.length;
        }
        assertEquals(q.raw.length(), top);

        int at28 = -1;
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).tag.equals("28") && nodes.get(i).depth == 0) at28 = i;
        assertTrue(nodes.get(at28).template);
        assertNull(nodes.get(at28).value);
        assertEquals("00", nodes.get(at28 + 1).tag);
        assertEquals(1, nodes.get(at28 + 1).depth);
        assertEquals("2611061516150000001", nodes.get(at28 + 1).value);
        assertEquals("02", nodes.get(at28 + 3).tag);
        assertEquals("PK00TEST0000000000000001", nodes.get(at28 + 3).value);
        assertEquals(0, nodes.get(at28 + 4).depth);

        BankQr.Node crc = nodes.get(nodes.size() - 1);
        assertEquals("63", crc.tag);
        assertEquals("C871", crc.value);
    }

    @Test
    public void treeKeepsAValueThatIsNotTlvAsALeafEvenInATemplateTagRange() {
        BankQr q = BankQr.of(qr("11", tlv("80", "ABCDEFG")));
        for (BankQr.Node n : q.tree()) {
            if (n.tag.equals("80")) {
                assertFalse(n.template);
                assertEquals("ABCDEFG", n.value);
                return;
            }
        }
        throw new AssertionError("no tag 80");
    }

    @Test
    public void treeShowsWhereAnUnreadableTailStarts() {
        BankQr q = BankQr.of("0002010102125999abc");
        List<BankQr.Node> nodes = q.tree();
        BankQr.Node last = nodes.get(nodes.size() - 1);
        assertEquals("--", last.tag);
        assertEquals("5999abc", last.value);
    }
}
