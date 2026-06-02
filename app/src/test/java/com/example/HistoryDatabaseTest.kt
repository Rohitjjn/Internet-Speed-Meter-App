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
            mobileDataUsed = 100 * 1024L * 1024L,
            wifiDataUsed = 500 * 1024L * 1024L,
            addOnDataUsed = 0L
        )
        dataUsageDao.insertData(entry)

        val result = dataUsageDao.getDataByDate("2026-05-27").first()
        assertNotNull(result)
        assertEquals(entry.date, result?.date)
        assertEquals(entry.mobileDataUsed, result?.mobileDataUsed)
        assertEquals(entry.wifiDataUsed, result?.wifiDataUsed)
    }

    @Test
    fun testGetMonthlyTotal() = runTest {
        val entry1 = DataUsageEntity(
            date = "2026-05-01",
            mobileDataUsed = 100 * 1024L * 1024L,
            wifiDataUsed = 200 * 1024L * 1024L,
            addOnDataUsed = 50 * 1024L * 1024L
        )
        val entry2 = DataUsageEntity(
            date = "2026-05-15",
            mobileDataUsed = 150 * 1024L * 1024L,
            wifiDataUsed = 300 * 1024L * 1024L,
            addOnDataUsed = 0L
        )
        // This is a different month, should not be summed
        val entry3 = DataUsageEntity(
            date = "2026-06-01",
            mobileDataUsed = 500 * 1024L * 1024L,
            wifiDataUsed = 500 * 1024L * 1024L,
            addOnDataUsed = 500 * 1024L * 1024L
        )

        dataUsageDao.insertData(entry1)
        dataUsageDao.insertData(entry2)
        dataUsageDao.insertData(entry3)

        // Sum of entry1 & entry2: (100+200+50) + (150+300) = 350 + 450 = 800 MB
        val expectedBytes = 800 * 1024L * 1024L
        val monthTotal = dataUsageDao.getThisMonthTotal("2026-05-%").first()

        assertEquals(expectedBytes, monthTotal)
    }
}
