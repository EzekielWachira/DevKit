package io.devkit.fillkit.debug.deeplink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import io.devkit.fillkit.FillKit
import io.devkit.fillkit.debug.activation.FillKitActivationEngine

/**
 * Debug-only deep-link entry point. It validates the link, queues the activation
 * and hands control back to whatever the application's launcher activity is; it
 * never renders the application itself.
 */
class FillKitDeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expectedScheme = FillKitDeepLink.scheme(packageName)
        when (
            val parsed = FillKitDeepLink.parse(
                uri = intent?.dataString,
                expectedScheme = expectedScheme,
                allowFieldOverrides = FillKit.debugConfig.allowDeepLinkFieldOverrides,
            )
        ) {
            is FillDeepLinkResult.Invalid -> reject(parsed.reason)
            is FillDeepLinkResult.Activation -> {
                FillKitActivationEngine.submitFromColdStart(applicationContext, parsed.request)
                Log.i(TAG, "queued FillKit activation for form \"${parsed.request.formId}\"")
                launchApplication()
            }
        }
        finish()
    }

    private fun reject(reason: String) {
        Log.e(TAG, "rejected FillKit deep link: $reason")
        runCatching { Toast.makeText(this, "FillKit link rejected: $reason", Toast.LENGTH_LONG).show() }
    }

    /** Never assumes an activity name; the launcher intent comes from the package manager. */
    private fun launchApplication() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Log.e(TAG, "no launcher activity found for $packageName; open the application yourself")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
    }

    private companion object {
        const val TAG = "FillKit"
    }
}
