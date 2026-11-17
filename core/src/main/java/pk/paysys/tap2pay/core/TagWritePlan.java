package pk.paysys.tap2pay.core;

import java.util.Arrays;

/**
 * What the sticker writer does with a tag it has just read. Kept free of android.nfc so every case is unit-tested;
 * the app passes in what {@code Ndef} / {@code NdefFormatable} reported. Never makes a tag read-only.
 */
public final class TagWritePlan {
    public enum Action {
        /** Not armed: report what is on the tag and leave it alone. */
        INSPECT_ONLY,
        WRITE,
        /** Blank tag that is not NDEF-formatted yet; format() writes the message in the same step. */
        FORMAT_AND_WRITE,
        TOO_SMALL,
        READ_ONLY,
        /** Neither NDEF nor formatable here: a bank card, or MIFARE Classic on a phone without NXP support. */
        NOT_WRITABLE
    }

    private TagWritePlan() {}

    /**
     * @param ndefMaxSize Ndef.getMaxSize(), ignored when {@code hasNdef} is false
     * @param messageSize NdefMessage.getByteArrayLength() of what we want to write
     */
    public static Action decide(boolean armed, boolean hasNdef, boolean ndefWritable, int ndefMaxSize,
                                boolean formatable, int messageSize) {
        if (!armed) return Action.INSPECT_ONLY;
        if (hasNdef) {
            if (!ndefWritable) return Action.READ_ONLY;
            return messageSize <= ndefMaxSize ? Action.WRITE : Action.TOO_SMALL;
        }
        return formatable ? Action.FORMAT_AND_WRITE : Action.NOT_WRITABLE;
    }

    /** Read-back check: the first record's payload must be exactly what was written. */
    public static boolean verified(byte[] written, byte[] readBack) {
        return written != null && readBack != null && Arrays.equals(written, readBack);
    }
}
