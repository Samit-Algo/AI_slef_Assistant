package com.heybuddy.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The vault's overall state, as far as the master key is concerned. */
enum class VaultCondition {
    /** No vault yet (first start, or after "Delete everything"). A new key may be created. */
    NO_VAULT_YET,

    /** A vault exists, and as far as we can tell its key is fine (or we are not sure it is not). */
    OK,

    /** CERTAIN: the vault exists but its key is gone for good. Only then the recovery screen shows (rule 14). */
    KEY_GONE,
}

/**
 * Tells whether an error is CERTAIN proof that the master key is gone for good (rule 14).
 *
 * Only two things count: the key no longer exists while vault data does ([MasterKeyGoneException]),
 * or Android says it was permanently invalidated ([KeyPermanentlyInvalidatedException]). Anything
 * else (Keystore busy, phone locked, timeout, an unknown error) is NOT proof; those must lead to a
 * normal "try again" message and never to "Delete everything".
 *
 * @param error Any error from using the master key.
 * @return true only for certain proof.
 */
fun isProofKeyIsGone(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is MasterKeyGoneException || current is KeyPermanentlyInvalidatedException) return true
        current = current.cause
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 16

/**
 * Watches whether the vault can still be opened (rule 14), for the top-level screen choice.
 *
 * Decides "key gone" ONLY on certain evidence. If checking the key itself fails (for example the
 * Keystore is busy), the answer is [VaultCondition.OK]: being unsure must never offer deletion.
 *
 * @param keyExists Whether the master key alias exists in Keystore (may throw).
 * @param vaultDataExists Whether any vault data exists (database, passphrase file or encrypted files).
 */
class VaultHealthMonitor(
    private val keyExists: () -> Boolean,
    private val vaultDataExists: () -> Boolean,
) {

    // Set when Android said the key was permanently invalidated. Its alias may still exist, so a
    // later check alone would wrongly say OK; this keeps the answer until the vault is erased.
    private var knownInvalidated = false

    private val mutableCondition = MutableStateFlow(VaultCondition.OK)

    /** The current condition. */
    val condition: StateFlow<VaultCondition> = mutableCondition.asStateFlow()

    init {
        refresh()
    }

    /** Checks again (called at start, when the app is back in front, and after errors). */
    fun refresh() {
        mutableCondition.value = evaluate()
    }

    /**
     * Records CERTAIN proof from using the key. Ignores anything that is not proof.
     *
     * @param error The error from the master key.
     */
    fun reportKeyError(error: Throwable) {
        if (!isProofKeyIsGone(error)) return
        if (error.isInvalidation()) knownInvalidated = true
        refresh()
    }

    /** Called after "Delete everything": the old, broken vault is gone. */
    fun onVaultErased() {
        knownInvalidated = false
        refresh()
    }

    private fun evaluate(): VaultCondition {
        val hasVaultData = try {
            vaultDataExists()
        } catch (unsure: Exception) {
            return VaultCondition.OK
        }
        if (!hasVaultData) return VaultCondition.NO_VAULT_YET
        if (knownInvalidated) return VaultCondition.KEY_GONE
        val hasKey = try {
            keyExists()
        } catch (unsure: Exception) {
            // Keystore busy or failing: not proof that the key is gone.
            return VaultCondition.OK
        }
        return if (hasKey) VaultCondition.OK else VaultCondition.KEY_GONE
    }

    private fun Throwable.isInvalidation(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is KeyPermanentlyInvalidatedException) return true
            current = current.cause
            depth++
        }
        return false
    }
}
