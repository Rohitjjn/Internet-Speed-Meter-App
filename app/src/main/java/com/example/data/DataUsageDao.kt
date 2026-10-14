package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DataUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertData(data: DataUsageEntity)

    @Query("SELECT * FROM daily_data_usage WHERE date = :date LIMIT 1")
    fun getDataByDate(date: String): Flow<DataUsageEntity?>

    /** One-shot read (used by the service to detect external resets). */
    @Query("SELECT * FROM daily_data_usage WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DataUsageEntity?

    @Query("SELECT * FROM daily_data_usage ORDER BY date DESC")
    fun getAllHistory(): Flow<List<DataUsageEntity>>

    @Query("SELECT COALESCE(SUM(mobileRx + mobileTx + wifiRx + wifiTx), 0) FROM daily_data_usage WHERE date LIKE :monthPrefix")
    fun getThisMonthTotal(monthPrefix: String): Flow<Long>

    /** Month-to-date **mobile** bytes — what the optional monthly limit is compared against. */
    @Query("SELECT COALESCE(SUM(mobileRx + mobileTx), 0) FROM daily_data_usage WHERE date LIKE :monthPrefix")
    fun getThisMonthMobileTotal(monthPrefix: String): Flow<Long>

    @Query("DELETE FROM daily_data_usage WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM daily_data_usage")
    suspend fun clearAll()
}
