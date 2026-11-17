package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Spec Clause 9, Table 23: test vectors T-01..T-10, phone side. Each vector is the canonical object of
 * Clause 3.9 with only the noted mutation; the CRC is recomputed unless the mutation is the CRC itself, so
 * every vector exercises the rule it names. "Now" is the canonical timestamp.
 */
public class ConformanceVectorsTest {
    private InvoiceValidator validator;
    private Fixtures.FakeClock clock;
    private InvoiceAssembler assembler;

    @Before
    public void setUp() {
        validator = new InvoiceValidator();
        clock = new Fixtures.FakeClock();
        assembler = new InvoiceAssembler(InvoiceAssembler.T_CHUNK_GAP_MS, InvoiceAssembler.DEFAULT_MAX_BUFFER, clock);
    }

    /** Full phone path: header/reassembly (rules 1-2), then validation (rules 3-8). */
    private int present(List<byte[]> apdus) {
        InvoiceAssembler.Step s = Fixtures.feedAll(assembler, apdus);
        if (!s.complete()) return s.sw;
        return validator.validate(s.payloadString(), Fixtures.NOW).sw;
    }

    @Test
    public void t01_canonicalSingleApduAccepted() {
        assertEquals(Apdu.SW_OK, present(Apdu.pushInvoice(Invoice.CANONICAL, 0)));
    }

    @Test
    public void t02_crcAlteredRejected() {
        String p = Invoice.CANONICAL.replace("6304B00A", "6304B00B");
        InvoiceValidator.Result r = validator.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_CRC, r.rule);
        assertNull("nothing displayed", r.top);
    }

    @Test
    public void t03_timestampRemovedRejectedAsMissingMandatory() {
        String p = Fixtures.mutate("821620261008T101530Z", "");
        InvoiceValidator.Result r = validator.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_SYNTAX, r.rule);
        assertTrue(r.reason, r.reason.startsWith("82"));
    }

    @Test
    public void t04_timestampOneHourOldRejectedStale() {
        String p = Fixtures.mutate("20261008T101530Z", "20261008T091530Z");
        InvoiceValidator.Result r = validator.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_CONDITIONS, r.sw);
        assertEquals(InvoiceValidator.RULE_FRESHNESS, r.rule);
    }

    @Test
    public void t05_rePresentationAfterAuthorizationRejected() {
        assertEquals(Apdu.SW_OK, validator.validate(Invoice.CANONICAL, Fixtures.NOW).sw);
        validator.markAuthorized(Fixtures.NONCE, Fixtures.NOW + 20_000);
        InvoiceValidator.Result r = validator.validate(Invoice.CANONICAL, Fixtures.NOW + 30_000);
        assertEquals(Apdu.SW_CONDITIONS, r.sw);
        assertEquals(InvoiceValidator.RULE_DUPLICATE, r.rule);
    }

    @Test
    public void t06_ibanCheckDigitCorruptedRejected() {
        String p = Fixtures.mutate("PK29PAYS0000001123456702", "PK29PAYS0000001123456703");
        InvoiceValidator.Result r = validator.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_SYNTAX, r.rule);
        assertTrue(r.reason, r.reason.startsWith("26-00"));
    }

    @Test
    public void t07_p1_05_rejectedAndNoSessionStarted() {
        byte[] apdu = Apdu.pushInvoice(Invoice.CANONICAL, 0).get(0);
        apdu[2] = 0x05;
        InvoiceAssembler.Step s = assembler.accept(apdu);
        assertEquals(Apdu.SW_WRONG_P1P2, s.sw);
        assertFalse(s.complete());
        assertFalse(assembler.inProgress());
        // Also for a first chunk: no session is left behind
        byte[] chunk1 = Apdu.pushInvoice(Invoice.CANONICAL, 180).get(0);
        chunk1[2] = 0x05;
        assertEquals(Apdu.SW_WRONG_P1P2, assembler.accept(chunk1).sw);
        assertFalse(assembler.inProgress());
    }

    @Test
    public void t08_chunkGapInSequenceRejectedAndBufferDiscarded() {
        List<byte[]> chunks = Apdu.pushInvoice(Invoice.CANONICAL, 100);
        assertEquals(3, chunks.size());
        assertEquals(Apdu.SW_OK, assembler.accept(chunks.get(0)).sw);
        InvoiceAssembler.Step s = assembler.accept(chunks.get(2)); // P2 = 83, chunk 02 missing
        assertEquals(Apdu.SW_WRONG_P1P2, s.sw);
        assertFalse(assembler.inProgress());
        // Terminal restarts at chunk 1 with the same nonce; the object is accepted
        assertEquals(Apdu.SW_OK, present(chunks));
    }

    @Test
    public void t09_amountWithCommaRejected() {
        String p = Fixtures.mutate("5406500.00", "54075,00.00");
        InvoiceValidator.Result r = validator.validate(p, Fixtures.NOW);
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertEquals(InvoiceValidator.RULE_SYNTAX, r.rule);
        assertTrue(r.reason, r.reason.startsWith("54"));
    }

    @Test
    public void t10_unknownTag75Ignored() {
        String p = Fixtures.mutate("8010PAYSYS0001", "7508FUTURE01" + "8010PAYSYS0001");
        assertEquals(Apdu.SW_OK, present(Apdu.pushInvoice(p, 0)));
        InvoiceValidator.Result r = new InvoiceValidator().validate(p, Fixtures.NOW);
        assertEquals("500.00", r.top.get("54"));
    }
}
