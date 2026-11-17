package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMVQR-style numeric TLV (spec Clause 3.4): 2-digit tag + 2-digit length (UTF-8 byte count) + value.
 * Parsing works on bytes so that UTF-8 values (DBA, city) are measured correctly.
 */
public final class Tlv {
    private Tlv() {}

    /** Templates whose value is itself a TLV list, parsed recursively (Clause 3.4; tags 26 and 62). */
    public static final Set<String> TEMPLATES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("26", "62")));

    /** One parsed data element. {@code children} is non-null only for templates. */
    public static final class Element {
        public final String tag;
        public final String value;
        /** Value length in UTF-8 bytes, as declared in the TLV header. */
        public final int length;
        /** Byte offset of the element header within the parsed string. */
        public final int offset;
        public final List<Element> children;

        Element(String tag, String value, int length, int offset, List<Element> children) {
            this.tag = tag;
            this.value = value;
            this.length = length;
            this.offset = offset;
            this.children = children;
        }

        /** Size of the whole element (header + value) in bytes. */
        public int size() {
            return 4 + length;
        }

        /** First child with the given sub-tag, or null. */
        public Element child(String subTag) {
            if (children == null) return null;
            for (Element e : children) if (e.tag.equals(subTag)) return e;
            return null;
        }
    }

    public static String element(String tag, String value) {
        int len = value.getBytes(StandardCharsets.UTF_8).length;
        if (len > 99) throw new IllegalArgumentException("Tag " + tag + " value too long: " + len);
        return tag + String.format("%02d", len) + value;
    }

    /** Parses one level; throws IllegalArgumentException on malformed input. Duplicate tags keep the first value. */
    public static Map<String, String> parse(String payload) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element e : parseElements(payload, Collections.<String>emptySet())) {
            if (!out.containsKey(e.tag)) out.put(e.tag, e.value);
        }
        return out;
    }

    /**
     * Parses the payload into elements in wire order, recursing into {@link #TEMPLATES}.
     * Throws IllegalArgumentException on any malformed element at any level.
     */
    public static List<Element> parseElements(String payload) {
        return parseElements(payload, TEMPLATES);
    }

    public static List<Element> parseElements(String payload, Set<String> templates) {
        return parseBytes(payload.getBytes(StandardCharsets.UTF_8), templates, "");
    }

    private static List<Element> parseBytes(byte[] b, Set<String> templates, String path) {
        List<Element> out = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            if (i + 4 > b.length) throw new IllegalArgumentException("Truncated header at byte " + i + where(path));
            String tag = ascii(b, i, 2);
            String lenStr = ascii(b, i + 2, 2);
            if (!tag.matches("\\d\\d") || !lenStr.matches("\\d\\d")) {
                throw new IllegalArgumentException("Non-numeric tag/length at byte " + i + where(path));
            }
            int len = Integer.parseInt(lenStr);
            if (i + 4 + len > b.length) throw new IllegalArgumentException("Tag " + path + tag + " overruns payload");
            byte[] v = Arrays.copyOfRange(b, i + 4, i + 4 + len);
            List<Element> children = templates.contains(tag)
                    ? Collections.unmodifiableList(parseBytes(v, Collections.<String>emptySet(), path + tag + "-"))
                    : null;
            out.add(new Element(tag, new String(v, StandardCharsets.UTF_8), len, i, children));
            i += 4 + len;
        }
        return out;
    }

    private static String where(String path) {
        return path.isEmpty() ? "" : " inside template " + path.substring(0, path.length() - 1);
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.US_ASCII);
    }
}
