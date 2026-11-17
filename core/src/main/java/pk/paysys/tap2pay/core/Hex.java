package pk.paysys.tap2pay.core;

public final class Hex {
    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    private Hex() {}

    public static String encode(byte[] data) {
        if (data == null) return "null";
        return encode(data, 0, data.length);
    }

    public static String encode(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = off; i < off + len; i++) {
            if (i > off) sb.append(' ');
            sb.append(DIGITS[(data[i] >> 4) & 0x0F]).append(DIGITS[data[i] & 0x0F]);
        }
        return sb.toString();
    }

    public static byte[] decode(String hex) {
        String s = hex.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
