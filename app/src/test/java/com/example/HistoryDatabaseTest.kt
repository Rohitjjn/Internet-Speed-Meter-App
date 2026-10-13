package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.DataUsageDao
import com.example.data.DataUsageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoryDatabaseTest {

    private lateinit var database: AppDatabase
    private lateinit var dataUsageDao: DataUsageDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataUsageDao = database.dataUsageDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun testInsertAndRetrieveByDate() = runTest {
        val entry = DataUsageEntity(
            date = "2026-05-27",
            mobileRx = 100L * 1024 * 1024,
            mobileTx = 10L * 1024 * 1024,
            wifiRx = 500L * 1024 * 1024,
            wifiTx = 60L * 1024 * 1024
        )
        dataUsageDao.upsertData(entry)

        val result = dataUsageDao.getDataByDate("2026-05-27").first()
        assertNotNull(result)
        assertEquals(entry.date, result?.date)
        assertEquals(entry.mobileRx, result?.mobileRx)
        assertEquals(entry.mobileTx, result?.mobileTx)
        assertEquals(entry.wifiRx, result?.wifiRx)
        assertEquals(entry.wifiTx, result?.wifiTx)
    }

    @Test
    fun testUpsertReplacesSameDate() = runTest {
        dataUsageDao.upsertData(DataUsageEntity("2026-05-27", 1, 1, 1, 1))
        dataUsageDao.upsertData(DataUsageEntity("2026-05-27", 10, 20, 30, 40))

        val result = dataUsageDao.getByDate("2026-05-27")
        assertEquals(10L, result?.mobileRx)
        assertEquals(20L, result?.mobileTx)
        assertEquals(30L, result?.wifiRx)
        assertEquals(40L, result?.wifiTx)
    }

    @Test
    fun testMissingDateReturnsNull() = runTest {
        assertNull(dataUsageDao.getByDate("2000-01-01"))
    }

    @Test
    fun testMonthlyTotalSumsAllTransports() = runTest {
        dataUsageDao.upsertData(DataUsageEntity("2026-06-01", 100, 10, 200, 20))
        dataUsageDao.upsertData(DataUsageEntity("2026-06-02", 50, 5, 100, 10))
        dataUsageDao.upsertData(DataUsageEntity("2026-07-01", 999, 999, 999, 999)) // other month

        val total = dataUsageDao.getThisMonthTotal("2026-06-%").first()
        assertEquals(495L, total)
    }

    @Test
    fun testDeleteAndClear() = runTest {
        dataUsageDao.upsertData(DataUsageEntity("2026-06-01", 1, 1, 1, 1))
        dataUsageDao.upsertData(DataUsageEntity("2026-06-02", 1, 1, 1, 1))

        dataUsageDao.deleteByDate("2026-06-01")
        assertNull(dataUsageDao.getByDate("2026-06-01"))
        assertNotNull(dataUsageDao.getByDate("2026-06-02"))

        dataUsageDao.clearAll()
        assertNull(dataUsageDao.getByDate("2026-06-02"))
    }
}
