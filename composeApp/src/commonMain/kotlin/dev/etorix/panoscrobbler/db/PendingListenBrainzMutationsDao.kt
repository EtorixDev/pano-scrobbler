package dev.etorix.panoscrobbler.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingListenBrainzMutationsDao {
    @Query("SELECT * FROM $tableName WHERE apiRoot = :apiRoot AND username = :username ORDER BY createdAtMillis DESC")
    fun allForAccountFlow(apiRoot: String, username: String): Flow<List<PendingListenBrainzMutation>>

    @Query("SELECT * FROM $tableName WHERE apiRoot = :apiRoot AND username = :username AND expiresAtMillis > :now ORDER BY createdAtMillis DESC")
    suspend fun activeForAccount(
        apiRoot: String,
        username: String,
        now: Long = System.currentTimeMillis()
    ): List<PendingListenBrainzMutation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mutation: PendingListenBrainzMutation)

    @Query("DELETE FROM $tableName WHERE expiresAtMillis <= :now")
    suspend fun cleanupExpired(now: Long = System.currentTimeMillis())

    @Query("SELECT _id FROM $tableName WHERE apiRoot = :apiRoot AND username = :username ORDER BY createdAtMillis DESC LIMIT -1 OFFSET :keep")
    suspend fun overflowIds(
        apiRoot: String,
        username: String,
        keep: Int = PendingListenBrainzMutation.MAX_PER_ACCOUNT,
    ): List<Long>

    @Query("DELETE FROM $tableName WHERE _id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM $tableName WHERE apiRoot = :apiRoot AND username = :username AND listenedAtMillis = :listenedAtMillis AND recordingMsid = :recordingMsid")
    suspend fun deleteExact(
        apiRoot: String,
        username: String,
        listenedAtMillis: Long,
        recordingMsid: String,
    )

    suspend fun deleteExact(mutation: PendingListenBrainzMutation) {
        deleteExact(
            apiRoot = mutation.apiRoot,
            username = mutation.username,
            listenedAtMillis = mutation.listenedAtMillis,
            recordingMsid = mutation.recordingMsid,
        )
    }

    @Transaction
    suspend fun insertBounded(mutation: PendingListenBrainzMutation) {
        cleanupExpired()
        insert(mutation)
        overflowIds(mutation.apiRoot, mutation.username)
            .takeIf { it.isNotEmpty() }
            ?.let { delete(it) }
    }

    companion object {
        const val tableName = "PendingListenBrainzMutations"
    }
}
