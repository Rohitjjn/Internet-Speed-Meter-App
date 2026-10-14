package com.example

import com.example.data.DataUsageEntity
import com.example.tile.loadTileTotal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TileUsageTest {
    @Test
    fun runningMeterPrefersSameDayAccountingWithoutReadingRoom() = runTest {
        val total = loadTileTotal("2026-10-14", true, DataUsageEntity("2026-10-14", mobileRx = 420L)) {
            error("fresh in-memory row should avoid a database read")
        }
        assertEquals(420L, total)
    }

    @Test
    fun stoppedMeterAndMidnightUsePersistedTodayInstead() = runTest {
        val today = "2026-10-14"
        val persisted = DataUsageEntity(today, wifiRx = 12L, wifiTx = 3L)
        assertEquals(15L, loadTileTotal(today, false, DataUsageEntity(today, mobileRx = 999L)) { persisted })
        assertEquals(15L, loadTileTotal(today, true, DataUsageEntity("2026-10-13", mobileRx = 999L)) { persisted })
        assertNull(loadTileTotal(today, false, persisted) { null })
    }
}
