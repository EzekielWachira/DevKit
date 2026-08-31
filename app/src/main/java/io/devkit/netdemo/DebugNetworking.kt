package io.devkit.netdemo

import androidx.compose.runtime.Composable
import okhttp3.Interceptor

/**
 * The seam between shared application code and debug-only network tooling.
 *
 * This is the pattern NetKit recommends. The whole `:netkit` module is on the
 * **debug** classpath only, so nothing in `src/main` may reference it — including
 * the code that builds the `OkHttpClient`. Instead, shared code reads these
 * hooks, and the debug source set fills them in.
 *
 * In a release build nothing writes to this object: [interceptors] stays empty,
 * [console] and [launcher] stay `null`, and every NetKit class is absent from
 * the APK entirely.
 */
object DebugNetworking {

    /** Interceptors the debug build wants on the shared OkHttp client. */
    var interceptors: List<Interceptor> = emptyList()

    /** Base URL the demo screen calls. The debug build points it at a local server. */
    var baseUrl: String = "https://api.example.com"

    /** Renders the NetKit console. `null` in release. */
    var console: (@Composable (onClose: () -> Unit) -> Unit)? = null

    /** Renders a floating launcher over the app. `null` in release. */
    var launcher: (@Composable () -> Unit)? = null

    /** Resets every scenario back to normal. No-op in release. */
    var reset: () -> Unit = {}

    /** Applies one of the demo's canned QA scenarios by id. No-op in release. */
    var applyPreset: (String) -> Unit = {}

    /** True when debug tooling is present in this build. */
    val isAvailable: Boolean get() = console != null
}
