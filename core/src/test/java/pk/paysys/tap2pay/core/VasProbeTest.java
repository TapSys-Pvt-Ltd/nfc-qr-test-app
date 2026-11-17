package pk.paysys.tap2pay.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * One test per row of the VAS probe scenario matrix (the POS sends SELECT OSE.VAS.01 and classifies the answer).
 * The expected FCI follows Proxmark3 {@code hf vas}: 50 "ApplePay", 9F21 version 01 00, 9F24 nonce (4 B),
 * 9F23 capabilities (4 B, first three 00, bit 3 of the last = VAS may be performed).
 */
public class VasProbeTest {
    private static final String LABEL = "50084170706C65506179"; // 50 "ApplePay"
    private static final String VERSION = "9F21020100";
    private static final String NONCE = "9F240401020304";

    private static String fci(String... elements) {
        String body = String.join("", elements);
        return "6F" + String.format("%02X", body.length() / 2) + body;
    }

    private static VasProbe.Result probe(String hexResponse) {
        return VasProbe.describe(Hex.decode(hexResponse));
    }

    private static String text(VasProbe.Result r) {
        return String.join("\n", r.lines);
    }

    // V1
    @Test
    public void selectCommandIsCaseFourWithOseVasAid() {
        assertEquals("00 A4 04 00 0A 4F 53 45 2E 56 41 53 2E 30 31 00", Hex.encode(VasProbe.SELECT));
        assertEquals("OSE.VAS.01", new String(VasProbe.AID, java.nio.charset.StandardCharsets.US_ASCII));
    }

    // V2
    @Test
    public void appleFciWithVasBitIsReadyAndFullyDecoded() {
        VasProbe.Result r = probe(fci(LABEL, VERSION, NONCE, "9F23040000000B") + "9000");
        assertEquals(VasProbe.Verdict.VAS_READY, r.verdict);
        String t = text(r);
        assertTrue(t, t.contains("SW 9000 OK"));
        assertTrue(t, t.contains("50 wallet = \"ApplePay\""));
        assertTrue(t, t.contains("9F21 VAS version = 01 00"));
        assertTrue(t, t.contains("9F24 mobile nonce = 01 02 03 04"));
        assertTrue(t, t.contains("9F23 mobile capabilities = 00 00 00 0B"));
        assertTrue(t, t.contains("VAS may be performed"));
        assertTrue(t, t.contains("encrypted VAS data supported"));
        assertTrue(t, t.contains("plaintext VAS data supported"));
    }

    // V3
    @Test
    public void appleFciWithoutVasBitIsNotOffered() {
        VasProbe.Result r = probe(fci(LABEL, VERSION, NONCE, "9F230400000003") + "9000");
        assertEquals(VasProbe.Verdict.VAS_NOT_OFFERED, r.verdict);
    }

    // V4 (another wallet, or an Android HCE app that registered the AID)
    @Test
    public void nonAppleWalletLabelIsNotApple() {
        String other = "5007416E64726F6964"; // 50 "Android"
        VasProbe.Result r = probe(fci(other, VERSION, NONCE, "9F23040000000B") + "9000");
        assertEquals(VasProbe.Verdict.NOT_APPLE, r.verdict);
        assertEquals(VasProbe.Verdict.NOT_APPLE, probe(fci(VERSION, NONCE, "9F23040000000B") + "9000").verdict);
    }

    // V5
    @Test
    public void versionOtherThanOneZeroIsUnexpected() {
        assertEquals(VasProbe.Verdict.UNEXPECTED,
                probe(fci(LABEL, "9F21020200", NONCE, "9F23040000000B") + "9000").verdict);
        assertEquals(VasProbe.Verdict.UNEXPECTED,
                probe(fci(LABEL, "9F210101", NONCE, "9F23040000000B") + "9000").verdict);
        assertEquals(VasProbe.Verdict.UNEXPECTED,
                probe(fci(LABEL, NONCE, "9F23040000000B") + "9000").verdict);
    }

    // V6
    @Test
    public void capabilitiesMissingWrongLengthOrHighBytesSetAreUnexpected() {
        assertEquals(VasProbe.Verdict.UNEXPECTED, probe(fci(LABEL, VERSION, NONCE) + "9000").verdict);
        assertEquals(VasProbe.Verdict.UNEXPECTED, probe(fci(LABEL, VERSION, NONCE, "9F230300000B") + "9000").verdict);
        assertEquals(VasProbe.Verdict.UNEXPECTED, probe(fci(LABEL, VERSION, NONCE, "9F230401000008") + "9000").verdict);
    }

    // V7 (Android phone or card without the applet)
    @Test
    public void applicationNotFoundIsNotSelected() {
        VasProbe.Result r = probe("6A82");
        assertEquals(VasProbe.Verdict.NOT_SELECTED, r.verdict);
        assertTrue(text(r), text(r).contains("6A82 application not found"));
    }

    // V8
    @Test
    public void anyOtherStatusWordIsNotSelected() {
        assertEquals(VasProbe.Verdict.NOT_SELECTED, probe("6D00").verdict);
        assertEquals(VasProbe.Verdict.NOT_SELECTED, probe("6F00").verdict);
        assertEquals(VasProbe.Verdict.NOT_SELECTED, probe(fci(LABEL, VERSION, NONCE, "9F23040000000B") + "6283").verdict);
    }

    // V9
    @Test
    public void nullOrShortResponseIsNoResponse() {
        assertEquals(VasProbe.Verdict.NO_RESPONSE, VasProbe.describe(null).verdict);
        assertEquals(VasProbe.Verdict.NO_RESPONSE, VasProbe.describe(new byte[0]).verdict);
        assertEquals(VasProbe.Verdict.NO_RESPONSE, VasProbe.describe(new byte[]{(byte) 0x90}).verdict);
    }

    // V10
    @Test
    public void okWithNoDataIsMalformed() {
        assertEquals(VasProbe.Verdict.MALFORMED, probe("9000").verdict);
    }

    // V11
    @Test
    public void truncatedFciIsMalformedWithoutThrowing() {
        VasProbe.Result r = probe("6F1D50084170706C65" + "9000");
        assertEquals(VasProbe.Verdict.MALFORMED, r.verdict);
        assertTrue(text(r), text(r).contains("6F 1D 50 08 41 70 70 6C 65"));
    }

    // V12 (unknown tags are listed, not dropped; FCI found even when not the first element)
    @Test
    public void unknownTagsAreListedAndDoNotChangeTheVerdict() {
        VasProbe.Result r = probe("9F7F0155" + fci(LABEL, VERSION, NONCE, "9F23040000000B", "9F0102ABCD") + "9000");
        assertEquals(VasProbe.Verdict.VAS_READY, r.verdict);
        assertTrue(text(r), text(r).contains("9F01 = AB CD"));
        assertTrue(text(r), text(r).contains("9F7F = 55"));
    }

    // V14 (raw response always logged, whatever the verdict)
    @Test
    public void rawResponseIsAlwaysLogged() {
        assertTrue(text(VasProbe.describe(null)).contains("raw: null"));
        assertTrue(text(probe("6A82")).contains("raw: 6A 82"));
        String ok = fci(LABEL, VERSION, NONCE, "9F23040000000B") + "9000";
        assertTrue(text(probe(ok)).contains("raw: " + Hex.encode(Hex.decode(ok))));
    }

    // Every verdict has a one-line summary for the RESULT line
    @Test
    public void everyVerdictHasASummary() {
        for (VasProbe.Verdict v : VasProbe.Verdict.values()) {
            assertTrue(v.name(), v.summary.length() > 10);
        }
    }
}
