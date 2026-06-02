package com.example.data

import kotlinx.coroutines.flow.Flow

class DataUsageRepository(private val dataUsageDao: DataUsageDao) {

    val allHistory: Flow<List<DataUsageEntity>> = dataUsageDao.getAllHistory()

    suspend fun insertData(data: DataUsageEntity) {
        dataUsageDao.insertData(data)
    }

    suspend fun insertDataIgnore(data: DataUsageEntity) {
        dataUsageDao.insertDataIgnore(data)
    }

    fun getDataByDate(date: String): Flow<DataUsageEntity?> {
        return dataUsageDao.getDataByDate(date)
    }

    fun getThisMonthTotal(monthPrefix: String): Flow<Long?> {
        return dataUsageDao.getThisMonthTotal(monthPrefix)
    }

    suspend fun deleteTodayData(date: String) {
        dataUsageDao.deleteTodayData(date)
    }

    suspend fun clearAllHistory() {
        dataUsageDao.clearAllHistory()
    }
}
