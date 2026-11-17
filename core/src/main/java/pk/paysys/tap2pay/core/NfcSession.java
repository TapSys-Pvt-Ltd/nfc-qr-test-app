package pk.paysys.tap2pay.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One NFC session with a POS as the phone saw it: every command it got and every answer it gave, in order, from the
 * first SELECT (or the first stray APDU) to the end of the RF link. Written by {@link BankResponder}, judged by
 * {@link NfcReport}. Immutable; the byte arrays are copied in and out.
 */
public final class NfcSession {
    /** One command and the answer to it. */
    public static final class Exchange {
        /** Milliseconds since the session began, when the command arrived. */
        public final long atMs;
        private final byte[] command;
        private final byte[] response;

        public Exchange(long atMs, byte[] command, byte[] response) {
            this.atMs = atMs;
            this.command = command == null ? new byte[0] : command.clone();
            this.response = response == null ? new byte[0] : response.clone();
        }

        public byte[] command() {
            return command.clone();
        }

        public byte[] response() {
            return response.clone();
        }
    }

    /** Wall clock ms when the session began. */
    public final long startedWallMs;
    /** How it ended ("link lost", "deselected", "new SELECT"), or null when it was still open when copied. */
    public final String end;
    private final List<Exchange> exchanges;

    public NfcSession(long startedWallMs, List<Exchange> exchanges, String end) {
        this.startedWallMs = startedWallMs;
        this.end = end;
        this.exchanges = Collections.unmodifiableList(new ArrayList<>(exchanges));
    }

    public List<Exchange> exchanges() {
        return exchanges;
    }

    /** Text safe to keep in a tab-separated store: only hex digits, ',' and ';'. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(startedWallMs).append(';').append(end == null ? "-" : hex(end.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (Exchange e : exchanges) {
            sb.append(';').append(e.atMs).append(',').append(hex(e.command)).append(',').append(hex(e.response));
        }
        return sb.toString();
    }

    /** Null when the text is not a session written by {@link #serialize()}. */
    public static NfcSession parse(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] parts = text.split(";", -1);
        if (parts.length < 2) return null;
        try {
            long started = Long.parseLong(parts[0]);
            String end = null;
            if (!"-".equals(parts[1])) {
                byte[] b = unhex(parts[1]);
                if (b == null) return null;
                end = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            }
            List<Exchange> list = new ArrayList<>();
            for (int i = 2; i < parts.length; i++) {
                String[] f = parts[i].split(",", -1);
                if (f.length != 3) return null;
                byte[] cmd = unhex(f[1]);
                byte[] resp = unhex(f[2]);
                if (cmd == null || resp == null) return null;
                list.add(new Exchange(Long.parseLong(f[0]), cmd, resp));
            }
            return new NfcSession(started, list, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String hex(byte[] b) {
        return b.length == 0 ? "" : Hex.encode(b).replace(" ", "");
    }

    /** Null when the text is not whole hex bytes. */
    private static byte[] unhex(String s) {
        if (s.length() % 2 != 0 || !s.matches("[0-9A-Fa-f]*")) return null;
        return s.isEmpty() ? new byte[0] : Hex.decode(s);
    }
}
