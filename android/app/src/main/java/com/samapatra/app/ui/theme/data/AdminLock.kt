package com.samapatra.app.data

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The admin password for this tablet. Only a salted fingerprint is kept, never the password. */
object AdminLock {
    private const val PREFS = "samapatra_admin"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val ITERATIONS = 120_000
    private const val MAX_TRIES = 5
    private const val LOCK_MS = 60_000L

    private var failures = 0
    private var lockedUntil = 0L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Sets or changes the password. Takes a moment, so call it off the main thread. */
    fun set(context: Context, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt)
        prefs(context).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        synchronized(this) {
            failures = 0
            lockedUntil = 0L
        }
    }

    /** Seconds left before another try is allowed, or 0. */
    fun secondsLocked(): Int = synchronized(this) {
        val left = lockedUntil - SystemClock.elapsedRealtime()
        if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    /** Checks a password. Takes a moment, so call it off the main thread. */
    fun check(context: Context, password: String): Boolean {
        if (secondsLocked() > 0) return false
        val p = prefs(context)
        val salt = p.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val stored = p.getString(KEY_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false

        val ok = MessageDigest.isEqual(derive(password, salt), stored)
        synchronized(this) {
            if (ok) {
                failures = 0
            } else {
                failures += 1
                if (failures >= MAX_TRIES) {
                    lockedUntil = SystemClock.elapsedRealtime() + LOCK_MS
                    failures = 0
                }
            }
        }
        return ok
    }
}