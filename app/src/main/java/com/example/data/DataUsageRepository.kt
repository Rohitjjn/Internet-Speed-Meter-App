package com.example.data

import kotlinx.coroutines.flow.Flow

class DataUsageRepository(private val dataUsageDao: DataUsageDao) {

    val allHistory: Flow<List<DataUsageEntity>> = dataUsageDao.getAllHistory()

    suspend fun upsertData(data: DataUsageEntity) {
        dataUsageDao.upsertData(data)
    }

    fun getDataByDate(date: String): Flow<DataUsageEntity?> = dataUsageDao.getDataByDate(date)

    suspend fun getByDate(date: String): DataUsageEntity? = dataUsageDao.getByDate(date)

    fun getThisMonthTotal(monthPrefix: String): Flow<Long> = dataUsageDao.getThisMonthTotal(monthPrefix)

    suspend fun deleteByDate(date: String) {
        dataUsageDao.deleteByDate(date)
    }

    suspend fun clearAll() {
        dataUsageDao.clearAll()
    }
}
