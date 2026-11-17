package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/** Table 10 rules 1-2, Clause 3.8 chunking and Clause 4.3 torn transfers. */
public class InvoiceAssemblerTest {
    private Fixtures.FakeClock clock;
    private InvoiceAssembler a;
    private List<byte[]> three;

    @Before
    public void setUp() {
        clock = new Fixtures.FakeClock();
        a = new InvoiceAssembler(2000, 1024, clock);
        three = Apdu.pushInvoice(Invoice.CANONICAL, 100);
    }

    @Test
    public void threeChunksReassemble() {
        assertEquals("80 CA 03 01 61", Fixtures.header(three.get(0)));
        assertEquals("80 CA 03 02 59", Fixtures.header(three.get(1)));
        assertEquals("80 CA 03 83 30", Fixtures.header(three.get(2)));
        assertFalse(a.accept(three.get(0)).complete());
        assertTrue(a.inProgress());
        assertFalse(a.accept(three.get(1)).complete());
        InvoiceAssembler.Step s = a.accept(three.get(2));
        assertEquals(Apdu.SW_OK, s.sw);
        assertEquals(Invoice.CANONICAL, s.payloadString());
        assertFalse(a.inProgress());
    }

    @Test
    public void chunkWithinGapAccepted() {
        a.accept(three.get(0));
        clock.now += 2000;
        assertEquals(Apdu.SW_OK, a.accept(three.get(1)).sw);
    }

    @Test
    public void chunkGapTimeoutDiscardsBuffer() {
        a.accept(three.get(0));
        clock.now += 2001;
        InvoiceAssembler.Step s = a.accept(three.get(1));
        assertEquals(Apdu.SW_WRONG_P1P2, s.sw);
        assertTrue(s.reason, s.reason.contains("timeout"));
        assertFalse(a.inProgress());
    }

    @Test
    public void restartAtChunk1AfterTimeoutIsAccepted() {
        a.accept(three.get(0));
        a.accept(three.get(1));
        clock.now += 5000;
        InvoiceAssembler.Step s = Fixtures.feedAll(a, three);
        assertEquals(Invoice.CANONICAL, s.payloadString());
    }

    @Test
    public void repeatedChunkIsSequenceError() {
        a.accept(three.get(0));
        assertEquals(Apdu.SW_WRONG_P1P2, a.accept(three.get(0)).sw);
        assertFalse(a.inProgress());
    }

    @Test
    public void firstChunkMustBeIndex1() {
        assertEquals(Apdu.SW_WRONG_P1P2, a.accept(three.get(1)).sw);
        byte[] p2_80 = three.get(0).clone();
        p2_80[3] = (byte) 0x80; // final flag with index 0
        assertEquals(Apdu.SW_WRONG_P1P2, a.accept(p2_80).sw);
    }

    @Test
    public void finalFlagOnChunk1IsASingleChunkTransfer() {
        byte[] apdu = Apdu.pushInvoice(Invoice.CANONICAL, 0).get(0);
        apdu[3] = (byte) 0x81;
        assertEquals(Invoice.CANONICAL, a.accept(apdu).payloadString());
    }

    @Test
    public void singleApduDuringChunkedTransferNotAllowed() {
        a.accept(three.get(0));
        InvoiceAssembler.Step s = a.accept(Apdu.pushInvoice(Invoice.CANONICAL, 0).get(0));
        assertEquals(Apdu.SW_NOT_ALLOWED, s.sw);
        assertFalse(a.inProgress());
    }

    @Test
    public void singleApduAfterStaleTransferAccepted() {
        a.accept(three.get(0));
        clock.now += 3000;
        assertEquals(Invoice.CANONICAL, a.accept(Apdu.pushInvoice(Invoice.CANONICAL, 0).get(0)).payloadString());
    }

    @Test
    public void lcZeroIsWrongLength() {
        assertEquals(Apdu.SW_WRONG_LENGTH, a.accept(Hex.decode("80CA030000")).sw);
        assertEquals(Apdu.SW_WRONG_LENGTH, a.accept(Hex.decode("80CA0300")).sw);
    }

    @Test
    public void lcAbove239IsWrongLength() {
        byte[] apdu = new byte[5 + 240];
        System.arraycopy(Hex.decode("80CA0300F0"), 0, apdu, 0, 5);
        Arrays.fill(apdu, 5, apdu.length, (byte) '0');
        assertEquals(Apdu.SW_WRONG_LENGTH, a.accept(apdu).sw);
    }

    @Test
    public void lcInconsistentWithDataIsWrongLength() {
        byte[] apdu = Apdu.pushInvoice(Invoice.CANONICAL, 0).get(0);
        assertEquals(Apdu.SW_WRONG_LENGTH, a.accept(Arrays.copyOf(apdu, apdu.length - 1)).sw);
        assertEquals(Apdu.SW_WRONG_LENGTH, a.accept(Arrays.copyOf(apdu, apdu.length + 2)).sw);
        // A trailing Le byte is tolerated
        assertEquals(Invoice.CANONICAL, a.accept(Arrays.copyOf(apdu, apdu.length + 1)).payloadString());
    }

    @Test
    public void bufferLimitReturns6A84AndDiscards() {
        InvoiceAssembler small = new InvoiceAssembler(2000, 300, clock);
        byte[] chunk = new byte[5 + 239];
        Arrays.fill(chunk, 5, chunk.length, (byte) '0');
        chunk[0] = (byte) 0x80;
        chunk[1] = (byte) 0xCA;
        chunk[2] = 0x03;
        chunk[3] = 0x01;
        chunk[4] = (byte) 239;
        assertEquals(Apdu.SW_OK, small.accept(chunk).sw);
        chunk[3] = (byte) 0x82;
        InvoiceAssembler.Step s = small.accept(chunk);
        assertEquals(Apdu.SW_NO_MEMORY, s.sw);
        assertNull(s.payload);
        assertFalse(small.inProgress());
    }

    @Test
    public void headerChecksPrecedeBufferCheck() {
        // Table 10 order: a sequence error is reported as 6A86 even when the buffer would also overflow
        InvoiceAssembler small = new InvoiceAssembler(2000, 239, clock);
        byte[] chunk = Apdu.pushInvoice(Invoice.CANONICAL, 100).get(0);
        small.accept(chunk);
        chunk[3] = 0x03;
        assertEquals(Apdu.SW_WRONG_P1P2, small.accept(chunk).sw);
    }

    @Test
    public void otherInsRejected() {
        assertEquals(Apdu.SW_INS_NOT_SUPPORTED, a.accept(Hex.decode("80CB030001 30".replace(" ", ""))).sw);
        assertEquals(Apdu.SW_INS_NOT_SUPPORTED, a.accept(Hex.decode("00CA03000130")).sw);
    }

    @Test
    public void reservedP1ValuesRejected() {
        for (int p1 : new int[]{0x00, 0x01, 0x02, 0x04, 0x05, 0xFF}) {
            byte[] apdu = Apdu.pushInvoice(Invoice.CANONICAL, 0).get(0);
            apdu[2] = (byte) p1;
            assertEquals("P1 " + p1, Apdu.SW_WRONG_P1P2, a.accept(apdu).sw);
        }
    }

    @Test
    public void resetDropsPartialTransfer() {
        a.accept(three.get(0));
        a.reset(); // e.g. a new SELECT
        assertEquals(Apdu.SW_WRONG_P1P2, a.accept(three.get(1)).sw);
    }

    @Test(expected = IllegalArgumentException.class)
    public void chunkGapOutsideTable14RangeRefused() {
        new InvoiceAssembler(6000, 1024, clock);
    }
}
