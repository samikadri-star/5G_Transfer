package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<TransferRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: TransferRecord): Long

    @Query("DELETE FROM transfer_records WHERE id = :id")
    suspend fun deleteRecord(id: Long)

    @Query("DELETE FROM transfer_records")
    suspend fun clearAll()

    @Query("SELECT SUM(fileSize) FROM transfer_records WHERE transferType = 'SENT' AND status = 'SUCCESS'")
    fun getTotalSentBytes(): Flow<Long?>

    @Query("SELECT SUM(fileSize) FROM transfer_records WHERE transferType = 'RECEIVED' AND status = 'SUCCESS'")
    fun getTotalReceivedBytes(): Flow<Long?>
}
