package dev.etorix.panoscrobbler.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.TypeConverters
import dev.etorix.panoscrobbler.api.AccountType
import dev.etorix.panoscrobbler.api.ScrobbleEvent
import dev.etorix.panoscrobbler.api.lastfm.ScrobbleData


@Entity(tableName = PendingScrobblesDao.tableName)
data class PendingScrobble(
    @PrimaryKey(autoGenerate = true)
    val _id: Long = 0,

    @Embedded
    val scrobbleData: ScrobbleData,

    val event: ScrobbleEvent,

    @field:TypeConverters(AccountBitmaskConverter::class)
    val services: Set<AccountType> = emptySet(),

    val lastFailedTimestamp: Long = System.currentTimeMillis(),
    val lastFailedReason: String? = null,

    @ColumnInfo(defaultValue = "0")
    val canForceRetry: Boolean = false,
)