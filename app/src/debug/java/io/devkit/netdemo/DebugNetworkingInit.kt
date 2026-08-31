package io.devkit.netdemo

/**
 * Debug variant: installs NetKit and the local demo backend.
 *
 * This function exists once per build type, so shared code can call it
 * unconditionally without ever naming a debug-only type.
 */
fun installDebugNetworking() {
    NetKitDemoInstaller.install()
}
