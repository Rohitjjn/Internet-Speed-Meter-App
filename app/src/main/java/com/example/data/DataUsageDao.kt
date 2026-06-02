package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DataUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertData(data: DataUsageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDataIgnore(data: DataUsageEntity)

    @Query("SELECT * FROM daily_data_usage WHERE date = :date LIMIT 1")
    fun getDataByDate(date: String): Flow<DataUsageEntity?>

    @Query("SELECT * FROM daily_data_usage ORDER BY date DESC")
    fun getAllHistory(): Flow<List<DataUsageEntity>>

    @Query("SELECT SUM(mobileDataUsed + wifiDataUsed + addOnDataUsed) FROM daily_data_usage WHERE date LIKE :monthPrefix")
    fun getThisMonthTotal(monthPrefix: String): Flow<Long?>

    @Query("DELETE FROM daily_data_usage WHERE date = :date")
    suspend fun deleteTodayData(date: String)

    @Query("DELETE FROM daily_data_usage")
    suspend fun clearAllHistory()
}
