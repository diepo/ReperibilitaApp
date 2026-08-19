package it.reperibilita.app.data.log

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry): Long

    @Query("SELECT * FROM log_entries ORDER BY timestampEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE timestampEpochMillis BETWEEN :fromMillis AND :toMillis ORDER BY timestampEpochMillis ASC")
    suspend fun findBetween(fromMillis: Long, toMillis: Long): List<LogEntry>

    @Query("DELETE FROM log_entries WHERE timestampEpochMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long)
}
