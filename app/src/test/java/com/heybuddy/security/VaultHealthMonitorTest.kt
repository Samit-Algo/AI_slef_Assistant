package com.heybuddy.security

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * Rule 14 must be CERTAIN: the recovery screen only when the key is gone for good.
 * Every other problem (Keystore busy, timeouts, unknown errors) must never offer deletion.
 */
class VaultHealthMonitorTest {

    private var hasVaultData = true
    private var hasKey = true
    private var keyCheckError: Exception? = null

    private fun monitor() = VaultHealthMonitor(
        keyExists = {
            keyCheckError?.let { error -> throw error }
            hasKey
        },
        vaultDataExists = { hasVaultData },
    )

    @Test
    fun noVaultDataMeansFirstStart() {
        hasVaultData = false
        hasKey = false

        assertEquals(VaultCondition.NO_VAULT_YET, monitor().condition.value)
    }

    @Test
    fun vaultWithItsKeyIsOk() {
        assertEquals(VaultCondition.OK, monitor().condition.value)
    }

    @Test
    fun vaultWithoutItsKeyIsGoneForGood() {
        hasKey = false

        assertEquals(VaultCondition.KEY_GONE, monitor().condition.value)
    }

    @Test
    fun aBusyOrFailingKeystoreIsNotProof() {
        keyCheckError = KeyStoreException("Keystore busy")

        assertEquals(VaultCondition.OK, monitor().condition.value)
    }

    @Test
    fun anUnknownErrorWhileCheckingIsNotProof() {
        keyCheckError = IllegalStateException("something unexpected")

        assertEquals(VaultCondition.OK, monitor().condition.value)
    }

    @Test
    fun reportingANormalErrorNeverLeadsToRecovery() {
        val health = monitor()

        health.reportKeyError(ProviderException("timeout"))
        health.reportKeyError(IOException("phone locked"))
        health.reportKeyError(KeyStoreException("busy"))

        assertEquals(VaultCondition.OK, health.condition.value)
    }

    @Test
    fun reportingProofThatTheKeyIsMissingLeadsToRecovery() {
        val health = monitor()
        hasKey = false

        health.reportKeyError(MasterKeyGoneException())

        assertEquals(VaultCondition.KEY_GONE, health.condition.value)
    }

    @Test
    fun afterErasingTheVaultItIsAFreshStart() {
        hasKey = false
        val health = monitor()
        hasVaultData = false

        health.onVaultErased()

        assertEquals(VaultCondition.NO_VAULT_YET, health.condition.value)
    }

    @Test
    fun onlyProofCountsWhenTurningErrorsIntoUnlockFailures() {
        assertEquals(UnlockFailure.KEY_GONE, failureForKeyError(MasterKeyGoneException()))
        assertEquals(UnlockFailure.KEY_GONE, failureForKeyError(IllegalStateException("wrapped", MasterKeyGoneException())))
        assertEquals(UnlockFailure.OTHER, failureForKeyError(KeyStoreException("busy")))
        assertEquals(UnlockFailure.OTHER, failureForKeyError(ProviderException("timeout")))
        assertEquals(UnlockFailure.OTHER, failureForKeyError(IllegalStateException("no screen lock")))
        assertEquals(UnlockFailure.OTHER, failureForKeyError(RuntimeException("unknown")))
    }
}
