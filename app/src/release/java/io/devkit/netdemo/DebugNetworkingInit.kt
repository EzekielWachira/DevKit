package io.devkit.netdemo

/**
 * Release variant: does nothing.
 *
 * No NetKit class is referenced here, and `:netkit` is not on the release
 * classpath, so nothing from the toolkit reaches the shipped APK.
 */
fun installDebugNetworking() = Unit
