package pk.paysys.tap2pay.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reverse ("tag") route: the POS is the card and the phone reads the QR. Answers two ways on one HCE service:
 * <ul>
 *   <li>NFC Forum Type 4 Tag v2.0 (AID D2760000850101): CC file E103, NDEF file E104 holding one MIME record
 *       {@link #MIME_TYPE} whose payload is the QR string. Read by iOS {@code NFCNDEFReaderSession} and by
 *       Android reader mode.</li>
 *   <li>Tap2Pay AID F0010203030605: SELECT returns the spec FCI, then GET INVOICE ({@code 80 CB}, P1P2 = offset)
 *       returns up to Le bytes, SW 6310 while more remains and 9000 on the last chunk.</li>
 * </ul>
 * The QR is snapshotted at SELECT, so replacing it mid-read never mixes two invoices. Not thread-safe on its own:
 * HCE delivers APDUs on one thread; {@link #setQr} may be called from any thread.
 */
public final class TagResponder {
    public static final byte[] NDEF_AID = Hex.decode("D2760000850101");
    public static final String MIME_TYPE = "application/vnd.paysys.raastqr";
    public static final int INS_GET_INVOICE = 0xCB;
    public static final int SW_MORE_DATA = 0x6310;
    public static final int SW_FILE_NOT_FOUND = 0x6A82;
    public static final int SW_WRONG_OFFSET = 0x6B00;
    /** Largest READ BINARY / GET INVOICE response we send (fits a short APDU response on every controller). */
    public static final int MAX_LE = 0xF6;
    static final int MAX_NDEF_FILE = 1024;
    private static final int CC_FILE = 0xE103;
    private static final int NDEF_FILE = 0xE104;

    private enum Selected { NONE, NDEF_APP, CC, NDEF, TAP2PAY }

    private volatile String qr;
    private Selected selected = Selected.NONE;
    private byte[] cc;
    private byte[] ndefFile;
    private byte[] qrBytes;

    /**
     * The QR served from the next SELECT on; null or empty makes both AIDs answer 6A82 (POS idle, no tag).
     * Throws IllegalArgumentException if it does not fit the NDEF file, so a bad QR never reaches a tap.
     */
    public void setQr(String qr) {
        if (qr != null && qr.isEmpty()) qr = null;
        if (qr != null) ndefFile(qr.getBytes(StandardCharsets.UTF_8));
        this.qr = qr;
    }

    public String qr() {
        return qr;
    }

    /** Call from HostApduService.onDeactivated: the next tap starts unselected. */
    public void reset() {
        selected = Selected.NONE;
        ndefFile = null;
        qrBytes = null;
    }

    public byte[] process(byte[] apdu) {
        if (apdu == null || apdu.length < 4) return Apdu.sw(Apdu.SW_WRONG_LENGTH);
        int cla = apdu[0] & 0xFF, ins = apdu[1] & 0xFF, p1 = apdu[2] & 0xFF, p2 = apdu[3] & 0xFF;
        if (cla == 0x00 && ins == 0xA4) return select(apdu, p1);
        if (cla == 0x00 && ins == 0xB0) return readBinary(apdu, p1, p2);
        if (cla == Apdu.CLA_PROPRIETARY && ins == INS_GET_INVOICE) return getInvoice(apdu, p1, p2);
        return Apdu.sw(Apdu.SW_INS_NOT_SUPPORTED);
    }

    private byte[] select(byte[] apdu, int p1) {
        byte[] id = data(apdu);
        if (id == null) return Apdu.sw(Apdu.SW_WRONG_LENGTH);
        if (p1 == 0x04) {
            String current = qr;
            boolean ndef = Arrays.equals(id, NDEF_AID), t2p = Arrays.equals(id, Apdu.AID);
            if ((!ndef && !t2p) || current == null) {
                reset();
                return Apdu.sw(SW_FILE_NOT_FOUND);
            }
            qrBytes = current.getBytes(StandardCharsets.UTF_8);
            if (ndef) {
                ndefFile = ndefFile(qrBytes);
                cc = capabilityContainer();
                selected = Selected.NDEF_APP;
                return Apdu.sw(Apdu.SW_OK);
            }
            selected = Selected.TAP2PAY;
            return Apdu.withSw(Apdu.FCI, Apdu.SW_OK);
        }
        // File select by EF id (P1 00 or 02), only inside the NDEF application
        if ((p1 == 0x00 || p1 == 0x02) && id.length == 2 && selected != Selected.NONE
                && selected != Selected.TAP2PAY) {
            int fid = ((id[0] & 0xFF) << 8) | (id[1] & 0xFF);
            if (fid == CC_FILE) {
                selected = Selected.CC;
                return Apdu.sw(Apdu.SW_OK);
            }
            if (fid == NDEF_FILE) {
                selected = Selected.NDEF;
                return Apdu.sw(Apdu.SW_OK);
            }
        }
        return Apdu.sw(SW_FILE_NOT_FOUND);
    }

    private byte[] readBinary(byte[] apdu, int p1, int p2) {
        byte[] file = selected == Selected.CC ? cc : selected == Selected.NDEF ? ndefFile : null;
        if (file == null) return Apdu.sw(Apdu.SW_NOT_ALLOWED);
        return slice(file, (p1 << 8) | p2, le(apdu), false);
    }

    private byte[] getInvoice(byte[] apdu, int p1, int p2) {
        if (selected != Selected.TAP2PAY) return Apdu.sw(Apdu.SW_CONDITIONS);
        return slice(qrBytes, (p1 << 8) | p2, le(apdu), true);
    }

    private static byte[] slice(byte[] file, int offset, int le, boolean flagMore) {
        if (offset > file.length || (offset == file.length && file.length > 0)) return Apdu.sw(SW_WRONG_OFFSET);
        int n = Math.min(le, file.length - offset);
        byte[] out = Arrays.copyOfRange(file, offset, offset + n);
        boolean more = offset + n < file.length;
        return Apdu.withSw(out, flagMore && more ? SW_MORE_DATA : Apdu.SW_OK);
    }

    /** Le of a case-2 APDU; absent or 00 means "as much as you can", capped at {@link #MAX_LE}. */
    private static int le(byte[] apdu) {
        int le = apdu.length == 5 ? apdu[4] & 0xFF : 0;
        return le == 0 ? MAX_LE : Math.min(le, MAX_LE);
    }

    /** Command data of a case-3/4 APDU, or null when Lc is missing or wrong. */
    private static byte[] data(byte[] apdu) {
        if (apdu.length < 6) return null;
        int lc = apdu[4] & 0xFF;
        if (lc == 0 || apdu.length < 5 + lc) return null;
        return Arrays.copyOfRange(apdu, 5, 5 + lc);
    }

    /** CC v2.0: MLe/MLc, then the NDEF File Control TLV (E104, max size, read free, write denied). */
    private static byte[] capabilityContainer() {
        return new byte[]{
                0x00, 0x0F, 0x20,
                0x00, (byte) MAX_LE, 0x00, (byte) MAX_LE,
                0x04, 0x06, (byte) 0xE1, 0x04,
                (byte) (MAX_NDEF_FILE >> 8), (byte) MAX_NDEF_FILE, 0x00, (byte) 0xFF};
    }

    /** NLEN (2 bytes) + an NDEF message of one MIME record; short-record form when the payload fits in a byte. */
    static byte[] ndefFile(byte[] payload) {
        byte[] type = MIME_TYPE.getBytes(StandardCharsets.US_ASCII);
        boolean sr = payload.length < 256;
        ByteArrayOutputStream rec = new ByteArrayOutputStream();
        rec.write(0x80 | 0x40 | (sr ? 0x10 : 0) | 0x02); // MB, ME, SR, TNF=2 (media type)
        rec.write(type.length);
        if (sr) {
            rec.write(payload.length);
        } else {
            rec.write(payload.length >>> 24);
            rec.write(payload.length >>> 16);
            rec.write(payload.length >>> 8);
            rec.write(payload.length);
        }
        rec.write(type, 0, type.length);
        rec.write(payload, 0, payload.length);
        byte[] msg = rec.toByteArray();
        if (msg.length + 2 > MAX_NDEF_FILE) throw new IllegalArgumentException("QR too long for the NDEF file");
        byte[] file = new byte[msg.length + 2];
        file[0] = (byte) (msg.length >> 8);
        file[1] = (byte) msg.length;
        System.arraycopy(msg, 0, file, 2, msg.length);
        return file;
    }
}
