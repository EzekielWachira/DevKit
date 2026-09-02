package io.devkit.netdemo

import android.content.Context

/**
 * Release variant: does nothing.
 *
 * No NetKit class is referenced here, and `:netkit` is not on the release
 * classpath, so nothing from the toolkit reaches the shipped APK. The `Context`
 * parameter exists only so shared code can call one signature in both variants.
 */
@Suppress("UNUSED_PARAMETER")
fun installDebugNetworking(context: Context) = Unit
