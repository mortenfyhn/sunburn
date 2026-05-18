package no.fyhn.uvindex

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max

private val Ink = Color(0xFF111111)
private val Sunburn = Color(0xFFEFA572)  // warm orange for UV > threshold

const val SUNSCREEN_THRESHOLD = 2.0

@Composable
fun UvChart(
    hours: List<HourUv>,
    nowFracHour: Double,
    modifier: Modifier = Modifier,
) {
    val tm: TextMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)

    Canvas(modifier = modifier) {
        if (hours.size < 2) return@Canvas

        val maxUv = hours.maxOf { it.uv }
        // Top out at the next integer at-or-above the day's peak (min 3 so the chart
        // doesn't get absurdly squashed when UV is near zero).
        val yMax = max(3.0, ceil(maxUv))

        val leftPad = 40.dp.toPx()
        val rightPad = 8.dp.toPx()
        val topPad = 8.dp.toPx()
        val bottomPad = 32.dp.toPx()

        val plotLeft = leftPad
        val plotRight = size.width - rightPad
        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        val lastIndex = hours.size - 1

        fun xAt(hour: Double): Float =
            plotLeft + (hour / lastIndex.toDouble()).toFloat() * plotW

        fun yAt(uv: Double): Float {
            val frac = (uv / yMax).coerceIn(0.0, 1.0)
            return plotBottom - frac.toFloat() * plotH
        }

        // Y-axis: 0, 2, 4, 6, 8 (clipped to yMax)
        val yTicks = listOf(0, 2, 4, 6, 8).filter { it <= yMax }
        for (v in yTicks) {
            val y = yAt(v.toDouble())
            val layout = tm.measure(v.toString(), axisStyle)
            drawText(
                layout,
                topLeft = Offset(
                    x = plotLeft - layout.size.width - 12.dp.toPx(),
                    y = y - layout.size.height / 2f,
                ),
            )
        }

        // X-axis: 5 evenly-spaced ticks. The right edge is labelled "24" to read
        // as "end of day" — the last data point is hour 23, but the conventional
        // 0–24 framing communicates "this is one full day" more clearly.
        val xTickIdx = listOf(0, 6, 12, 18, lastIndex).distinct().filter { it < hours.size }
        for (i in xTickIdx) {
            val x = xAt(i.toDouble())
            val label = if (i == lastIndex) "24" else "%02d".format(hours[i].localTime.hour)
            val layout = tm.measure(label, axisStyle)
            drawText(
                layout,
                topLeft = Offset(
                    x = x - layout.size.width / 2f,
                    y = plotBottom + 12.dp.toPx(),
                ),
            )
        }

        // Smooth curve through 24 points
        val pts = hours.mapIndexed { i, h -> Offset(xAt(i.toDouble()), yAt(h.uv)) }
        val curvePath = smoothCurvePath(pts)

        // Fill: solid orange between the smooth curve and the UV=2 threshold line,
        // clipped so only the portion above the threshold shows.
        val thresholdY = yAt(SUNSCREEN_THRESHOLD)
        val fillPath = Path().apply {
            addPath(curvePath)
            lineTo(pts.last().x, thresholdY)
            lineTo(pts.first().x, thresholdY)
            close()
        }
        clipRect(
            left = plotLeft,
            top = plotTop,
            right = plotRight,
            bottom = thresholdY,
        ) {
            drawPath(fillPath, color = Sunburn)
        }

        // Curve
        drawPath(curvePath, color = Ink, style = Stroke(width = 2.5.dp.toPx()))

        // "Now" marker dot
        if (nowFracHour in 0.0..lastIndex.toDouble()) {
            val nowX = xAt(nowFracHour)
            val nowY = yAt(interpolatedUv(hours, nowFracHour))
            drawCircle(color = Ink, radius = 7.dp.toPx(), center = Offset(nowX, nowY))
            drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(nowX, nowY))
        }
    }
}

fun interpolatedUv(hours: List<HourUv>, fracHour: Double): Double {
    if (hours.isEmpty()) return 0.0
    val i = fracHour.toInt().coerceIn(0, hours.size - 1)
    val j = (i + 1).coerceAtMost(hours.size - 1)
    val t = (fracHour - i).coerceIn(0.0, 1.0)
    return hours[i].uv + (hours[j].uv - hours[i].uv) * t
}

/**
 * Monotone cubic Hermite spline (Fritsch–Carlson). Unlike Catmull-Rom, this
 * never overshoots — a sequence of equal Y values stays flat, and ascending
 * values stay monotonically ascending. That fixes the "dip below zero" before
 * dawn that Catmull-Rom produces on flat-then-rising sections.
 *
 * Output: a Path made of cubic Bezier segments through every input point.
 */
private fun smoothCurvePath(points: List<Offset>): Path {
    val path = Path()
    val n = points.size
    if (n == 0) return path
    path.moveTo(points[0].x, points[0].y)
    if (n == 1) return path

    val xs = FloatArray(n) { points[it].x }
    val ys = FloatArray(n) { points[it].y }
    val dx = FloatArray(n - 1) { xs[it + 1] - xs[it] }
    val dy = FloatArray(n - 1) { ys[it + 1] - ys[it] }
    val slope = FloatArray(n - 1) { dy[it] / dx[it] }

    // Initial tangents: weighted average of neighbouring slopes (set to slope
    // at the boundaries).
    val tangent = FloatArray(n)
    tangent[0] = slope[0]
    tangent[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) tangent[i] = (slope[i - 1] + slope[i]) / 2f

    // Fritsch-Carlson monotonicity correction.
    for (i in 0 until n - 1) {
        if (slope[i] == 0f) {
            tangent[i] = 0f
            tangent[i + 1] = 0f
        } else {
            val a = tangent[i] / slope[i]
            val b = tangent[i + 1] / slope[i]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / kotlin.math.sqrt(h)
                tangent[i] = t * a * slope[i]
                tangent[i + 1] = t * b * slope[i]
            }
        }
    }

    // Each segment as a cubic Bezier: control points lie 1/3 along the tangent.
    for (i in 0 until n - 1) {
        val h = dx[i]
        val cp1x = xs[i] + h / 3f
        val cp1y = ys[i] + tangent[i] * h / 3f
        val cp2x = xs[i + 1] - h / 3f
        val cp2y = ys[i + 1] - tangent[i + 1] * h / 3f
        path.cubicTo(cp1x, cp1y, cp2x, cp2y, xs[i + 1], ys[i + 1])
    }
    return path
}
