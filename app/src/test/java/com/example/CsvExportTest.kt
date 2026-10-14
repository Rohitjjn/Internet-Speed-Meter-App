package com.example

import com.example.util.CsvExport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CsvExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun cleanupOnlyDeletesOwnedCsvFilesOlderThanOneDay() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        fun file(name: String, at: Long) = temp.newFile(name).apply {
            writeText("test")
            assertTrue(setLastModified(at))
        }
        val old = file("speed-meter-usage-old.csv", now - day - 1)
        val boundary = file("speed-meter-tests-boundary.csv", now - day)
        val recent = file("speed-meter-tests-today.csv", now - 1000)
        val future = file("speed-meter-tests-future.csv", now + day)
        val other = file("other.csv", now - day * 2)
        val notCsv = file("speed-meter-note.txt", now - day * 2)
        val dir = temp.newFolder("speed-meter-folder.csv").apply { setLastModified(now - day * 2) }
        CsvExport.pruneExpired(temp.root, now)
        assertFalse(old.exists())
        listOf(boundary, recent, future, other, notCsv, dir).forEach { assertTrue(it.exists()) }
        CsvExport.pruneExpired(File(temp.root, "absent"), now) // no directory is harmless
    }
}
