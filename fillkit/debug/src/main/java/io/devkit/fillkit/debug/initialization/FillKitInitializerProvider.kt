package io.devkit.fillkit.debug.initialization

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.devkit.fillkit.debug.activation.FillKitActivationEngine
import io.devkit.fillkit.debug.runtime.DebugFillKitRuntime
import io.devkit.fillkit.runtime.FillKitActivator
import io.devkit.fillkit.runtime.FillKitRuntimeProvider

/** Present only in debug variants that explicitly depend on fillkit-debug. */
class FillKitInitializerProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        FillKitRuntimeProvider.install(DebugFillKitRuntime)
        FillKitRuntimeProvider.activator = FillKitActivator(FillKitActivationEngine::submit)
        // Restores a cold-start deep link before the first screen composes.
        context?.let(FillKitActivationEngine::attach)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
