package com.heybuddy.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The vault's Room database, encrypted by SQLCipher (rule 5). Opened only by [VaultDatabaseHolder].
 *
 * NEVER use fallbackToDestructiveMigration (or anything similar) when building this database.
 * There is no backup (rule 7), so a database wiped during an app upgrade would lose every
 * document's wrapped key, and with it every document, for good. Instead, every schema change must
 * raise [version] and add a real Migration. exportSchema = true writes each version's layout to
 * app/schemas (kept in git, no user data inside) so those migrations can be written and tested.
 *
 * Versions: 1 = one file per document (Tasks 1.6 to 1.10); 2 = documents with pages (Task 1.10p,
 * see [MIGRATION_1_2]); 3 = the confirmed profile details (Task 2.8, see [MIGRATION_2_3]).
 */
@Database(
    entities = [DocumentEntity::class, DocumentPageEntity::class, ProfileFactEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {

    /** Access to the `documents` and `document_pages` tables. */
    abstract fun documentDao(): DocumentDao

    /** Access to the `profile_facts` table. */
    abstract fun profileFactDao(): ProfileFactDao

    /** Every migration, in order. The holder adds them all when it opens the database. */
    companion object {
        /** All migrations, oldest first. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
