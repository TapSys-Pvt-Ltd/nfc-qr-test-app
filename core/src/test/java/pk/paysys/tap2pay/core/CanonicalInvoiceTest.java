package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

/** Clause 3.6 and 3.9: canonical object, CRC, and its single-APDU and two-chunk transfers. */
public class CanonicalInvoiceTest {

    @Test
    public void builderProducesCanonicalObject() {
        assertEquals(Invoice.CANONICAL, new Invoice().toPayload());
    }

    @Test
    public void canonicalIs234BytesWithCrcB00A() {
        assertEquals(234, Invoice.CANONICAL.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(Invoice.CANONICAL.endsWith("6304B00A"));
        assertEquals("B00A", Crc16.forPayloadPrefix(Invoice.CANONICAL.substring(0, 230)));
    }

    @Test
    public void crcIsCcittFalse() {
        // Standard check value of CRC-16/CCITT-FALSE
        assertEquals(0x29B1, Crc16.compute("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void canonicalDecodesToTable9Values() {
        InvoiceValidator.Result r = new InvoiceValidator().validate(Invoice.CANONICAL, Fixtures.NOW);
        assertEquals(r.reason, Apdu.SW_OK, r.sw);
        assertEquals("PAYSYS LABS", r.top.get("59"));
        assertEquals("KARACHI", r.top.get("60"));
        assertEquals("PK", r.top.get("58"));
        assertEquals("5411", r.top.get("52"));
        assertEquals("PK29PAYS0000001123456702", r.account.get("00"));
        assertEquals("229999999", r.account.get("01"));
        assertEquals("PAYSPKKAXXX", r.account.get("02"));
        assertEquals("500.00", r.top.get("54"));
        assertEquals("586", r.top.get("53"));
        assertEquals("INV12345", r.additional.get("01"));
        assertEquals("REF98765", r.additional.get("05"));
        assertEquals("PAYSYS-KHI1", r.additional.get("07"));
        assertEquals("PAYSYS0001", r.top.get("80"));
        assertEquals(Fixtures.NONCE, r.top.get("81"));
        assertEquals("20261008T101530Z", r.top.get("82"));
    }

    @Test
    public void singleApduForm() {
        List<byte[]> apdus = Apdu.pushInvoice(Invoice.CANONICAL, 0);
        assertEquals(1, apdus.size());
        byte[] apdu = apdus.get(0);
        assertEquals("80 CA 03 00 EA", Fixtures.header(apdu));
        assertEquals(5 + 234, apdu.length);
        assertArrayEquals(Invoice.CANONICAL.getBytes(StandardCharsets.UTF_8),
                java.util.Arrays.copyOfRange(apdu, 5, apdu.length));
    }

    @Test
    public void twoChunkFormSplitsAtTag80() {
        // Any limit from 172 to 185 puts the last tag boundary that fits at tag 80 (offset 172)
        List<byte[]> apdus = Apdu.pushInvoice(Invoice.CANONICAL, 180);
        assertEquals(2, apdus.size());
        assertEquals("80 CA 03 01 AC", Fixtures.header(apdus.get(0)));
        assertEquals("80 CA 03 82 3E", Fixtures.header(apdus.get(1)));
        String first = new String(apdus.get(0), 5, 172, StandardCharsets.UTF_8);
        String second = new String(apdus.get(1), 5, 62, StandardCharsets.UTF_8);
        assertTrue(first.endsWith("0711PAYSYS-KHI1"));
        assertEquals("8010PAYSYS000181163F2A9C011B7E4D55821620261008T101530Z6304B00A", second);
    }

    @Test
    public void twoChunkFormReassemblesAndValidates() {
        InvoiceAssembler a = new InvoiceAssembler(2000, 1024, new Fixtures.FakeClock());
        InvoiceAssembler.Step s1 = a.accept(Apdu.pushInvoice(Invoice.CANONICAL, 180).get(0));
        assertEquals(Apdu.SW_OK, s1.sw);
        assertTrue(!s1.complete());
        InvoiceAssembler.Step s2 = a.accept(Apdu.pushInvoice(Invoice.CANONICAL, 180).get(1));
        assertEquals(Apdu.SW_OK, s2.sw);
        assertEquals(Invoice.CANONICAL, s2.payloadString());
        assertEquals(Apdu.SW_OK, new InvoiceValidator().validate(s2.payloadString(), Fixtures.NOW).sw);
    }

    @Test
    public void largeInvoiceChunksAtTagBoundariesWithin239Bytes() {
        Invoice inv = new Invoice();
        inv.dba = "پے سس لیبز کرا"; // UTF-8 DBA
        inv.storeLabel = "Clifton Block 5 Branch";
        inv.purpose = "0001";
        inv.billNumber = "INV-2026-10-08-0000001234567";
        String payload = inv.toPayload();
        int total = payload.getBytes(StandardCharsets.UTF_8).length;
        assertTrue("needs chunking: " + total, total > Apdu.MAX_CHUNK);
        List<byte[]> apdus = Apdu.pushInvoice(payload, 0);
        assertEquals(2, apdus.size());
        int boundary = (apdus.get(0)[4] & 0xFF);
        assertTrue(boundary <= Apdu.MAX_CHUNK);
        // Second chunk starts with a top-level tag
        String rest = new String(apdus.get(1), 5, apdus.get(1)[4] & 0xFF, StandardCharsets.UTF_8);
        assertTrue(rest, rest.startsWith("62") || rest.startsWith("80") || rest.startsWith("81") || rest.startsWith("82"));
        InvoiceAssembler.Step s = Fixtures.feedAll(new InvoiceAssembler(), apdus);
        assertEquals(payload, s.payloadString());
        InvoiceValidator.Result r = new InvoiceValidator().validate(payload, Fixtures.NOW);
        assertEquals(r.reason, Apdu.SW_OK, r.sw);
    }
}
