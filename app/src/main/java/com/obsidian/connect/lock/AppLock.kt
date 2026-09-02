package com.obsidian.connect.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

/**
 * Locks the app behind the phone's own fingerprint, PIN, pattern or password.
 *
 * Deliberately not a PIN of this app's own. Storing and verifying a secret
 * correctly — salting, hashing, rate limiting, surviving a rooted device — is
 * a real security problem, and the platform already solves it with hardware
 * backing this app cannot match. Reusing the device credential also means
 * there is no new number for anyone to forget.
 */
object AppLock {

    private const val PREFS = "connect_lock"
    private const val KEY_ENABLED = "enabled"

    /**
     * Fingerprint or face, with PIN, pattern or password as the fallback.
     *
     * BIOMETRIC_WEAK rather than STRONG: this is gating a photo of someone you
     * love, not a banking session, and STRONG rules out perfectly reasonable
     * face unlock on a lot of phones.
     */
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Whether this phone can lock at all.
     *
     * A device with no fingerprint enrolled and no screen lock set has nothing
     * to authenticate against, and offering the switch there would produce a
     * setting that silently never works.
     */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompatExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onSuccess()

                /**
                 * Only terminal errors land here — a cancel, a lockout, no
                 * hardware. A wrong finger fires onAuthenticationFailed and the
                 * prompt stays up, which is what should happen.
                 */
                override fun onAuthenticationError(code: Int, message: CharSequence) = onFailure()
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Connect")
                .setSubtitle("Use your fingerprint or screen lock")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}

/** Runs callbacks on the main thread, where the UI expects them. */
private class ContextCompatExecutor(private val activity: FragmentActivity) :
    java.util.concurrent.Executor {
    override fun execute(command: Runnable) {
        activity.runOnUiThread(command)
    }
}
