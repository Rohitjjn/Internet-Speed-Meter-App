package com.example.util

import com.example.data.DataUsageEntity

/**
 * Builds the CSV the History screen shares (`ACTION_SEND`, `text/csv` as plain text —
 * no file, no storage permission, no `FileProvider`).
 *
 * Pure function over the stored rows: one line per day, raw **bytes** as the kernel
 * counted them (no unit rounding — a spreadsheet can convert, the app must not lose
 * precision), newest day first, exactly as the screen shows them.
 */
object UsageCsv {

    const val HEADER = "date,mobile_rx_bytes,mobile_tx_bytes,wifi_rx_bytes,wifi_tx_bytes,total_bytes"

    fun build(rows: List<DataUsageEntity>): String {
        val out = StringBuilder(HEADER.length + rows.size * 64)
        out.append(HEADER).append('\n')
        for (row in rows) {
            out.append(row.date).append(',')
                .append(row.mobileRx).append(',')
                .append(row.mobileTx).append(',')
                .append(row.wifiRx).append(',')
                .append(row.wifiTx).append(',')
                .append(row.total).append('\n')
        }
        return out.toString()
    }
}
