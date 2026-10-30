package com.heybuddy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.heybuddy.util.TamilText

/**
 * After details are deleted, every kind that still has details but no chosen one gets its newest
 * detail chosen again (so "My details" always has one value to show per kind). Shared with
 * [DocumentDao], whose page and document deletes can remove details too.
 */
internal const val REPAIR_CHOSEN_SQL =
    "UPDATE profile_facts SET chosen = 1 WHERE factId IN (" +
        "SELECT (SELECT newest.factId FROM profile_facts newest WHERE newest.kind = kinds.kind " +
        "ORDER BY newest.confirmedAt DESC, newest.factId DESC LIMIT 1) " +
        "FROM (SELECT DISTINCT kind FROM profile_facts) kinds " +
        "WHERE NOT EXISTS (SELECT 1 FROM profile_facts picked WHERE picked.kind = kinds.kind AND picked.chosen = 1))"

/**
 * Database access for `profile_facts` (spec 7.10). Only [ProfileRepository] uses this; page and
 * document deletes handle their details inside [DocumentDao]'s own transactions.
 */
@Dao
abstract class ProfileFactDao {

    /**
     * Adds confirmed details in one transaction. A detail whose value is already stored for the same
     * kind (ignoring case, spacing and Tamil cleanup differences) is not added twice. A new value is
     * chosen only if its kind has no chosen value yet (a disagreement is shown, never decided here).
     *
     * @param rows The new rows (their `chosen` is decided here).
     * @return How many rows were added.
     */
    @Transaction
    open suspend fun insertConfirmed(rows: List<ProfileFactEntity>): Int {
        var added = 0
        for (row in rows) {
            val existing = ofKind(row.kind)
            val sameValue = existing.any { stored -> sameValue(stored.value, row.value) }
            if (sameValue) continue
            val chosen = existing.none { stored -> stored.chosen }
            insert(row.copy(chosen = chosen))
            added++
        }
        return added
    }

    /**
     * Makes one detail the chosen value of its kind (the others of that kind are no longer chosen).
     *
     * @param factId The detail the user picked.
     * @return False if there is no such detail.
     */
    @Transaction
    open suspend fun choose(factId: String): Boolean {
        val fact = findById(factId) ?: return false
        clearChosen(fact.kind)
        setChosen(factId)
        return true
    }

    /**
     * Deletes one detail; if it was the chosen one, the newest remaining of its kind is chosen.
     *
     * @param factId The detail.
     * @return False if there is no such detail.
     */
    @Transaction
    open suspend fun deleteAndRepair(factId: String): Boolean {
        val deleted = delete(factId)
        repairChosen()
        return deleted > 0
    }

    /** @return Every stored detail, oldest first. */
    @Query("SELECT * FROM profile_facts ORDER BY confirmedAt, factId")
    abstract suspend fun all(): List<ProfileFactEntity>

    /** @return One detail, or null. */
    @Query("SELECT * FROM profile_facts WHERE factId = :factId")
    abstract suspend fun findById(factId: String): ProfileFactEntity?

    /**
     * Changes a detail's value (the user corrected it).
     *
     * @return How many rows changed (0 if the id does not exist).
     */
    @Query("UPDATE profile_facts SET value = :value, origin = :origin, updatedAt = :updatedAt WHERE factId = :factId")
    abstract suspend fun updateValue(factId: String, value: String, origin: String, updatedAt: Long): Int

    /** @return How many details were read from this document. */
    @Query("SELECT COUNT(*) FROM profile_facts WHERE documentId = :documentId")
    abstract suspend fun countForDocument(documentId: String): Int

    /** @return How many details were read from this page. */
    @Query("SELECT COUNT(*) FROM profile_facts WHERE pageId = :pageId")
    abstract suspend fun countForPage(pageId: String): Int

    /** @param fact A new row (ABORT on a duplicate id, never REPLACE). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(fact: ProfileFactEntity)

    @Query("SELECT * FROM profile_facts WHERE kind = :kind")
    protected abstract suspend fun ofKind(kind: String): List<ProfileFactEntity>

    @Query("UPDATE profile_facts SET chosen = 0 WHERE kind = :kind")
    protected abstract suspend fun clearChosen(kind: String)

    @Query("UPDATE profile_facts SET chosen = 1 WHERE factId = :factId")
    protected abstract suspend fun setChosen(factId: String)

    @Query("DELETE FROM profile_facts WHERE factId = :factId")
    protected abstract suspend fun delete(factId: String): Int

    @Query(REPAIR_CHOSEN_SQL)
    protected abstract suspend fun repairChosen()

    /** The same detail written two ways ("S. Murugan" / "S.  MURUGAN") is one detail. */
    private fun sameValue(first: String, second: String): Boolean = comparable(first) == comparable(second)

    private fun comparable(value: String): String {
        val cleaned = TamilText.clean(value)
        return cleaned.uppercase().replace(WHITESPACE, " ").trim()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
