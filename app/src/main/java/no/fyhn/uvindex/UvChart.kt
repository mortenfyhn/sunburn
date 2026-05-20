package no.fyhn.uvindex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
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

private data class NowMarker(
    val dotX: Float,
    val dotY: Float,
    val layout: TextLayoutResult,
    val labelLeft: Float,
    val labelTop: Float,
    val rect: Rect,
)

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

    // Scrub: when the user touches/drags on the chart, override the marker
    // position; release snaps back to now. Prototype — testing whether users
    // want to inspect UV at arbitrary times of day.
    var scrubFrac by remember { mutableStateOf<Double?>(null) }
    val markerFrac = scrubFrac ?: nowFracHour

    Canvas(
        modifier = modifier.pointerInput(hours.size) {
            val leftPx = 32.dp.toPx()
            val rightPx = 8.dp.toPx()
            val lastIdx = (hours.size - 1).toDouble()
            awaitEachGesture {
                val down = awaitFirstDown()
                val plotW = (size.width - leftPx - rightPx).coerceAtLeast(1f)
                fun toFrac(x: Float): Double {
                    val clamped = x.coerceIn(leftPx, size.width - rightPx)
                    return ((clamped - leftPx) / plotW).toDouble().coerceIn(0.0, 1.0) * lastIdx
                }
                scrubFrac = toFrac(down.position.x)
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    if (change.changedToUp()) {
                        scrubFrac = null
                        break
                    }
                    scrubFrac = toFrac(change.position.x)
                    change.consume()
                }
            }
        }
    ) {
        if (hours.size < 2) return@Canvas

        val maxUv = hours.maxOf { it.uv }
        val yMax = max(3.0, ceil(maxUv))

        val leftPad = 32.dp.toPx()
        val rightPad = 8.dp.toPx()
        // Top padding has to fit the larger now-label above the curve's apex
        // when the now-dot is near the peak — taller than the peak label that
        // used to set this budget.
        val topPad = 44.dp.toPx()
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

        // Threshold crossings (rising and falling). Used to anchor the "2.0"
        // label, the time markers, and the now/threshold overlap test.
        val asc = (1 until hours.size).firstOrNull { i ->
            hours[i - 1].uv < SUNSCREEN_THRESHOLD && hours[i].uv >= SUNSCREEN_THRESHOLD
        }
        val desc = (hours.size - 1 downTo 1).firstOrNull { i ->
            hours[i - 1].uv >= SUNSCREEN_THRESHOLD && hours[i].uv < SUNSCREEN_THRESHOLD
        }
        fun crossingFrac(i: Int): Double {
            val prev = hours[i - 1].uv
            val curr = hours[i].uv
            return (i - 1) + (SUNSCREEN_THRESHOLD - prev) / (curr - prev)
        }
        val ascFrac: Double? = asc?.let { crossingFrac(it) }
        val descFrac: Double? = desc?.let { crossingFrac(it) }

        // Pre-compute the now-marker geometry (dot + label rectangle). The
        // rect is consulted below to position "2.0" on whichever side of the
        // orange band isn't sitting under the larger now-label.
        val nowMarker: NowMarker? =
            if (markerFrac in 0.0..lastIndex.toDouble()) {
                val nowUv = interpolatedUv(hours, markerFrac)
                val nowX = xAt(markerFrac)
                val nowY = yAt(nowUv)
                val layout = tm.measure(formatUvLabel(nowUv), nowStyle)
                val labelW = layout.size.width.toFloat()
                val labelH = layout.size.height.toFloat()
                val eps = 0.5
                val h0 = (markerFrac - eps).coerceAtLeast(0.0)
                val h1 = (markerFrac + eps).coerceAtMost(lastIndex.toDouble())
                val tdx = xAt(h1) - xAt(h0)
                val tdy = yAt(interpolatedUv(hours, h1)) - yAt(interpolatedUv(hours, h0))
                val tlen = kotlin.math.sqrt(tdx * tdx + tdy * tdy)
                val nx = tdy / tlen
                val ny = -tdx / tlen
                val gap = 8.dp.toPx()
                val halfProj = (labelW / 2f) * kotlin.math.abs(nx) +
                    (labelH / 2f) * kotlin.math.abs(ny)
                val d = gap + halfProj
                val centerX = nowX + nx * d
                val centerY = nowY + ny * d
                val labelLeft = (centerX - labelW / 2f)
                    .coerceIn(plotLeft, plotRight - labelW)
                // Tangent-normal placement only considers the curve at the
                // now-dot. On a flat-top "double peak" the nearby higher peak
                // pokes into the label's rectangle from outside that local
                // window — sample the curve under the label's X-span and lift
                // the label until it clears.
                var curveTopY = Float.POSITIVE_INFINITY
                val samples = 16
                for (s in 0..samples) {
                    val sx = labelLeft + labelW * s / samples.toFloat()
                    val sFrac = ((sx - plotLeft) / plotW).coerceIn(0f, 1f).toDouble() * lastIndex
                    val sy = yAt(interpolatedUv(hours, sFrac))
                    if (sy < curveTopY) curveTopY = sy
                }
                val labelTop = minOf(
                    centerY - labelH / 2f,
                    curveTopY - labelH - gap,
                )
                NowMarker(
                    dotX = nowX,
                    dotY = nowY,
                    layout = layout,
                    labelLeft = labelLeft,
                    labelTop = labelTop,
                    rect = Rect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH),
                )
            } else null

        // Threshold "2.0": labelled directly on the orange fill's edge so the
        // value touches the line it names. Default to the rising crossing
        // (left of where orange begins); if the now-label would overlap that
        // spot, jump to the falling crossing (right of where orange ends).
        // Fall back to the y-axis if neither inline spot works or the curve
        // never crosses 2 today. No "0" on the y-axis: the curve's flat
        // shoulders sit visibly at the bottom of the plot, so the label would
        // only restate what the shape shows.
        run {
            val layout = tm.measure(formatUvLabel(SUNSCREEN_THRESHOLD), axisStyle)
            val labelW = layout.size.width.toFloat()
            val labelH = layout.size.height.toFloat()
            val ly = thresholdY - labelH / 2f
            fun rectAt(lx: Float) = Rect(lx, ly, lx + labelW, ly + labelH)
            fun clearOfNow(r: Rect) = nowMarker?.rect?.overlaps(r) != true

            val ascPos: Float? = ascFrac?.let { xAt(it) - labelW - 6.dp.toPx() }
            val descPos: Float? = descFrac?.let { xAt(it) + 6.dp.toPx() }
            val lx = when {
                ascPos != null && clearOfNow(rectAt(ascPos)) -> ascPos
                descPos != null && clearOfNow(rectAt(descPos)) -> descPos
                ascPos == null && descPos == null ->
                    plotLeft - labelW - 10.dp.toPx()
                else -> null  // both inline spots collide; omit the label
            }
            if (lx != null) drawText(layout, topLeft = Offset(lx, ly))
        }

        // X-axis: label the first rising and last falling crossings of the
        // sunscreen threshold rather than generic hour ticks. Generic ticks
        // made the reader interpolate ("the crossing is somewhere between 06
        // and 12") to answer the question they actually had — when do I need
        // sunscreen, when can I stop. Direct labels answer it at source. If
        // the curve never crosses (winter, high latitude), fall back to noon
        // as a single anchor so the chart still has some time orientation.
        val crossingFracs = listOfNotNull(ascFrac, descFrac)
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
        // used to show the day's top value. Suppressed when its rect would
        // collide with the now-label; the now-label already reports a value
        // at roughly the same point. Rect overlap (not an hour-distance
        // heuristic) so flat-top curves with multiple equal-max hours, where
        // peakIdx pins left of the now-dot, still suppress correctly.
        val peakIdx = hours.indexOfFirst { it.uv == maxUv }
        if (peakIdx >= 0) {
            val peakX = xAt(peakIdx.toDouble())
            val peakY = yAt(maxUv)
            val layout = tm.measure(formatUvLabel(maxUv), peakStyle)
            val labelW = layout.size.width.toFloat()
            val labelH = layout.size.height.toFloat()
            val peakLeft = (peakX - labelW / 2f)
                .coerceIn(plotLeft, plotRight - labelW)
            val peakTop = peakY - labelH - 6.dp.toPx()
            val peakRect = Rect(peakLeft, peakTop, peakLeft + labelW, peakTop + labelH)
            if (nowMarker?.rect?.overlaps(peakRect) != true) {
                drawText(layout, topLeft = Offset(peakLeft, peakTop))
            }
        }

        // "Now" marker: solid dot plus the current value floating above —
        // mirrors the peak label's placement so both annotations read the
        // same way. Position is computed earlier so the threshold "2.0"
        // placement can avoid overlapping the same rectangle.
        if (nowMarker != null) {
            drawCircle(
                color = Ink,
                radius = 5.5.dp.toPx(),
                center = Offset(nowMarker.dotX, nowMarker.dotY),
            )
            drawText(
                nowMarker.layout,
                topLeft = Offset(nowMarker.labelLeft, nowMarker.labelTop),
            )
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
