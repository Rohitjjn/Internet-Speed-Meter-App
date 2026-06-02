package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_data_usage")
data class DataUsageEntity(
    @PrimaryKey
    val date: String,            // format: YYYY-MM-DD (unique key per day)
    val mobileDataUsed: Long,     // in Bytes
    val wifiDataUsed: Long,       // in Bytes
    val addOnDataUsed: Long       // in Bytes
)
