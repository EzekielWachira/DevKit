package io.devkit.chartkit.interaction

/**
 * What a reader may do to a map.
 *
 * One value rather than five booleans threaded through every map composable, so
 * "this map is a static illustration" and "this map is explorable" are each one
 * expression rather than five that have to agree.
 *
 * @param select whether a tap picks a region, mark or route.
 * @param zoom whether pinch, `+`/`-` and the wheel change the magnification.
 * @param pan whether a drag and the arrow keys move the camera.
 * @param doubleTapZoom whether a double tap zooms in a step.
 *
 *   **Off by default, and that is a considered default.** Compose withholds
 *   every single tap for the length of the double-tap window while a
 *   double-tap handler is installed, so turning this on delays *every* region
 *   selection by roughly a third of a second. That is the wrong price for a
 *   shortcut that `reset()`, the `0` key and a pinch already cover — but it is
 *   the right trade for a map that is browsed more than it is queried, so it is
 *   offered rather than forbidden.
 * @param hover whether a mouse or stylus moving over the map selects what is
 *   under it, without a click. Ignored where there is no such pointer, which is
 *   most phones.
 */
data class GeoInteraction(
    val select: Boolean = true,
    val zoom: Boolean = true,
    val pan: Boolean = true,
    val doubleTapZoom: Boolean = false,
    val hover: Boolean = false,
) {
    /** True when the map responds to the camera at all. */
    val movable: Boolean get() = zoom || pan

    companion object {
        /** Tap to select, pinch to zoom, drag to pan. */
        val Default: GeoInteraction = GeoInteraction()

        /** A picture: nothing responds. */
        val None: GeoInteraction = GeoInteraction(
            select = false,
            zoom = false,
            pan = false,
            doubleTapZoom = false,
            hover = false,
        )

        /** Explorable but not queryable — a backdrop the reader can move around. */
        val CameraOnly: GeoInteraction = GeoInteraction(select = false)

        /** Everything, including the shortcuts that cost something. */
        val All: GeoInteraction = GeoInteraction(doubleTapZoom = true, hover = true)
    }
}
