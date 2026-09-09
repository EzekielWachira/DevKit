package io.devkit.chartdemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The sample's own icon set, drawn rather than imported.
 *
 * ### Why these are drawn here
 *
 * The workspace deliberately does not depend on `material-icons`, and copying a
 * real company's logo into a repository to demonstrate a chart would be using
 * somebody else's trademark as filler. So the demo draws five unmistakably
 * generic marks and a lettered badge, which is enough to show that *arbitrary
 * Compose content* goes inside a region — which is the capability under
 * demonstration. Nothing about ChartKit requires them; a consumer passes their
 * own `Icon`, `Image` or `Painter`.
 */
enum class DemoGlyph { Circle, Square, Triangle, Ring, Diamond, Cross }

/** One drawn mark, sized in dp like any icon. */
@Composable
fun DemoGlyphIcon(
    glyph: DemoGlyph,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    Canvas(modifier.size(size)) {
        val side = this.size.minDimension
        val centre = Offset(this.size.width / 2, this.size.height / 2)
        when (glyph) {
            DemoGlyph.Circle -> drawCircle(color, radius = side / 2)
            DemoGlyph.Square -> drawRect(
                color = color,
                topLeft = Offset(centre.x - side / 2, centre.y - side / 2),
                size = androidx.compose.ui.geometry.Size(side, side),
            )

            DemoGlyph.Ring -> drawCircle(
                color = color,
                radius = side / 2 - side * 0.12f,
                style = Stroke(width = side * 0.22f),
            )

            DemoGlyph.Triangle -> drawPath(
                path = Path().apply {
                    moveTo(centre.x, centre.y - side / 2)
                    lineTo(centre.x + side / 2, centre.y + side / 2)
                    lineTo(centre.x - side / 2, centre.y + side / 2)
                    close()
                },
                color = color,
            )

            DemoGlyph.Diamond -> drawPath(
                path = Path().apply {
                    moveTo(centre.x, centre.y - side / 2)
                    lineTo(centre.x + side / 2, centre.y)
                    lineTo(centre.x, centre.y + side / 2)
                    lineTo(centre.x - side / 2, centre.y)
                    close()
                },
                color = color,
            )

            DemoGlyph.Cross -> {
                val arm = side * 0.28f
                drawRect(
                    color = color,
                    topLeft = Offset(centre.x - arm / 2, centre.y - side / 2),
                    size = androidx.compose.ui.geometry.Size(arm, side),
                )
                drawRect(
                    color = color,
                    topLeft = Offset(centre.x - side / 2, centre.y - arm / 2),
                    size = androidx.compose.ui.geometry.Size(side, arm),
                )
            }
        }
    }
}

/**
 * A lettered badge, standing in for a company logo.
 *
 * Deliberately not anybody's actual mark. The point of the reference's logo
 * example is that arbitrary images can be placed inside a region; a letter in a
 * coloured square demonstrates exactly that without borrowing a trademark.
 */
@Composable
fun DemoLogo(
    letter: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawRoundRect(
                color = color,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    this.size.minDimension * 0.28f,
                ),
            )
        }
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}
