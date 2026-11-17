package pk.paysys.tap2pay.bankapp;

import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import pk.paysys.tap2pay.core.Apdu;
import pk.paysys.tap2pay.core.BankResponder;
import pk.paysys.tap2pay.core.NfcSession;
import pk.paysys.tap2pay.core.PhoneResponder;
import pk.paysys.tap2pay.core.TapHistory;

/**
 * Tap2Pay via Raast receiver, answering as a bank app's HCE service should ({@link BankResponder} holds the
 * logic). A QR with a valid CRC is recorded and {@link TapLaunchActivity} is started to bring the app to the front
 * before the POS gets its 9000; any other payload is recorded as rejected and answered 6A80; a session that ends
 * without a complete payload is recorded as incomplete. Every record carries the APDUs of the session. Android calls this on
 * the main thread and starts the process if the app is closed.
 */
public class BankHceService extends HostApduService {
    private final BankResponder responder = new BankResponder(new PhoneResponder.Clock() {
        @Override
        public long monotonicMs() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public long wallMs() {
            return System.currentTimeMillis();
        }
    }, new BankResponder.Listener() {
        @Override
        public void onAccepted(String qr, NfcSession session) {
            BankStore.get(BankHceService.this).record(TapHistory.Kind.ACCEPTED, qr, null, session);
            try {
                startActivity(new Intent(BankHceService.this, TapLaunchActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (RuntimeException e) {
                // the tap stays in the list and opens the next time the app comes to the front
                Log.e(BankStore.TAG, "could not open the app", e);
            }
        }

        @Override
        public void onRejected(String payload, String problem, NfcSession session) {
            BankStore.get(BankHceService.this).record(TapHistory.Kind.REJECTED, payload, problem, session);
        }

        @Override
        public void onIncomplete(NfcSession session) {
            // a broken or torn tap: kept for the NFC log, the app does not open for it
            BankStore.get(BankHceService.this).record(TapHistory.Kind.INCOMPLETE, "", "no QR reached the phone", session);
        }
    });

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        try {
            return responder.onApdu(apdu);
        } catch (RuntimeException e) {
            Log.e(BankStore.TAG, "APDU handling failed", e);
            return Apdu.sw(Apdu.SW_UNKNOWN);
        }
    }

    @Override
    public void onDeactivated(int reason) {
        responder.onDeactivated(reason == DEACTIVATION_LINK_LOSS);
    }
}
