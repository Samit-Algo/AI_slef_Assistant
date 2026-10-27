package com.heybuddy.data

import java.io.File

/**
 * Where the vault's data lives on disk. Knows nothing else, so the master key guard, the health
 * check (rule 14) and the eraser (rule 13) can all depend on it without depending on each other.
 *
 * @property databaseFile The SQLCipher database file.
 * @property passphraseFile The wrapped database passphrase.
 * @property vaultDir The encrypted files folder.
 * @property viewTempDir The decrypted-for-viewing folder.
 */
class VaultFiles(
    val databaseFile: File,
    val passphraseFile: File,
    val vaultDir: File,
    val viewTempDir: File,
) {

    /**
     * Tells whether any vault data exists.
     *
     * Used to decide whether a NEW master key may be created (only when there is no vault yet) and
     * whether a missing key means the vault is lost for good.
     *
     * @return true if the database, the passphrase file or any encrypted file exists.
     */
    fun vaultDataExists(): Boolean {
        val encryptedFiles = vaultDir.listFiles()
        val hasEncryptedFiles = encryptedFiles != null && encryptedFiles.isNotEmpty()
        return databaseFile.exists() || passphraseFile.exists() || hasEncryptedFiles
    }
}
