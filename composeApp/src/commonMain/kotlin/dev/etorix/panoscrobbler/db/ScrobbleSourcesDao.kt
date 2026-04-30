package dev.etorix.panoscrobbler.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import dev.etorix.panoscrobbler.utils.Stuff

@Dao
interface ScrobbleSourcesDao {
    @Query("SELECT * FROM $tableName ORDER BY _id DESC")
    suspend fun all(): List<ScrobbleSource>

    @Query("SELECT * FROM $tableName WHERE timeMillis >= :earliest AND timeMillis <= :latest ORDER BY timeMillis ASC")
    suspend fun selectBetween(earliest: Long, latest: Long): List<ScrobbleSource>

    @Query("SELECT count(1) FROM $tableName")
    suspend fun count(): Int

    @Query("SELECT * FROM $tableName WHERE ABS(timeMillis - :time) < ${Stuff.SCROBBLE_SOURCE_THRESHOLD} ORDER BY ABS(timeMillis - :time) ASC LIMIT 1")
    suspend fun findPlayer(time: Long): ScrobbleSource?

    @Query("SELECT * FROM $tableName WHERE pkg = :pkg AND timeMillis >= :earliest AND timeMillis <= :latest ORDER BY timeMillis DESC LIMIT 1")
    suspend fun findForPackageBetween(pkg: String, earliest: Long, latest: Long): ScrobbleSource?

    @Query(
        """
        SELECT * FROM $tableName
        WHERE pkg = :pkg
            AND timeMillis >= :earliest
            AND timeMillis <= :latest
            AND artist IS NOT NULL
            AND track IS NOT NULL
            AND LOWER(artist) = LOWER(:artist)
            AND LOWER(track) = LOWER(:track)
            AND (:album IS NULL OR album IS NULL OR LOWER(album) = LOWER(:album))
        ORDER BY ABS(timeMillis - :time) ASC
        LIMIT 1
        """
    )
    suspend fun findSameTrackForPackageBetween(
        pkg: String,
        artist: String,
        track: String,
        album: String?,
        time: Long,
        earliest: Long,
        latest: Long,
    ): ScrobbleSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: ScrobbleSource)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: List<ScrobbleSource>)

    @Query("DELETE FROM $tableName")
    suspend fun nuke()

    companion object {
        const val tableName = "scrobbleSources"
    }
}
