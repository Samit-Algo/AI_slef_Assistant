package com.heybuddy.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The vault's Room database, encrypted by SQLCipher (rule 5). Opened only by [VaultDatabaseHolder].
 *
 * NEVER use fallbackToDestructiveMigration (or anything similar) when building this database.
 * There is no backup (rule 7), so a database wiped during an app upgrade would lose every
 * document's wrapped key, and with it every document, for good. Instead, every schema change must
 * raise [version] and add a real Migration. exportSchema = true writes each version's layout to
 * app/schemas (kept in git, no user data inside) so those migrations can be written and tested.
 */
@Database(
    entities = [DocumentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {

    /** Access to the `documents` table. */
    abstract fun documentDao(): DocumentDao
}
