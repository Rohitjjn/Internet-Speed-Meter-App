package com.example.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a CSV to the share sheet as a real `.csv` attachment: the text is written to the
 * app's private cache (`cache/exports/`, see `res/xml/file_paths.xml`) and exposed through
 * the manifest `FileProvider` with a one-shot read grant. Nothing touches shared storage,
 * so no permission is involved and the file can be deleted by the system at any time.
 *
 * If the provider is unavailable for any reason (misconfigured ROM, cache not writable)
 * the same CSV is shared as plain text instead — the user never loses the export.
 */
object CsvExport {

    private const val EXPORT_DIR = "exports"

    fun share(context: Context, csv: String, fileName: String, subject: String, chooserTitle: String) {
        val intent = try {
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            pruneExpired(dir)
            val file = File(dir, fileName)
            file.writeText(csv)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                clipData = android.content.ClipData.newRawUri(fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, csv)
            }
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            // No share target is not an error worth crashing for.
            e.printStackTrace()
        }
    }

    /** Best-effort cleanup of our CSVs only; keep recent shares available for a day. */
    internal fun pruneExpired(dir: File, nowMillis: Long = System.currentTimeMillis()) {
        val cutoff = nowMillis - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("speed-meter-") && file.extension == "csv" &&
                file.lastModified() in 1 until cutoff) {
                file.delete()
            }
        }
    }

    /** `speed-meter-<kind>-<yyyy-MM-dd>.csv` — sortable, ASCII, no spaces. */
    fun fileName(kind: String, day: String = DateFormatter.day()): String = "speed-meter-$kind-$day.csv"
}
