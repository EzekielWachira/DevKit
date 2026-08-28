package io.devkit.fillkit.debug.overlay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.devkit.fillkit.FieldOverlayPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldOverlayPositionerTest {

    private val container = IntSize(1080, 2000)
    private val overlay = IntSize(600, 100)

    private fun place(
        field: Rect,
        imeInset: Int = 0,
        requested: FieldOverlayPlacement = FieldOverlayPlacement.Auto,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = FieldOverlayPositioner.place(field, container, overlay, imeInset, requested, direction)

    @Test
    fun anchorsBelowAFieldWithRoomUnderIt() {
        val layout = place(Rect(40f, 300f, 1040f, 400f))
        assertEquals(ResolvedOverlayPlacement.Below, layout.placement)
        assertEquals(408, layout.y)
        assertEquals(40, layout.x)
    }

    @Test
    fun flipsAboveAFieldNearTheBottom() {
        val layout = place(Rect(40f, 1850f, 1040f, 1950f))
        assertEquals(ResolvedOverlayPlacement.Above, layout.placement)
        assertEquals(1742, layout.y)
    }

    @Test
    fun promotesToAKeyboardBarWhenTheImeCoversTheSpaceBelow() {
        val layout = place(Rect(40f, 1000f, 1040f, 1100f), imeInset = 900)
        assertEquals(ResolvedOverlayPlacement.AboveIme, layout.placement)
        assertTrue("bar must sit above the IME", layout.y + overlay.height <= container.height - 900)
        assertEquals(FieldOverlayPositioner.MARGIN, layout.x)
        assertEquals(container.width - FieldOverlayPositioner.MARGIN * 2, layout.maxWidth)
        assertTrue(layout.fillWidth)
    }

    @Test
    fun keepsAnAnchoredPopoverWhenTheFieldIsSafelyHighWithTheImeOpen() {
        val layout = place(Rect(40f, 200f, 1040f, 300f), imeInset = 900)
        assertEquals(ResolvedOverlayPlacement.Below, layout.placement)
    }

    @Test
    fun neverPlacesTheOverlayBehindTheKeyboard() {
        val visibleBottom = container.height - 1200
        listOf(100f, 400f, 700f, 1200f, 1900f).forEach { top ->
            val layout = place(Rect(40f, top, 1040f, top + 100f), imeInset = 1200)
            assertTrue(
                "overlay at top=$top ran past the IME (${layout.placement})",
                layout.y + overlay.height <= visibleBottom,
            )
        }
    }

    @Test
    fun honoursAnExplicitAboveImeRequest() {
        val layout = place(Rect(40f, 200f, 1040f, 300f), requested = FieldOverlayPlacement.AboveIme)
        assertEquals(ResolvedOverlayPlacement.AboveIme, layout.placement)
    }

    @Test
    fun anchorsToTheTrailingEdgeInRightToLeftLayouts() {
        val field = Rect(40f, 300f, 900f, 400f)
        val ltr = place(field)
        val rtl = place(field, direction = LayoutDirection.Rtl)
        assertEquals(40, ltr.x)
        assertEquals(300, rtl.x)
    }

    @Test
    fun clampsInsideTheContainerOnBothEdges() {
        val offLeft = place(Rect(-200f, 300f, 400f, 400f))
        assertEquals(FieldOverlayPositioner.MARGIN, offLeft.x)
        val offRight = place(Rect(1000f, 300f, 1400f, 400f))
        assertTrue(offRight.x + overlay.width <= container.width - FieldOverlayPositioner.MARGIN)
    }

    @Test
    fun anchorsFromTheFieldBeforeTheBarHasBeenMeasured() {
        val unmeasured = FieldOverlayPositioner.place(
            field = Rect(240f, 300f, 1040f, 400f),
            container = container,
            overlay = IntSize.Zero,
        )
        assertEquals(240, unmeasured.x)
        assertTrue("an unmeasured bar must still be allowed real width", unmeasured.maxWidth > 0)
    }

    @Test
    fun reportsAFieldOffScreenAsNotVisible() {
        assertTrue(FieldOverlayPositioner.isFieldVisible(Rect(40f, 300f, 1040f, 400f), container))
        assertTrue(!FieldOverlayPositioner.isFieldVisible(Rect(40f, -400f, 1040f, -300f), container))
        assertTrue(!FieldOverlayPositioner.isFieldVisible(Rect(40f, 2400f, 1040f, 2500f), container))
        assertTrue(!FieldOverlayPositioner.isFieldVisible(Rect.Zero, container))
        assertTrue(!FieldOverlayPositioner.isFieldVisible(Rect(40f, 300f, 1040f, 400f), IntSize.Zero))
    }

    @Test
    fun treatsAFieldHiddenBehindTheImeAsNotVisible() {
        assertTrue(!FieldOverlayPositioner.isFieldVisible(Rect(40f, 1850f, 1040f, 1950f), container, imeInset = 1200))
        assertTrue(FieldOverlayPositioner.isFieldVisible(Rect(40f, 300f, 1040f, 400f), container, imeInset = 1200))
    }

    @Test
    fun survivesADegenerateContainer() {
        val layout = FieldOverlayPositioner.place(
            field = Rect(0f, 0f, 0f, 0f),
            container = IntSize(0, 0),
            overlay = overlay,
        )
        assertTrue(layout.x >= 0 && layout.y >= 0 && layout.maxWidth >= 0)
    }
}
