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
import java.util.Locale

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
    val peakStyle = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    // The current value is the primary reading; size it larger than the peak.
    val nowStyle = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)

    Canvas(modifier = modifier) {
        if (hours.size < 2) return@Canvas

        val maxUv = hours.maxOf { it.uv }
        val yMax = max(3.0, ceil(maxUv))

        val leftPad = 32.dp.toPx()
        val rightPad = 8.dp.toPx()
        // Generous top padding to fit the floating peak label above the apex.
        val topPad = 28.dp.toPx()
        val bottomPad = 28.dp.toPx()

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

        val thresholdY = yAt(SUNSCREEN_THRESHOLD)

        // Y-axis "0": stays at the left edge — the flat-zero shoulders of the
        // curve sit right on it, so it's already touching the data it labels.
        run {
            val layout = tm.measure("0", axisStyle)
            drawText(
                layout,
                topLeft = Offset(
                    x = plotLeft - layout.size.width - 10.dp.toPx(),
                    y = yAt(0.0) - layout.size.height / 2f,
                ),
            )
        }

        // Threshold "2": labelled directly on the orange fill's lower edge,
        // just left of where the curve first crosses the threshold going up.
        // Direct labelling — the value touches the line it names. Fall back
        // to the y-axis if the curve never crosses 2 today (no orange fill,
        // no inline anchor, but the scale still wants its label).
        val asc = (1 until hours.size).firstOrNull { i ->
            hours[i - 1].uv < SUNSCREEN_THRESHOLD && hours[i].uv >= SUNSCREEN_THRESHOLD
        }
        run {
            val twoLabel = SUNSCREEN_THRESHOLD.toInt().toString()
            val layout = tm.measure(twoLabel, axisStyle)
            val (lx, ly) = if (asc != null) {
                val prev = hours[asc - 1].uv
                val curr = hours[asc].uv
                val t = (SUNSCREEN_THRESHOLD - prev) / (curr - prev)
                val crossingX = xAt((asc - 1) + t)
                (crossingX - layout.size.width - 6.dp.toPx()) to
                    (thresholdY - layout.size.height / 2f)
            } else {
                (plotLeft - layout.size.width - 10.dp.toPx()) to
                    (thresholdY - layout.size.height / 2f)
            }
            drawText(layout, topLeft = Offset(lx, ly))
        }

        // X-axis: label the first rising and last falling crossings of the
        // sunscreen threshold rather than generic hour ticks. Generic ticks
        // made the reader interpolate ("the crossing is somewhere between 06
        // and 12") to answer the question they actually had — when do I need
        // sunscreen, when can I stop. Direct labels answer it at source. If
        // the curve never crosses (winter, high latitude), fall back to noon
        // as a single anchor so the chart still has some time orientation.
        val desc = (hours.size - 1 downTo 1).firstOrNull { i ->
            hours[i - 1].uv >= SUNSCREEN_THRESHOLD && hours[i].uv < SUNSCREEN_THRESHOLD
        }
        val crossingFracs = listOfNotNull(asc, desc).map { i ->
            val prev = hours[i - 1].uv
            val curr = hours[i].uv
            (i - 1) + (SUNSCREEN_THRESHOLD - prev) / (curr - prev)
        }
        val xLabelFracs = when {
            crossingFracs.isNotEmpty() -> crossingFracs
            12 < hours.size -> listOf(12.0)
            else -> emptyList()
        }
        for (frac in xLabelFracs) {
            val baseIdx = frac.toInt().coerceIn(0, hours.size - 1)
            val baseTime = hours[baseIdx].localTime
            val totalMinutes = baseTime.hour * 60 + baseTime.minute +
                ((frac - baseIdx) * 60).toInt()
            // Round to nearest 10 minutes — the forecast is hourly and
            // interpolation precision wouldn't be honest at higher resolution.
            val rounded = ((totalMinutes + 5) / 10) * 10
            val label = "%02d:%02d".format((rounded / 60) % 24, rounded % 60)
            val x = xAt(frac)
            val layout = tm.measure(label, axisStyle)
            drawText(
                layout,
                topLeft = Offset(
                    x = x - layout.size.width / 2f,
                    y = plotBottom + 10.dp.toPx(),
                ),
            )
        }

        // Smooth curve through the points
        val pts = hours.mapIndexed { i, h -> Offset(xAt(i.toDouble()), yAt(h.uv)) }
        val curvePath = smoothCurvePath(pts)

        // Fill above threshold: build a curve→plotBottom polygon and clip the
        // band [plotTop, thresholdY] so only the above-threshold region remains.
        // The polygon's bottom edge sits at plotBottom — well below the clip
        // line — so the clip produces a clean cut. If the polygon's bottom edge
        // were at thresholdY (i.e. on the clip boundary), AA would leave a
        // faint partial-opacity row across the full chart width at uv = 2.
        val fillPath = Path().apply {
            addPath(curvePath)
            lineTo(pts.last().x, plotBottom)
            lineTo(pts.first().x, plotBottom)
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

        // Peak label floating above the apex — replaces the y-axis ticks that
        // used to show the day's top value. Suppressed when the now-dot is
        // sitting on the peak (within an hour), since the now-label already
        // reports the same value at the same point and the two would overlap.
        val peakIdx = hours.indexOfFirst { it.uv == maxUv }
        val nowVisible = nowFracHour in 0.0..lastIndex.toDouble()
        val nowAtPeak = nowVisible && kotlin.math.abs(nowFracHour - peakIdx) < 1.0
        if (peakIdx >= 0 && !nowAtPeak) {
            val peakX = xAt(peakIdx.toDouble())
            val peakY = yAt(maxUv)
            val peakText = formatUvLabel(maxUv)
            val layout = tm.measure(peakText, peakStyle)
            drawText(
                layout,
                topLeft = Offset(
                    x = (peakX - layout.size.width / 2f)
                        .coerceIn(plotLeft, plotRight - layout.size.width),
                    y = peakY - layout.size.height - 6.dp.toPx(),
                ),
            )
        }

        // "Now" marker: solid dot with a thin white halo (so it stays visible
        // on top of the curve stroke), plus the current value floating above
        // — mirrors the peak label's placement so both annotations read the
        // same way.
        if (nowFracHour in 0.0..lastIndex.toDouble()) {
            val nowUv = interpolatedUv(hours, nowFracHour)
            val nowX = xAt(nowFracHour)
            val nowY = yAt(nowUv)
            drawCircle(color = Color.White, radius = 6.5.dp.toPx(), center = Offset(nowX, nowY))
            drawCircle(color = Ink, radius = 4.5.dp.toPx(), center = Offset(nowX, nowY))

            val nowText = formatUvLabel(nowUv)
            val layout = tm.measure(nowText, nowStyle)
            val labelW = layout.size.width.toFloat()
            val labelH = layout.size.height.toFloat()

            // Place the label along the curve's upward normal at the dot.
            // On a steep rise, the normal points up-and-left — keeping the
            // label clear of the climbing curve instead of stacking it
            // directly on top, where the curve would saw through the digits.
            val eps = 0.5
            val h0 = (nowFracHour - eps).coerceAtLeast(0.0)
            val h1 = (nowFracHour + eps).coerceAtMost(lastIndex.toDouble())
            val tdx = xAt(h1) - xAt(h0)
            val tdy = yAt(interpolatedUv(hours, h1)) - yAt(interpolatedUv(hours, h0))
            val tlen = kotlin.math.sqrt(tdx * tdx + tdy * tdy)
            // Perpendicular to (tdx, tdy) with negative y (upward on screen).
            val nx = tdy / tlen
            val ny = -tdx / tlen

            val gap = 8.dp.toPx()
            // Push the centre out far enough that the nearest edge of the
            // (axis-aligned) label sits `gap` from the dot, regardless of
            // normal direction.
            val halfProj = (labelW / 2f) * kotlin.math.abs(nx) +
                (labelH / 2f) * kotlin.math.abs(ny)
            val d = gap + halfProj
            val centerX = nowX + nx * d
            val centerY = nowY + ny * d

            val labelLeft = (centerX - labelW / 2f)
                .coerceIn(plotLeft, plotRight - labelW)
            val labelTop = centerY - labelH / 2f

            drawText(layout, topLeft = Offset(labelLeft, labelTop))
        }
    }
}

private fun formatUvLabel(uv: Double): String = "%.1f".format(Locale.ROOT, uv)

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
