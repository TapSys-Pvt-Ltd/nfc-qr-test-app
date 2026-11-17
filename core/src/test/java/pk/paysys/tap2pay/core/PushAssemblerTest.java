package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class PushAssemblerTest {
    private final PushAssembler a = new PushAssembler();

    private PushAssembler.Result feed(List<byte[]> apdus) {
        PushAssembler.Result r = null;
        for (int i = 0; i < apdus.size(); i++) {
            r = a.push(apdus.get(i));
            assertEquals(Apdu.SW_OK, r.sw);
            assertEquals(i == apdus.size() - 1, r.complete());
            assertEquals(i + 1, r.count);
        }
        return r;
    }

    private static String text(PushAssembler.Result r) {
        return new String(r.payload, StandardCharsets.UTF_8);
    }

    @Test
    public void specOneApdu() {
        PushAssembler.Result r = feed(PhoneFixtures.spec(PhoneFixtures.STATIC));
        assertEquals(PhoneFixtures.STATIC, text(r));
        assertEquals(PushAssembler.Form.SPEC, r.form);
    }

    @Test
    public void specChunked() {
        List<byte[]> apdus = PhoneFixtures.spec(PhoneFixtures.DYNAMIC);
        assertEquals(2, apdus.size());
        PushAssembler.Result r = feed(apdus);
        assertEquals(PhoneFixtures.DYNAMIC, text(r));
        assertEquals(PushAssembler.Form.SPEC, r.form);
    }

    @Test
    public void indexOneApdu() {
        PushAssembler.Result r = feed(PhoneFixtures.index(PhoneFixtures.STATIC, 239));
        assertEquals(PhoneFixtures.STATIC, text(r));
        assertEquals(PushAssembler.Form.INDEX, r.form);
    }

    @Test
    public void indexChunked() {
        List<byte[]> apdus = PhoneFixtures.index(PhoneFixtures.DYNAMIC, 100);
        assertEquals(3, apdus.size());
        PushAssembler.Result r = feed(apdus);
        assertEquals(PhoneFixtures.DYNAMIC, text(r));
        assertEquals(PushAssembler.Form.INDEX, r.form);
    }

    @Test
    public void outOfSequenceDropsTheBuffer() {
        List<byte[]> apdus = PhoneFixtures.spec(PhoneFixtures.DYNAMIC, 100);
        a.push(apdus.get(0));
        PushAssembler.Result r = a.push(apdus.get(2));
        assertEquals(Apdu.SW_WRONG_P1P2, r.sw);
        assertTrue(r.note, r.note.contains("out of sequence"));
        assertEquals(0, a.chunksBuffered());
    }

    @Test
    public void transferMustStartAtTheFirstChunk() {
        PushAssembler.Result r = a.push(PhoneFixtures.index(PhoneFixtures.DYNAMIC, 100).get(1));
        assertEquals(Apdu.SW_WRONG_P1P2, r.sw);
        assertTrue(r.note, r.note.contains("started at chunk 1"));
    }

    @Test
    public void formsCannotBeMixed() {
        a.push(PhoneFixtures.spec(PhoneFixtures.DYNAMIC).get(0));
        PushAssembler.Result r = a.push(PhoneFixtures.index(PhoneFixtures.DYNAMIC, 100).get(0));
        assertEquals(Apdu.SW_WRONG_P1P2, r.sw);
        assertTrue(r.note, r.note.contains("form changed"));
    }

    @Test
    public void specSingleDuringATransferRestarts() {
        a.push(PhoneFixtures.spec(PhoneFixtures.DYNAMIC).get(0));
        PushAssembler.Result r = a.push(PhoneFixtures.spec(PhoneFixtures.STATIC).get(0));
        assertEquals(PhoneFixtures.STATIC, text(r));
        assertEquals(1, r.count);
    }

    @Test
    public void unknownP1() {
        byte[] d = "x".getBytes(StandardCharsets.UTF_8);
        PushAssembler.Result r = a.push(PhoneFixtures.cmd(0x02, 0, d, 0, 1));
        assertEquals(Apdu.SW_WRONG_P1P2, r.sw);
    }

    @Test
    public void lcZeroOrTooLong() {
        assertEquals(Apdu.SW_BAD_DATA, a.push(new byte[]{(byte) 0x80, (byte) 0xCA, 3, 0, 0, 0x41}).sw);
        assertEquals(Apdu.SW_BAD_DATA, a.push(new byte[]{(byte) 0x80, (byte) 0xCA, 3, 0, 5, 0x41}).sw);
        assertEquals(Apdu.SW_BAD_DATA, a.push(new byte[]{(byte) 0x80, (byte) 0xCA, 3, 0}).sw);
    }

    @Test
    public void extendedLcIsAccepted() {
        byte[] d = PhoneFixtures.STATIC.getBytes(StandardCharsets.UTF_8);
        byte[] apdu = new byte[7 + d.length];
        apdu[0] = (byte) 0x80;
        apdu[1] = (byte) 0xCA;
        apdu[2] = 3;
        apdu[5] = (byte) (d.length >> 8);
        apdu[6] = (byte) d.length;
        System.arraycopy(d, 0, apdu, 7, d.length);
        PushAssembler.Result r = a.push(apdu);
        assertEquals(PhoneFixtures.STATIC, text(r));
    }

    @Test
    public void payloadOver4096BytesIsRefused() {
        byte[] d = new byte[239];
        java.util.Arrays.fill(d, (byte) 'A');
        PushAssembler.Result r = null;
        for (int i = 0; i < 18; i++) {
            r = a.push(PhoneFixtures.cmd(0x00, i, d, 0, d.length));
            if (r.sw != Apdu.SW_OK) break;
        }
        assertEquals(Apdu.SW_BAD_DATA, r.sw);
        assertFalse(r.complete());
        assertNull(r.payload);
    }
}
