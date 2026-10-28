package com.heybuddy.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.security.DatabaseKeyProvider
import com.heybuddy.security.FileCrypto
import com.heybuddy.security.KeyWrapper
import com.heybuddy.security.LockStatus
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.VaultLockState
import com.heybuddy.security.requirePhoneUnlocked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks the database, [VaultDatabaseHolder] and [VaultRepository] on a real phone.
 *
 * Everything is test-only: its own database name, folders, passphrase file and a test key
 * WITHOUT the fingerprint/PIN requirement (no timing). All of it is deleted after every test.
 */
@RunWith(AndroidJUnit4::class)
class VaultRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaultDir = File(context.filesDir, "vault_repo_test")
    private val viewTempDir = File(context.noBackupFilesDir, "view_tmp_repo_test")
    private val passphraseFile = File(context.noBackupFilesDir, "db_passphrase_repo_test.bin")
    private val masterKeyManager = MasterKeyManager(keyAlias = TEST_KEY_ALIAS, requireUserAuthentication = false)
    private val keyWrapper = KeyWrapper(masterKeyManager)
    private val keyProvider = DatabaseKeyProvider(keyWrapper, passphraseFile)
    private val fileCrypto = FileCrypto(vaultDir, viewTempDir)
    private val lockState = VaultLockState()
    private val holder = VaultDatabaseHolder(context, lockState, keyProvider, TEST_DATABASE_NAME)

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        deleteEverythingFromTests()
    }

    @After
    fun cleanUp() {
        // Runs even when a test fails, so nothing from the test stays on the phone.
        lockState.lock()
        runBlocking { holder.awaitPendingWork() }
        deleteEverythingFromTests()
    }

    @Test
    fun saveListRenameAndDeleteWork() = runBlocking {
        val repository = unlockedRepository()

        val saved = repository.saveDocument(sampleInput(), "  My Aadhaar ", DocumentType.AADHAAR, MIME_JPEG)
        val afterSave = firstList(repository)
        val renamed = repository.renameDocument(saved.id, "Aadhaar front")
        val afterRename = firstList(repository)
        repository.deleteDocument(saved.id)
        val afterDelete = firstList(repository)

        assertEquals("My Aadhaar", saved.displayName)
        assertEquals(listOf(saved), afterSave)
        assertTrue(renamed)
        assertEquals("Aadhaar front", afterRename.single().displayName)
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun databaseFileIsEncryptedOnDisk() = runBlocking {
        val repository = unlockedRepository()
        repository.saveDocument(sampleInput(), UNIQUE_NAME, DocumentType.PASSPORT, MIME_PDF)
        // Close first, so everything is written out of memory into the files we inspect.
        lockState.lock()
        holder.awaitPendingWork()

        val databaseFiles = databaseFiles().filter { file -> file.exists() }
        val mainFile = File(holder.databaseFilePath())
        val header = mainFile.readBytes().copyOf(SQLITE_HEADER.size)

        assertTrue(mainFile.exists())
        assertFalse("Plain SQLite header found", header.contentEquals(SQLITE_HEADER))
        for (file in databaseFiles) {
            val nameBytes = UNIQUE_NAME.toByteArray(Charsets.UTF_8)
            assertFalse("Name found in ${file.name}", containsSequence(file.readBytes(), nameBytes))
        }
    }

    @Test
    fun wrongPassphraseFailsAndNeverDeletesTheDatabase() = runBlocking {
        val repository = unlockedRepository()
        repository.saveDocument(sampleInput(), "Kept safe", DocumentType.PAN, MIME_JPEG)
        val rightPassphrase = keyProvider.getPassphrase()
        lockState.lock()
        holder.awaitPendingWork()
        val path = holder.databaseFilePath()
        val wrongPassphrase = ByteArray(rightPassphrase.size) { 7 }

        assertThrows(Exception::class.java) {
            openAndCount(path, wrongPassphrase)
        }
        // SQLCipher treats a wrong key like "corruption"; it must never delete the file for it.
        assertTrue("Database file was deleted", File(path).exists())
        assertEquals(1, openAndCount(path, rightPassphrase))
        rightPassphrase.fill(0)
    }

    @Test
    fun afterLockTheDatabaseIsClosedAndCannotBeUsed() = runBlocking {
        val repository = unlockedRepository()
        val saved = repository.saveDocument(sampleInput(), "Licence", DocumentType.DRIVING_LICENCE, MIME_JPEG)

        lockState.lock()
        holder.awaitPendingWork()

        assertFalse(holder.isOpenForTest())
        assertNull("Passphrase must be wiped on lock", keyProvider.cachedPassphraseForTest())
        assertThrowsSuspend<VaultLockedException> { repository.renameDocument(saved.id, "New name") }
        assertThrowsSuspend<VaultLockedException> {
            repository.saveDocument(sampleInput(), "Another", DocumentType.OTHER, MIME_JPEG)
        }
    }

    @Test
    fun whileLockedNothingOpensTheDatabase() = runBlocking {
        val repository = repository()

        assertThrowsSuspend<VaultLockedException> {
            repository.saveDocument(sampleInput(), "Never saved", DocumentType.OTHER, MIME_JPEG)
        }
        assertFalse(holder.isOpenForTest())
        assertFalse(File(holder.databaseFilePath()).exists())
    }

    @Test
    fun failedInsertLeavesNoEncryptedFileBehind() = runBlocking {
        val takenId = UUID.randomUUID().toString()
        val repository = unlockedRepository(newId = { takenId })
        // A document with this id already exists, so the save's insert must fail.
        holder.withDatabase { database -> database.documentDao().insertDocumentWithPages(rowWithoutPages(takenId), emptyList()) }

        assertThrowsSuspend<Exception> {
            repository.saveDocument(sampleInput(), "Clash", DocumentType.OTHER, MIME_JPEG)
        }
        assertFalse(fileCrypto.encryptedFileFor(takenId).exists())
        assertFalse(fileCrypto.pendingFileFor(takenId).exists())
        assertFalse(File(fileCrypto.pendingFileFor(takenId).path + ".tmp").exists())
    }

    @Test
    fun afterDeleteBothTheRowAndTheFileAreGone() = runBlocking {
        val repository = unlockedRepository()
        val saved = repository.saveDocument(sampleInput(), "To delete", DocumentType.CERTIFICATE, MIME_PDF)
        val pageFile = onlyPageFileOf(repository, saved.id)
        assertTrue(pageFile.exists())

        repository.deleteDocument(saved.id)

        val row = holder.withDatabase { database -> database.documentDao().findById(saved.id) }
        val pageRows = holder.withDatabase { database -> database.documentDao().pagesOf(saved.id) }
        assertNull(row)
        assertTrue("page rows (with their keys) must be gone", pageRows.isEmpty())
        assertFalse(pageFile.exists())
    }

    @Test
    fun passphraseCacheSurvivesWhatSqlCipherDoesWithItsCopy() = runBlocking {
        val repository = unlockedRepository()

        repository.saveDocument(sampleInput(), "Opens the database", DocumentType.PHOTO, MIME_JPEG)

        val cached = keyProvider.cachedPassphraseForTest()
        assertNotNull(cached)
        assertTrue("Cached passphrase was wiped", cached!!.any { byte -> byte != ZERO })
    }

    @Test
    fun lockThenUnlockReopensWithTheDataStillThere() = runBlocking {
        val repository = unlockedRepository()
        val saved = repository.saveDocument(sampleInput(), "Survives", DocumentType.SIGNATURE, MIME_JPEG)
        lockState.lock()
        holder.awaitPendingWork()

        lockState.markUnlocked()
        holder.awaitPendingWork()

        assertEquals(listOf(saved), firstList(repository))
    }

    @Test
    fun activeListObserverEndsQuietlyWhenTheVaultLocks() = runBlocking {
        val repository = unlockedRepository()
        repository.saveDocument(sampleInput(), "Shown in list", DocumentType.PHOTO, MIME_JPEG)
        val emissions = Collections.synchronizedList(mutableListOf<List<DocumentSummary>>())
        val observer = async(Dispatchers.Default) {
            runCatching { repository.observeDocuments().collect { list -> emissions.add(list) } }
        }
        withTimeout(TIMEOUT_MILLIS) { while (emissions.isEmpty()) delay(POLL_MILLIS) }

        lockState.lock()
        val outcome = withTimeout(TIMEOUT_MILLIS) { observer.await() }
        holder.awaitPendingWork()

        assertTrue("Observer failed: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals(1, emissions.first().size)
    }

    @Test
    fun observingWhileLockedEndsAtOnce() = runBlocking {
        val repository = repository()

        val emissions = withTimeout(TIMEOUT_MILLIS) { collectAll(repository) }

        assertTrue(emissions.isEmpty())
    }

    /**
     * A lock while a page is still being encrypted (before the database step): nothing is saved and
     * no file of any kind is left behind (Task 1.10p: pages are encrypted before the save itself).
     */
    @Test
    fun lockWhileAPageIsEncryptedSavesNothingAndLeavesNoFiles() = runBlocking {
        val repository = unlockedRepository()
        val gatedInput = GatedInputStream(totalBytes = SAMPLE_BYTES)
        val save = async(Dispatchers.IO) {
            runCatching { repository.saveDocument(gatedInput, "Mid-save", DocumentType.AADHAAR, MIME_JPEG) }
        }
        assertTrue("Save never started", gatedInput.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        lockState.lock()
        gatedInput.gate.countDown()
        val saveOutcome = withTimeout(TIMEOUT_MILLIS) { save.await() }
        holder.awaitPendingWork()

        assertTrue("Expected VaultLockedException, got $saveOutcome", saveOutcome.exceptionOrNull() is VaultLockedException)
        assertTrue("No file may stay behind", vaultDir.listFiles().isNullOrEmpty())
    }

    /**
     * A lock during the database step of a save: the save finishes first, then the database closes.
     * Afterwards the rows and the file all exist, and new work is refused while locked.
     */
    @Test
    fun lockDuringTheDatabaseStepLetsTheSaveFinishThenCloses() = runBlocking {
        // The repository reads the clock only inside the database step, so a clock that waits for
        // a gate holds the save right there, while the database lock is held.
        val insideDatabaseStep = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val gatedClock = {
            insideDatabaseStep.countDown()
            gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            FIXED_TIME
        }
        lockState.markUnlocked()
        holder.awaitPendingWork()
        val repository = VaultRepository(holder, lockState, keyWrapper, fileCrypto, clock = gatedClock)
        val save = async(Dispatchers.IO) {
            runCatching { repository.saveDocument(sampleInput(), "Mid-save", DocumentType.AADHAAR, MIME_JPEG) }
        }
        assertTrue("Save never reached the database", insideDatabaseStep.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        lockState.lock()
        assertEquals(LockStatus.LOCKED, lockState.status.value)
        gate.countDown()
        val saveOutcome = withTimeout(TIMEOUT_MILLIS) { save.await() }
        holder.awaitPendingWork()

        assertTrue("Save failed: ${saveOutcome.exceptionOrNull()}", saveOutcome.isSuccess)
        assertFalse(holder.isOpenForTest())
        val savedId = saveOutcome.getOrThrow().id
        lockState.markUnlocked()
        holder.awaitPendingWork()
        assertTrue("File must exist for the saved row", onlyPageFileOf(repository, savedId).exists())
        lockState.lock()
        holder.awaitPendingWork()
        assertThrowsSuspend<VaultLockedException> {
            repository.saveDocument(sampleInput(), "After lock", DocumentType.OTHER, MIME_JPEG)
        }
        lockState.markUnlocked()
        holder.awaitPendingWork()
        assertEquals(savedId, firstList(repository).single().id)
    }

    @Test
    fun invalidNameSavesNothing() = runBlocking {
        val repository = unlockedRepository()

        assertThrowsSuspend<InvalidDisplayNameException> {
            repository.saveDocument(sampleInput(), "   ", DocumentType.OTHER, MIME_JPEG)
        }
        assertTrue(vaultDir.listFiles().isNullOrEmpty())
        assertTrue(firstList(repository).isEmpty())
    }

    private fun repository(newId: () -> String = { UUID.randomUUID().toString() }): VaultRepository {
        return VaultRepository(holder, lockState, keyWrapper, fileCrypto, newId = newId)
    }

    private suspend fun unlockedRepository(
        newId: () -> String = { UUID.randomUUID().toString() },
    ): VaultRepository {
        lockState.markUnlocked()
        holder.awaitPendingWork()
        return repository(newId)
    }

    private suspend fun onlyPageFileOf(repository: VaultRepository, documentId: String): File {
        val page = repository.documentDetails(documentId).pages.single()
        return fileCrypto.encryptedFileFor(page.pageId)
    }

    private suspend fun firstList(repository: VaultRepository): List<DocumentSummary> {
        return withTimeout(TIMEOUT_MILLIS) { repository.observeDocuments().first() }
    }

    private suspend fun collectAll(repository: VaultRepository): List<List<DocumentSummary>> {
        val emissions = mutableListOf<List<DocumentSummary>>()
        repository.observeDocuments().collect { list -> emissions.add(list) }
        return emissions
    }

    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit) {
        val outcome = runCatching { block() }
        val error = outcome.exceptionOrNull()
        assertTrue("Expected ${T::class.simpleName} but got $error", error is T)
    }

    private fun openAndCount(path: String, passphrase: ByteArray): Int {
        val noHook: SQLiteDatabaseHook? = null
        val database = SQLiteDatabase.openDatabase(path, passphrase, null, SQLiteDatabase.OPEN_READWRITE, noHook)
        try {
            database.rawQuery("SELECT COUNT(*) FROM documents", null).use { cursor ->
                cursor.moveToFirst()
                return cursor.getInt(0)
            }
        } finally {
            database.close()
        }
    }

    private fun rowWithoutPages(id: String): DocumentEntity {
        return DocumentEntity(id, "Existing", DocumentType.OTHER.name, createdAt = 0, updatedAt = 0)
    }

    private fun sampleInput(): ByteArrayInputStream = ByteArrayInputStream(ByteArray(SAMPLE_BYTES) { 42 })

    private fun databaseFiles(): List<File> {
        val mainPath = holder.databaseFilePath()
        return listOf(File(mainPath), File("$mainPath-wal"), File("$mainPath-shm"), File("$mainPath-journal"))
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        val lastStart = haystack.size - needle.size
        for (start in 0..lastStart) {
            val window = haystack.copyOfRange(start, start + needle.size)
            if (window.contentEquals(needle)) return true
        }
        return false
    }

    private fun deleteEverythingFromTests() {
        context.deleteDatabase(TEST_DATABASE_NAME)
        vaultDir.deleteRecursively()
        viewTempDir.deleteRecursively()
        passphraseFile.delete()
        masterKeyManager.deleteMasterKey()
    }

    /**
     * Holds a save in the middle of encryption: signals [started] on the first read, then waits
     * for [gate] before giving any bytes. Lets the test lock the vault while a save is running.
     */
    private class GatedInputStream(private val totalBytes: Int) : InputStream() {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        private var position = 0

        override fun read(): Int {
            started.countDown()
            gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (position >= totalBytes) return -1
            position++
            return 42
        }
    }

    private companion object {
        const val TEST_KEY_ALIAS = "hb_master_key_repo_test"
        const val TEST_DATABASE_NAME = "vault_repo_test.db"
        const val UNIQUE_NAME = "ZebraPassportUniqueName"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PDF = "application/pdf"
        const val SAMPLE_BYTES = 20_000
        const val TIMEOUT_MILLIS = 10_000L
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 20L
        const val FIXED_TIME = 1_700_000_000_000L
        const val ZERO: Byte = 0

        /** The first 16 bytes of every unencrypted SQLite file. */
        val SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
