package pk.paysys.tap2pay.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The HCE logic of the NBP app's {@code Tap2PayHceService}, without Android, for the NFC QR Test App: SELECT answers
 * the FCI, PUSH INVOICE is reassembled by {@link PushAssembler}, and a complete payload that passes the NBP
 * acceptance rule ({@link RaastQr}) is answered 9000 after {@link Listener#onAccepted} ran (the Android service opens
 * the app there, as the NBP service does before it answers). Any other payload is answered 6A80.
 *
 * Status words and their order match the NBP service: a request shorter than 4 bytes 6A80; PUSH before SELECT 6985
 * (checked before the class and instruction); CLA other than 80 6E00; INS other than CA 6D00. A listener that
 * throws does not change the answer: the NBP service also answers 9000 when it could not open the app.
 *
 * It also keeps a log of the session ({@link NfcSession}): a session begins at a SELECT (or at the first APDU if the
 * POS never selected) and ends when the link drops, another app is selected, or a new SELECT arrives. A QR that
 * completes is reported at once, with the log up to and including its own answer; a session that ends without any
 * complete payload is reported once, as incomplete, so a torn or broken tap is not lost. Calls must come from one
 * thread (HostApduService calls on main).
 */
public final class BankResponder {
    public interface Listener {
        /** A complete QR that passed the acceptance rule. {@code session} includes the 9000 about to be sent. */
        void onAccepted(String qr, NfcSession session);

        /** A complete payload that did not; {@code problem} says why. {@code session} includes the 6A80. */
        void onRejected(String payload, String problem, NfcSession session);

        /** The session ended and never delivered a complete payload. */
        void onIncomplete(NfcSession session);
    }

    private final PushAssembler assembler = new PushAssembler();
    private final Listener listener;
    private final PhoneResponder.Clock clock;
    private boolean selected;

    /** The session in progress, or null. */
    private List<NfcSession.Exchange> log;
    private long startedWallMs;
    private long startedMonoMs;
    /** A payload completed in this session (accepted or not), so it is not reported again as incomplete. */
    private boolean reported;

    /** Set by {@link #handle} when a payload completes, read by {@link #onApdu}. */
    private String completedPayload;
    private String completedProblem;

    public BankResponder(PhoneResponder.Clock clock, Listener listener) {
        this.clock = clock;
        this.listener = listener;
    }

    public byte[] onApdu(byte[] apdu) {
        long now = clock.monotonicMs();
        if (PhoneResponder.isSelect(apdu)) {
            endSession("new SELECT");
            begin(now);
        } else if (log == null) {
            begin(now);
        }
        completedPayload = null;
        completedProblem = null;
        byte[] response;
        try {
            response = handle(apdu);
        } catch (RuntimeException e) {
            completedPayload = null;
            response = Apdu.sw(Apdu.SW_UNKNOWN);
        }
        log.add(new NfcSession.Exchange(now - startedMonoMs, apdu, response));
        if (completedPayload != null) {
            reported = true;
            NfcSession snapshot = snapshot(null);
            if (completedProblem == null) notifyAccepted(completedPayload, snapshot);
            else notifyRejected(completedPayload, completedProblem, snapshot);
        }
        return response;
    }

    /** RF link gone or another app selected (HostApduService.onDeactivated). */
    public void onDeactivated(boolean linkLoss) {
        selected = false;
        assembler.reset();
        endSession(linkLoss ? "link lost" : "deselected");
    }

    private void begin(long nowMono) {
        log = new ArrayList<>();
        startedMonoMs = nowMono;
        startedWallMs = clock.wallMs();
        reported = false;
    }

    private void endSession(String why) {
        if (log == null) return;
        boolean lost = !reported && !log.isEmpty();
        NfcSession snapshot = snapshot(why);
        log = null;
        reported = false;
        if (lost) {
            try {
                listener.onIncomplete(snapshot);
            } catch (RuntimeException ignored) {
                // bookkeeping must not break the next tap
            }
        }
    }

    private NfcSession snapshot(String end) {
        return new NfcSession(startedWallMs, log, end);
    }

    private byte[] handle(byte[] apdu) {
        if (apdu == null || apdu.length < 4) return Apdu.sw(Apdu.SW_BAD_DATA);
        if (PhoneResponder.isSelect(apdu)) {
            selected = true;
            assembler.reset();
            return Apdu.withSw(Apdu.FCI, Apdu.SW_OK);
        }
        if (!selected) return Apdu.sw(Apdu.SW_CONDITIONS);
        if ((apdu[0] & 0xFF) != Apdu.CLA_PROPRIETARY) return Apdu.sw(PhoneResponder.SW_CLA_NOT_SUPPORTED);
        if ((apdu[1] & 0xFF) != Apdu.INS_PUSH_INVOICE) return Apdu.sw(Apdu.SW_INS_NOT_SUPPORTED);

        PushAssembler.Result r = assembler.push(apdu);
        if (!r.complete()) return Apdu.sw(r.sw);

        String qr = new String(r.payload, StandardCharsets.UTF_8);
        completedPayload = qr;
        completedProblem = RaastQr.of(qr).problem;
        return Apdu.sw(completedProblem == null ? Apdu.SW_OK : Apdu.SW_BAD_DATA);
    }

    private void notifyAccepted(String qr, NfcSession session) {
        try {
            listener.onAccepted(qr, session);
        } catch (RuntimeException ignored) {
            // answered 9000 anyway, like the NBP service when it cannot open the app
        }
    }

    private void notifyRejected(String payload, String problem, NfcSession session) {
        try {
            listener.onRejected(payload, problem, session);
        } catch (RuntimeException ignored) {
            // the answer must not depend on bookkeeping
        }
    }
}
