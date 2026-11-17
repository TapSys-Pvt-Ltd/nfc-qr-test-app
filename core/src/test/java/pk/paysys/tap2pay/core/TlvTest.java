package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/** Clause 3.4 TLV grammar. */
public class TlvTest {

    @Test
    public void parsesTemplatesRecursively() {
        List<Tlv.Element> els = Tlv.parseElements(Invoice.CANONICAL);
        assertEquals(14, els.size());
        Tlv.Element t26 = els.get(2);
        assertEquals("26", t26.tag);
        assertEquals(56, t26.length);
        assertEquals(3, t26.children.size());
        assertEquals("PK29PAYS0000001123456702", t26.child("00").value);
        Tlv.Element t62 = els.get(9);
        assertEquals("62", t62.tag);
        assertEquals("REF98765", t62.child("05").value);
        assertNull(els.get(0).children);
        assertEquals("63", els.get(13).tag);
        assertEquals(226, els.get(13).offset);
    }

    @Test
    public void lengthsAreUtf8Bytes() {
        String dba = "لاہور"; // 5 Urdu letters = 10 bytes
        String el = Tlv.element("59", dba);
        assertEquals("5910" + dba, el);
        assertEquals(dba, Tlv.parse(el + "6000").get("59"));
    }

    @Test
    public void flatParseKeepsFirstDuplicateAndRawTemplates() {
        Map<String, String> m = Tlv.parse("5402AB5402CD2604ABCD");
        assertEquals("AB", m.get("54"));
        assertEquals("ABCD", m.get("26"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void overrunRejected() {
        Tlv.parse("5405AB");
    }

    @Test(expected = IllegalArgumentException.class)
    public void truncatedHeaderRejected() {
        Tlv.parse("54");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonNumericLengthRejected() {
        Tlv.parse("54A1X");
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedSubElementRejected() {
        Tlv.parseElements("2606000501");
    }

    @Test(expected = IllegalArgumentException.class)
    public void elementOver99BytesRefused() {
        Tlv.element("59", new String(new char[100]).replace('\0', 'A'));
    }
}
