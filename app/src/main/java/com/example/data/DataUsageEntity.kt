package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per calendar day with REAL byte counters (no simulated data).
 *
 * `rx` = bytes received (download), `tx` = bytes sent (upload).
 * Mobile = cellular transport, Wifi = Wi-Fi + Ethernet.
 */
@Entity(tableName = "daily_data_usage")
data class DataUsageEntity(
    @PrimaryKey
    val date: String,            // format: YYYY-MM-DD (unique key per day)
    val mobileRx: Long = 0L,     // bytes downloaded on mobile
    val mobileTx: Long = 0L,     // bytes uploaded on mobile
    val wifiRx: Long = 0L,       // bytes downloaded on Wi-Fi / Ethernet
    val wifiTx: Long = 0L        // bytes uploaded on Wi-Fi / Ethernet
) {
    val mobileTotal: Long get() = mobileRx + mobileTx
    val wifiTotal: Long get() = wifiRx + wifiTx
    val total: Long get() = mobileTotal + wifiTotal
}
