package com.obsidian.connect.lock

import android.content.Context
import java.security.SecureRandom
import java.security.MessageDigest
import android.util.Base64
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
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"

    /**
     * Fingerprint or face, with PIN, pattern or password as the fallback.
     *
     * BIOMETRIC_WEAK rather than STRONG: this is gating a photo of someone you
     * love, not a banking session, and STRONG rules out perfectly reasonable
     * face unlock on a lot of phones.
     */
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /**
     * When the app was last unlocked, in memory only.
     *
     * Opening a photo, the jam, or settings starts another activity, which
     * stops the main one - and re-locking on every stop meant being asked for a
     * fingerprint on the way back from anywhere in the app. A short grace
     * window tells the difference between stepping into another of our own
     * screens and actually putting the phone down.
     *
     * Not persisted on purpose: the process dying should mean locking again.
     */
    private var unlockedAt: Long = 0L

    fun markUnlocked() {
        unlockedAt = System.currentTimeMillis()
    }

    fun forget() {
        unlockedAt = 0L
    }

    /**
     * Whether the unlock still stands.
     *
     * Thirty seconds is long enough to cover cropping a photo or picking one
     * from the gallery, and short enough that a phone handed to somebody else
     * is locked by the time they look at it.
     */
    fun isStillUnlocked(): Boolean =
        unlockedAt > 0 && System.currentTimeMillis() - unlockedAt < GRACE_MS

    private const val GRACE_MS = 30_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Whether a fingerprint opens the app.
     *
     * On by default, because it is the reason most people turn a lock on at
     * all. Switching it off leaves the PIN - or the phone's own screen lock -
     * as the only way in, which is a reasonable thing to want on a phone whose
     * fingerprints are not all yours.
     */
    fun isFingerprintEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FINGERPRINT, true)

    fun setFingerprintEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FINGERPRINT, enabled).apply()
    }

    /**
     * Whether the app has a PIN of its own.
     *
     * Without one it falls back to the phone's screen lock, which is the
     * sensible default - one fewer thing to remember, and the phone's own PIN
     * is already the thing standing between anybody and everything else. A
     * separate PIN only matters when somebody else knows the phone's.
     */
    fun hasOwnPin(context: Context): Boolean =
        prefs(context).getString(KEY_PIN_HASH, null) != null

    /**
     * Stores a PIN as a salted hash.
     *
     * Not because this is a bank, but because a PIN written down in plain text
     * on a phone is worse than no PIN - people reuse them, and the one guarding
     * a photo app is very often the one guarding the phone.
     */
    fun setOwnPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, hash(pin, salt))
            .apply()
    }

    fun clearOwnPin(context: Context) {
        prefs(context).edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).apply()
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
        val salt = prefs(context).getString(KEY_PIN_SALT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return false

        return hash(pin, salt) == stored
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return Base64.encodeToString(digest.digest(pin.toByteArray()), Base64.NO_WRAP)
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

    /**
     * Whether the biometric prompt is worth showing at all.
     *
     * False when fingerprints are switched off here, and false when the app has
     * its own PIN and no fingerprint - in that case the only way in is the
     * screen this app draws itself.
     */
    fun canPrompt(context: Context): Boolean =
        isFingerprintEnabled(context) && isAvailable(context)

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

        // With a PIN of its own, the phone's screen lock is deliberately not
        // offered as the fallback - the whole point of setting one is that the
        // phone's own credential should not open this.
        val allowed = if (hasOwnPin(activity)) BIOMETRIC_WEAK else AUTHENTICATORS

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Connect")
            .setSubtitle(
                if (hasOwnPin(activity)) {
                    "Use your fingerprint, or enter your Connect PIN"
                } else {
                    "Use your fingerprint or screen lock"
                },
            )
            .setAllowedAuthenticators(allowed)
            .apply {
                // A prompt with no device-credential fallback must offer its
                // own way out, or there is no button but the back gesture.
                if (allowed == BIOMETRIC_WEAK) setNegativeButtonText("Use PIN")
            }
            .build()

        prompt.authenticate(info)
    }
}

/** Runs callbacks on the main thread, where the UI expects them. */
private class ContextCompatExecutor(private val activity: FragmentActivity) :
    java.util.concurrent.Executor {
    override fun execute(command: Runnable) {
        activity.runOnUiThread(command)
    }
}
