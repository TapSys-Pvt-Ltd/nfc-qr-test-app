package pk.paysys.tap2pay.bankapp;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;

import pk.paysys.tap2pay.core.UnlockRetry;

/**
 * Invisible trampoline started by {@link BankHceService}, which brings the app to the front for a tap.
 * If the phone is locked it shows over the lock screen only to ask for unlock; the app itself never appears above the
 * keyguard. Once unlocked it brings {@link MainActivity} to the front like a launcher tap, and MainActivity shows the
 * tap that is pending in {@link BankStore}. If the unlock is cancelled the tap stays in the list and is not opened.
 *
 * The unlock request waits for this window to take focus. On One UI, a request made while SystemUI still occludes the
 * lock screen for this window is cancelled by that occlusion about 0.3 s later, so such an early cancel is retried
 * once ({@link UnlockRetry}).
 */
public class TapLaunchActivity extends Activity {
    private static final String TAG = BankStore.TAG;
    /** Ask for unlock anyway if the window has not taken focus by then. */
    private static final long FOCUS_WAIT_MS = 800;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable requestWithoutFocus = () -> requestUnlock("no focus after " + FOCUS_WAIT_MS + " ms");
    private KeyguardManager km;
    private boolean waitingForUnlock;
    private boolean requested;
    private boolean retried;
    private long requestedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                showWhenLocked();
                waitingForUnlock = true;
                main.postDelayed(requestWithoutFocus, FOCUS_WAIT_MS);
            } else {
                openApp();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "launch failed", e);
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) requestUnlock("window focused");
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void requestUnlock(String why) {
        if (!waitingForUnlock || requested || isFinishing() || isDestroyed()) return;
        main.removeCallbacks(requestWithoutFocus);
        requested = true;
        requestedAt = SystemClock.uptimeMillis();
        Log.d(TAG, "requesting unlock (" + why + (retried ? ", retry" : "") + ")");
        try {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    waitingForUnlock = false;
                    openApp();
                }

                @Override
                public void onDismissCancelled() {
                    long elapsed = SystemClock.uptimeMillis() - requestedAt;
                    boolean alive = !isFinishing() && !isDestroyed();
                    if (alive && UnlockRetry.isSpuriousCancel(elapsed, retried, km.isKeyguardLocked())) {
                        Log.d(TAG, "unlock cancelled by the system after " + elapsed + " ms, asking again");
                        retried = true;
                        requested = false;
                        main.post(() -> requestUnlock("after early cancel"));
                        return;
                    }
                    Log.d(TAG, "unlock cancelled after " + elapsed + " ms, tap not opened");
                    finish();
                }

                @Override
                public void onDismissError() {
                    Log.w(TAG, "unlock error, tap not opened");
                    finish();
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "requestDismissKeyguard failed, tap not opened", e);
            finish();
        }
    }

    private void showWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
    }

    private void openApp() {
        try {
            // Same as tapping the launcher icon: resumes the existing task instead of stacking a new one.
            startActivity(new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException e) {
            Log.e(TAG, "could not open MainActivity", e);
        }
        finish();
    }
}
