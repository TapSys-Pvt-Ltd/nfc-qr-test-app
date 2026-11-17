package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/** BER-TLV rows of the VAS probe matrix (V12, V13): tag forms, length forms, nesting, malformed input. */
public class BerTlvTest {
    private static List<BerTlv.Node> parse(String hex) {
        byte[] b = Hex.decode(hex);
        return BerTlv.parse(b, 0, b.length);
    }

    private static void assertRejected(String hex) {
        try {
            parse(hex);
            fail("accepted " + hex);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // V12: one-byte tag, short length, constructed template recursed
    @Test
    public void constructedTemplateIsParsedRecursively() {
        List<BerTlv.Node> nodes = parse("6F055003414243" + "8200");
        assertEquals(2, nodes.size());
        BerTlv.Node fci = nodes.get(0);
        assertEquals(0x6F, fci.tag);
        assertTrue(fci.constructed());
        assertEquals(1, fci.children.size());
        assertEquals(0x50, fci.children.get(0).tag);
        assertEquals("41 42 43", Hex.encode(fci.children.get(0).value));
        assertFalse(nodes.get(1).constructed());
        assertNull(nodes.get(1).children);
        assertEquals(0, nodes.get(1).value.length);
    }

    // V12: multi-byte tags and long-form lengths 81 / 82
    @Test
    public void multiByteTagsAndLongFormLengths() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) big.append("AA");
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 300; i++) huge.append("BB");
        List<BerTlv.Node> nodes = parse("9F2102010" + "0" + "9F8101" + "81C8" + big + "C1" + "82012C" + huge);
        assertEquals(0x9F21, nodes.get(0).tag);
        assertEquals("01 00", Hex.encode(nodes.get(0).value));
        assertEquals(0x9F8101, nodes.get(1).tag);
        assertEquals(200, nodes.get(1).value.length);
        assertEquals(0xC1, nodes.get(2).tag);
        assertEquals(300, nodes.get(2).value.length);
    }

    // V12: depth-first find, including inside templates
    @Test
    public void findSearchesDepthFirst() {
        List<BerTlv.Node> nodes = parse("6F09A507" + "9F23040000000B");
        assertEquals("00 00 00 0B", Hex.encode(BerTlv.find(nodes, 0x9F23).value));
        assertNull(BerTlv.find(nodes, 0x9F24));
    }

    // V13: malformed input is rejected with IllegalArgumentException, never an index exception
    @Test
    public void malformedInputIsRejected() {
        assertRejected("50");            // tag without length
        assertRejected("5005414243");    // value overruns
        assertRejected("9F");            // truncated multi-byte tag
        assertRejected("5081");          // truncated long-form length
        assertRejected("5080");          // indefinite length
        assertRejected("508301000001");  // 3-byte length not supported
        assertRejected("6F035002AA");    // child overruns its template
        assertRejected("9FFFFFFF0100");  // tag longer than 3 bytes
    }

    @Test
    public void emptyInputParsesToNoNodes() {
        assertEquals(0, parse("").size());
    }
}
