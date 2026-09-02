package io.devkit.netkit.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.serialization.ScenarioExport
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/** The outcome of writing an export to a file the user can share. */
sealed interface ScenarioFileResult {

    data class Ready(val uri: Uri, val file: File, val export: ScenarioExport) : ScenarioFileResult

    data class Failed(val message: String) : ScenarioFileResult
}

/**
 * Turns an exported scenario into a file the platform can share, and reads a
 * picked file back.
 *
 * Kept strictly separate from serialization. Everything here needs a `Context`,
 * a `FileProvider` and an `Intent`; none of it needs to know what a scenario is.
 * That boundary is what lets the format be tested exhaustively on the JVM while
 * this layer stays a thin, obvious wrapper.
 *
 * ### FileProvider
 *
 * Sharing requires a `FileProvider` authority. NetKit declares one in its own
 * manifest as `${applicationId}.netkit.fileprovider`, so a consuming app needs
 * no manifest changes.
 */
object ScenarioFiles {

    /** Where exports are staged. Cleared by the platform like any cache. */
    private const val EXPORT_DIRECTORY = "netkit-exports"

    /** MIME type used for a `.netkit.json`. */
    const val MIME_TYPE: String = "application/json"

    /** The types the import picker accepts. */
    val IMPORT_MIME_TYPES: Array<String> = arrayOf("application/json", "text/plain", "*/*")

    /** The `FileProvider` authority NetKit declares for [context]. */
    fun authority(context: Context): String = "${context.packageName}.netkit.fileprovider"

    /**
     * Writes [export] into the cache and returns a shareable `content://` URI.
     *
     * Only files older than [STALE_EXPORT_MILLIS] are swept, never the whole
     * directory: a share sheet opened for an earlier export still holds a
     * `content://` URI for its file, and deleting it out from under a chooser
     * the user has not dismissed hands the receiving app nothing.
     */
    fun write(context: Context, export: ScenarioExport): ScenarioFileResult = try {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val staleBefore = System.currentTimeMillis() - STALE_EXPORT_MILLIS
        directory.listFiles()
            ?.filter { it.lastModified() < staleBefore }
            ?.forEach { it.delete() }
        val file = File(directory, export.suggestedFileName)
        file.writeText(export.content, StandardCharsets.UTF_8)
        ScenarioFileResult.Ready(
            uri = FileProvider.getUriForFile(context, authority(context), file),
            file = file,
            export = export,
        )
    } catch (error: IOException) {
        ScenarioFileResult.Failed("Could not write the scenario file: ${error.message}")
    } catch (error: IllegalArgumentException) {
        // Thrown by FileProvider when the path is not one it is configured for.
        ScenarioFileResult.Failed(
            "NetKit's FileProvider is not configured for this path: ${error.message}",
        )
    }

    /**
     * An `ACTION_SEND` intent for a written export.
     *
     * `ClipData` is not optional decoration: the system share sheet runs in its
     * own process and reads the file's name and size to build a preview.
     * `EXTRA_STREAM` alone does not grant it that access, so without the clip
     * data the chooser logs a permission denial and offers QA an unnamed
     * attachment — which is a poor thing to attach to a bug report.
     */
    fun shareIntent(context: Context, uri: Uri, title: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newUri(context.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** Writes [export] and opens the system share sheet. Returns false on failure. */
    fun share(context: Context, export: ScenarioExport): ScenarioFileResult {
        val result = write(context, export)
        if (result is ScenarioFileResult.Ready) {
            val chooser = Intent.createChooser(
                shareIntent(context, result.uri, export.suggestedFileName),
                "Share NetKit scenario",
            )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(chooser)
            } catch (error: ActivityNotFoundException) {
                return ScenarioFileResult.Failed("No app on this device can share a file.")
            }
        }
        return result
    }

    /**
     * Reads a picked document as text.
     *
     * The size cap is applied while reading, not after, so a hostile or
     * accidental multi-gigabyte file cannot be pulled into memory before being
     * rejected.
     *
     * @return the file's text, or `null` when it could not be read.
     */
    fun read(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val limit = NetKitLimits.MAX_IMPORT_BYTES + 1
            val buffer = ByteArray(READ_CHUNK)
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < limit) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            String(out.toByteArray(), StandardCharsets.UTF_8)
        }
    } catch (error: IOException) {
        null
    } catch (error: SecurityException) {
        null
    }

    private const val READ_CHUNK = 8 * 1024

    /** How long a staged export survives before the next export sweeps it. */
    private const val STALE_EXPORT_MILLIS = 60L * 60L * 1000L
}
