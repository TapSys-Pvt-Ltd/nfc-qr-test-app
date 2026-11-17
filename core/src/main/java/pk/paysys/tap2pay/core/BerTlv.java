package pk.paysys.tap2pay.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ISO 7816 / EMV BER-TLV, as used in card FCIs (e.g. the Apple VAS answer). Not the EMVQR numeric TLV in
 * {@link Tlv}. Tags up to 3 bytes; lengths in short form or 81 / 82 long form; constructed tags are recursed.
 */
public final class BerTlv {
    private BerTlv() {}

    public static final class Node {
        /** Tag bytes as an unsigned int, e.g. 0x6F, 0x9F21. */
        public final int tag;
        public final byte[] value;
        /** Non-null only for constructed tags. */
        public final List<Node> children;

        Node(int tag, byte[] value, List<Node> children) {
            this.tag = tag;
            this.value = value;
            this.children = children;
        }

        public boolean constructed() {
            return children != null;
        }

        /** Tag as hex, e.g. "50", "9F21". */
        public String tagHex() {
            String s = Integer.toHexString(tag).toUpperCase();
            return s.length() % 2 == 0 ? s : "0" + s;
        }
    }

    /** Parses {@code b[off, off+len)}; throws IllegalArgumentException on any malformed element at any level. */
    public static List<Node> parse(byte[] b, int off, int len) {
        List<Node> out = new ArrayList<>();
        int i = off;
        int end = off + len;
        while (i < end) {
            int first = b[i++] & 0xFF;
            int tag = first;
            if ((first & 0x1F) == 0x1F) {
                int more = 0;
                int next;
                do {
                    if (i >= end) throw new IllegalArgumentException("Truncated tag at byte " + (i - off));
                    if (++more > 2) throw new IllegalArgumentException("Tag longer than 3 bytes at byte " + (i - off));
                    next = b[i++] & 0xFF;
                    tag = (tag << 8) | next;
                } while ((next & 0x80) != 0);
            }
            if (i >= end) throw new IllegalArgumentException("Missing length for tag " + Integer.toHexString(tag));
            int l = b[i++] & 0xFF;
            if (l == 0x81 || l == 0x82) {
                int n = l & 0x7F;
                if (i + n > end) throw new IllegalArgumentException("Truncated length for tag " + Integer.toHexString(tag));
                l = 0;
                for (int k = 0; k < n; k++) l = (l << 8) | (b[i++] & 0xFF);
            } else if (l >= 0x80) {
                throw new IllegalArgumentException("Unsupported length form " + Integer.toHexString(l)
                        + " for tag " + Integer.toHexString(tag));
            }
            if (i + l > end) throw new IllegalArgumentException("Tag " + Integer.toHexString(tag).toUpperCase() + " overruns");
            byte[] value = Arrays.copyOfRange(b, i, i + l);
            List<Node> children = (first & 0x20) != 0 ? Collections.unmodifiableList(parse(value, 0, l)) : null;
            out.add(new Node(tag, value, children));
            i += l;
        }
        return out;
    }

    /** First node with this tag, depth-first, or null. */
    public static Node find(List<Node> nodes, int tag) {
        for (Node n : nodes) {
            if (n.tag == tag) return n;
            if (n.children != null) {
                Node c = find(n.children, tag);
                if (c != null) return c;
            }
        }
        return null;
    }
}
