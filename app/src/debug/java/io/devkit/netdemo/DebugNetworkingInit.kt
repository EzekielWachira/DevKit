package io.devkit.netdemo

import android.content.Context

/**
 * Debug variant: installs NetKit and the local demo backend.
 *
 * This function exists once per build type, so shared code can call it
 * unconditionally without ever naming a debug-only type. It takes a `Context`
 * because NetKit 0.2 persists saved scenarios; the release variant ignores it.
 */
fun installDebugNetworking(context: Context) {
    NetKitDemoInstaller.install(context)
}
