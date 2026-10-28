package com.heybuddy.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.heybuddy.security.requirePhoneUnlocked
import com.heybuddy.util.PAGE_FORMAT_CURRENT
import com.heybuddy.util.PAGE_FORMAT_LEGACY
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The real database migration from version 1 (one file per document) to version 2 (pages),
 * Task 1.10p. Never destructive: every old document must still open, with exactly its old bytes.
 *
 * Each test builds a real, encrypted version-1 database from the saved version-1 layout
 * (app/schemas/.../1.json, as Room's own test helper would), then lets the app's own database code
 * open it. Room runs [MIGRATION_1_2] and then checks the result against the version-2 layout; any
 * difference fails the open. (Room's test helper itself cannot run here: it needs a newer JSON
 * library than the one the app already ships.)
 *
 * Uses a test vault only (own database, folders and keys, deleted after every test).
 */
@RunWith(AndroidJUnit4::class)
class VaultMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val vault = TestVault(context, name = "migration")

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        System.loadLibrary(SQLCIPHER_LIBRARY)
        vault.deleteAll()
        vault.masterKeyManager.getOrCreateMasterKey()
    }

    @After
    fun cleanUp() {
        vault.deleteAll()
    }

    @Test
    fun everyOldDocumentBecomesADocumentWithOnePage() = runBlocking {
        createVersion1Database { database ->
            insertVersion1Row(database, DOCUMENT_A, "Sample photo", "PHOTO", MIME_JPEG, sizeBytes = 1234, wrappedKey = byteArrayOf(1, 2, 3))
            insertVersion1Row(database, DOCUMENT_B, "Sample certificate", "CERTIFICATE", MIME_PDF, sizeBytes = 5678, wrappedKey = byteArrayOf(4, 5))
        }

        // Opening runs the migration, and Room checks the result against the version-2 layout.
        vault.unlock()
        val (documents, pages, version) = vault.holder.withDatabase { room ->
            val migrated = room.openHelper.writableDatabase
            Triple(
                readRows(migrated, "SELECT id, displayName, type, createdAt, updatedAt FROM documents ORDER BY id"),
                readRows(migrated, "SELECT pageId, documentId, position, mimeType, sizeBytes, formatVersion FROM document_pages ORDER BY pageId"),
                migrated.version,
            )
        }

        assertEquals(VERSION_2, version)
        assertEquals(
            listOf(
                listOf(DOCUMENT_A, "Sample photo", "PHOTO", "100", "200"),
                listOf(DOCUMENT_B, "Sample certificate", "CERTIFICATE", "100", "200"),
            ),
            documents,
        )
        assertEquals(
            listOf(
                listOf(DOCUMENT_A, DOCUMENT_A, "0", MIME_JPEG, "1234", PAGE_FORMAT_LEGACY.toString()),
                listOf(DOCUMENT_B, DOCUMENT_B, "0", MIME_PDF, "5678", PAGE_FORMAT_LEGACY.toString()),
            ),
            pages,
        )
        val wrappedKeys = vault.holder.withDatabase { room ->
            listOf(DOCUMENT_A, DOCUMENT_B).map { pageId -> room.documentDao().findPage(pageId)!!.wrappedKey }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), wrappedKeys[0])
        assertArrayEquals(byteArrayOf(4, 5), wrappedKeys[1])
    }

    /** End to end: documents made the old way still open, with their exact bytes, after the app migrates. */
    @Test
    fun oldDocumentsStillOpenAfterTheMigration() = runBlocking {
        val photo = TestVault.sampleJpegBytes()
        val pdf = TestVault.sampleTwoPagePdfBytes()
        val photoKey = writeOldStyleFile(DOCUMENT_A, photo)
        val pdfKey = writeOldStyleFile(DOCUMENT_B, pdf)
        createVersion1Database { database ->
            insertVersion1Row(database, DOCUMENT_A, "Sample photo", "PHOTO", MIME_JPEG, photo.size.toLong(), photoKey)
            insertVersion1Row(database, DOCUMENT_B, "Sample certificate", "CERTIFICATE", MIME_PDF, pdf.size.toLong(), pdfKey)
        }

        // The app's own holder opens the version-1 database and runs the migration.
        vault.unlock()
        val documents = vault.repository.observeDocuments().first()

        assertEquals(setOf(DOCUMENT_A, DOCUMENT_B), documents.map { document -> document.id }.toSet())
        assertTrue(documents.all { document -> document.pageCount == 1 })
        assertArrayEquals(photo, openOnlyPage(DOCUMENT_A))
        assertArrayEquals(pdf, openOnlyPage(DOCUMENT_B))
        assertEquals("old files are recognised, not orphans", 0, vault.repository.removeOrphanFiles())
    }

    /** An old document can get new pages (new format) and lose its old page like any other. */
    @Test
    fun oldDocumentsCanGetNewPagesAndLoseTheOldOne() = runBlocking {
        val photo = TestVault.sampleJpegBytes()
        val photoKey = writeOldStyleFile(DOCUMENT_A, photo)
        createVersion1Database { database ->
            insertVersion1Row(database, DOCUMENT_A, "Sample photo", "PHOTO", MIME_JPEG, photo.size.toLong(), photoKey)
        }
        vault.unlock()
        val newBytes = TestVault.sampleJpegBytes().reversedArray()

        val newPage = vault.repository.preparePage(DOCUMENT_A, ByteArrayInputStream(newBytes), MIME_JPEG)
        vault.repository.addPages(DOCUMENT_A, listOf(newPage))
        val newFormat = vault.holder.withDatabase { database -> database.documentDao().findPage(newPage.pageId)!!.formatVersion }
        vault.repository.removePage(DOCUMENT_A, DOCUMENT_A)

        assertEquals(PAGE_FORMAT_CURRENT, newFormat)
        assertFalse("the old file is deleted", vault.fileCrypto.encryptedFileFor(DOCUMENT_A).exists())
        assertArrayEquals(newBytes, openOnlyPage(DOCUMENT_A))
    }

    /**
     * Builds the test vault's database as version 1, exactly as laid out in 1.json, encrypted with
     * the test vault's own passphrase, and lets [addRows] fill it.
     */
    private fun createVersion1Database(addRows: (SupportSQLiteDatabase) -> Unit) {
        val schemaText = instrumentation.context.assets.open(VERSION_1_SCHEMA).bufferedReader().use { reader -> reader.readText() }
        val schema = JSONObject(schemaText).getJSONObject("database")
        val path = context.getDatabasePath(vault.databaseName)
        path.parentFile?.mkdirs()
        val passphrase = vault.keyProvider.getPassphrase()
        val noHook: SQLiteDatabaseHook? = null
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        val database = SQLiteDatabase.openDatabase(path.path, passphrase, null, flags, noHook)
        try {
            runSchemaStatements(database, schema)
            database.version = VERSION_1
            addRows(database)
        } finally {
            database.close()
            passphrase.fill(0)
        }
    }

    /** The CREATE statements and Room's own bookkeeping rows, from the saved layout. */
    private fun runSchemaStatements(database: SupportSQLiteDatabase, schema: JSONObject) {
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val createSql = entity.getString("createSql").replace(TABLE_NAME_PLACEHOLDER, entity.getString("tableName"))
            database.execSQL(createSql)
        }
        val setupQueries = schema.getJSONArray("setupQueries")
        for (index in 0 until setupQueries.length()) {
            database.execSQL(setupQueries.getString(index))
        }
    }

    /**
     * Writes a document exactly as the app did before Task 1.10p, independently of today's code:
     * vault/<id>.bin and a wrapped key, both bound to "document:<id>".
     *
     * @return The wrapped key, as version 1 stored it in the documents table.
     */
    private fun writeOldStyleFile(documentId: String, plaintext: ByteArray): ByteArray {
        val documentKey = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB, RegistryConfiguration.get())
        val associatedData = "document:$documentId".toByteArray(Charsets.UTF_8)
        val masterAead = AndroidKeystore.getAead(vault.masterKeyManager.keyAlias)
        val streamingAead = documentKey.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        vault.vaultDir.mkdirs()
        vault.fileCrypto.encryptedFileFor(documentId).outputStream().use { fileOutput ->
            streamingAead.newEncryptingStream(fileOutput, associatedData).use { encrypting -> encrypting.write(plaintext) }
        }
        return TinkProtoKeysetFormat.serializeEncryptedKeyset(documentKey, masterAead, associatedData, RegistryConfiguration.get())
    }

    @Suppress("LongParameterList")
    private fun insertVersion1Row(
        database: SupportSQLiteDatabase,
        id: String,
        name: String,
        type: String,
        mimeType: String,
        sizeBytes: Long,
        wrappedKey: ByteArray,
    ) {
        database.execSQL(
            "INSERT INTO documents (id, displayName, type, mimeType, sizeBytes, wrappedKey, createdAt, updatedAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id, name, type, mimeType, sizeBytes, wrappedKey, CREATED_AT, UPDATED_AT),
        )
    }

    private suspend fun openOnlyPage(documentId: String): ByteArray {
        val page = vault.repository.documentDetails(documentId).pages.single()
        return when (val opened = vault.repository.openPage(documentId, page.pageId)) {
            is OpenedPage.Image -> opened.bytes.bytes
            is OpenedPage.Pdf -> opened.file.file.readBytes().also { opened.file.delete() }
        }
    }

    private fun readRows(database: SupportSQLiteDatabase, sql: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        database.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add((0 until cursor.columnCount).map { column -> cursor.getString(column) })
            }
        }
        return rows
    }

    private companion object {
        const val SQLCIPHER_LIBRARY = "sqlcipher"
        const val VERSION_1_SCHEMA = "com.heybuddy.data.VaultDatabase/1.json"
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val DOCUMENT_A = "6f1c2a4e-1b2c-4d3e-8f90-123456789abc"
        const val DOCUMENT_B = "7a2d3b5f-2c3d-4e4f-9a01-23456789abcd"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PDF = "application/pdf"
        const val CREATED_AT = 100L
        const val UPDATED_AT = 200L
    }
}
