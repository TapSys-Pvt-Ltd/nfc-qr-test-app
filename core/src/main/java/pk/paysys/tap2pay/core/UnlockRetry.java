package pk.paysys.tap2pay.core;

/**
 * The retry rule of the NBP app's {@code Tap2PayLaunchActivity}: on One UI a keyguard unlock request made while
 * SystemUI still occludes the lock screen for the trampoline window is cancelled about 0.3 s later without any user
 * action. No person can see and dismiss the prompt that fast, so such an early cancel is asked again once.
 */
public final class UnlockRetry {
    private UnlockRetry() {}

    /** A cancel sooner than this after the request came from SystemUI. */
    public static final long SPURIOUS_CANCEL_MS = 1000;

    /** A cancel the user could not have caused: still locked, soon after the request, and not already retried. */
    public static boolean isSpuriousCancel(long elapsedMs, boolean alreadyRetried, boolean stillLocked) {
        return !alreadyRetried && stillLocked && elapsedMs >= 0 && elapsedMs < SPURIOUS_CANCEL_MS;
    }
}
