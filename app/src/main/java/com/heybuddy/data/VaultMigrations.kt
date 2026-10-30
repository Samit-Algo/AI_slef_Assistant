package com.heybuddy.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.heybuddy.util.PAGE_FORMAT_LEGACY

/**
 * Version 1 → 2 (Task 1.10p): documents get pages.
 *
 * Every existing document becomes a document with ONE page. That page keeps the document's file
 * (vault/<id>.bin), its wrapped key and its old "document:<id>" binding: its page id is the
 * document id and it is marked [PAGE_FORMAT_LEGACY]. Nothing is re-encrypted and nothing is lost.
 *
 * Android runs a database upgrade inside one transaction, so a crash in the middle rolls the whole
 * migration back and the next start simply tries again.
 *
 * The CREATE statements are copied exactly from app/schemas/.../2.json; Room checks the result
 * against that layout after migrating, and the migration test does too.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // Keep the old table under another name until its rows have been copied.
        db.execSQL("ALTER TABLE `documents` RENAME TO `documents_v1`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `documents` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `documents` (`id`, `displayName`, `type`, `createdAt`, `updatedAt`) " +
                "SELECT `id`, `displayName`, `type`, `createdAt`, `updatedAt` FROM `documents_v1`",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `document_pages` (`pageId` TEXT NOT NULL, `documentId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                "`wrappedKey` BLOB NOT NULL, `formatVersion` INTEGER NOT NULL, PRIMARY KEY(`pageId`), " +
                "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_document_pages_documentId_position` " +
                "ON `document_pages` (`documentId`, `position`)",
        )
        // Page id = document id, place 0, legacy binding: exactly what the old file and key need.
        db.execSQL(
            "INSERT INTO `document_pages` (`pageId`, `documentId`, `position`, `mimeType`, `sizeBytes`, " +
                "`wrappedKey`, `formatVersion`) " +
                "SELECT `id`, `id`, 0, `mimeType`, `sizeBytes`, `wrappedKey`, $PAGE_FORMAT_LEGACY FROM `documents_v1`",
        )
        db.execSQL("DROP TABLE `documents_v1`")
    }
}

/**
 * Version 2 → 3 (Task 2.8): the confirmed profile details. Only ADDS the `profile_facts` table and
 * its two indexes; no existing row changes. The statements are copied exactly from
 * app/schemas/.../3.json; Room checks the result against that layout after migrating.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_facts` (`factId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, `documentId` TEXT, `pageId` TEXT, `sourceDeletedAt` INTEGER, " +
                "`origin` TEXT NOT NULL, `chosen` INTEGER NOT NULL, `confirmedAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`factId`), " +
                "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , " +
                "FOREIGN KEY(`pageId`) REFERENCES `document_pages`(`pageId`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_facts_documentId` ON `profile_facts` (`documentId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_facts_pageId` ON `profile_facts` (`pageId`)")
    }
}
