package com.kienhoang.dualsubreplay.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.data.ExoCourse
import com.kienhoang.dualsubreplay.data.ExoOverlay
import com.kienhoang.dualsubreplay.data.ExoShape
import kotlin.math.hypot

/*
 * The drawn echo picture of the ExoKG lessons: a black ultrasound sector, the view's chambers as dark
 * cavities in grey myocardium, valves as bright lines, and for a defect the hole (gold dashed) and the
 * shunt jets moving in true colour Doppler colours: red towards the probe at the sector's apex, blue away.
 */

private const val PICTURE_W = 400f
private const val PICTURE_H = 330f
private const val APEX_X = 200f
private const val APEX_Y = 12f
private const val SECTOR = "M200 12 L22 268 A305 305 0 0 0 378 268 Z"
private const val SPECKLES = 420
private const val SPECKLE_SEED = 7L
private const val LCG_MULTIPLIER = 16807L
private const val LCG_MODULUS = 2147483647L
private const val JET_PERIOD_MS = 1000
private const val JET_DASH = 6f
private const val JET_GAP = 7f
private const val JET_WIDTH = 7f
private const val HEAD_LENGTH = 16f
private const val HEAD_HALF = 9f
private const val VALVE_WIDTH = 3f
private const val GOLD_WIDTH = 3f

private val SCAN_BLACK = Color(0xFF050607)
private val SECTOR_FILL = Color(0xFF0B0C0E)
private val SECTOR_EDGE = Color(0xFF2A2F36)
private val CAVITY = Color(0xFF030303)
private val VALVE = Color(0xFFD9DDE2)
private val SEPTUM_MARK = Color(0xFF9AA1A9)
private val GOLD = Color(0xFFC9A227)
internal val DOPPLER_RED = Color(0xFFE5484D)
internal val DOPPLER_BLUE = Color(0xFF3B82F6)
private val TISSUE_LIGHT = Color(0xFF9AA0A7)
private val TISSUE_DARK = Color(0xFF4B5056)
private val LABEL = Color(0xFFF2F4F6)
private val LABEL_SMALL = Color(0xFFC9CED4)

/** A label in picture space. */
private data class ScanLabel(
    val text: String,
    val x: Float,
    val y: Float,
    val small: Boolean,
)

/** The picture split into layers drawn in order, as in the web trainer. */
private class ScanLayers {
    val tissue = ArrayList<ExoShape>()
    val cavities = ArrayList<ExoShape>()
    val lines = ArrayList<DrawScope.(Float) -> Unit>()
    val extra = ArrayList<ExoShape>()
    val labels = ArrayList<ScanLabel>()
}

private fun labelOf(shape: ExoShape): ScanLabel? {
    val text = shape.text("lab") ?: return null
    val x = shape.numbers["lx"] ?: shape.n("x")
    val y = shape.numbers["ly"] ?: shape.n("y")
    return ScanLabel(text, x, y, small = false)
}

private fun smallLabel(shape: ExoShape) = ScanLabel(shape.text("txt").orEmpty(), shape.n("x"), shape.n("y"), small = true)

private fun valveLine(shape: ExoShape): DrawScope.(Float) -> Unit =
    { s ->
        drawLine(VALVE, Offset(shape.n("x1") * s, shape.n("y1") * s), Offset(shape.n("x2") * s, shape.n("y2") * s), VALVE_WIDTH * s)
    }

private fun aorticValveMark(shape: ExoShape): DrawScope.(Float) -> Unit =
    { s ->
        val c = Offset(shape.n("x") * s, shape.n("y") * s)
        val r = shape.n("r") * s
        listOf(Offset(0f, -r), Offset(-r * COS_30, r / 2), Offset(r * COS_30, r / 2)).forEach {
            drawLine(SEPTUM_MARK, c, c + it, 2f * s)
        }
    }

private const val COS_30 = 0.866f

private fun lightPath(shape: ExoShape): DrawScope.(Float) -> Unit =
    { s -> drawPath(scaledPath(shape.text("d").orEmpty(), s), VALVE, style = Stroke(VALVE_WIDTH * s)) }

/** Sorts the view's shapes, changed by the defect, into the layers. */
private fun buildLayers(
    shapes: List<ExoShape>,
    overlay: ExoOverlay?,
): ScanLayers {
    val layers = ScanLayers()
    for (original in shapes) {
        val change = original.text("id")?.let { overlay?.changes?.get(it) }
        if (change?.has("hide") == true) continue
        addViewShape(layers, original.with(change), overlay?.commonValve == true)
    }
    if (overlay != null) addOverlay(layers, overlay)
    return layers
}

private fun addViewShape(
    layers: ScanLayers,
    shape: ExoShape,
    commonValve: Boolean,
) {
    when {
        shape.kind == "l" -> {
            if (commonValve) return
            layers.lines.add(valveLine(shape))
            labelOf(shape)?.let { layers.labels += it }
        }
        shape.kind == "x" -> layers.labels += smallLabel(shape)
        shape.kind == "q" -> layers.lines.add(lightPath(shape))
        shape.kind == "m" -> layers.lines.add(aorticValveMark(shape))
        shape.has("top") -> layers.lines.add { s -> drawShape(shape, s, cavity = false) }
        shape.has("tis") -> {
            layers.tissue += shape
            labelOf(shape)?.let { layers.labels += it }
        }
        else -> {
            layers.cavities += shape
            labelOf(shape)?.let { layers.labels += it }
        }
    }
}

private fun addOverlay(
    layers: ScanLayers,
    overlay: ExoOverlay,
) {
    if (overlay.commonValve) {
        layers.lines.add { s -> drawLine(VALVE, Offset(108f * s, 200f * s), Offset(296f * s, 200f * s), VALVE_WIDTH * s) }
        layers.labels += ScanLabel("Umumiy AV klapan", 200f, 150f, small = true)
    }
    for (added in overlay.added) {
        when {
            added.kind == "x" -> layers.labels += smallLabel(added)
            added.kind == "l" -> layers.lines.add(valveLine(added))
            added.has("tis") -> layers.tissue += added
            else -> layers.extra += added
        }
        if (added.kind != "x") labelOf(added)?.let { layers.labels += it }
    }
}

private val pathCache = HashMap<String, Path>()

private fun scaledPath(
    d: String,
    s: Float,
): Path {
    val base = pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }
    return Path().apply {
        addPath(base)
        transform(Matrix().apply { scale(s, s) })
    }
}

private fun polyline(
    points: String,
    s: Float,
): Path {
    val coordinates =
        points
            .trim()
            .split(' ', ',')
            .filter { it.isNotEmpty() }
            .map { it.toFloat() * s }
    return Path().apply {
        coordinates.chunked(2).forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
    }
}

private fun tissueBrush(s: Float) = Brush.radialGradient(listOf(TISSUE_LIGHT, TISSUE_DARK), Offset(APEX_X * s, 140f * s), 230f * s)

/** One shape: an ellipse, circle, filled path or tube; gold shapes are outlined only. */
private fun DrawScope.drawShape(
    shape: ExoShape,
    s: Float,
    cavity: Boolean,
) {
    val gold = shape.has("gold")
    val goldStroke = Stroke(GOLD_WIDTH * s)
    val fill = if (cavity || shape.has("cav")) CAVITY else null
    when (shape.kind) {
        "e" -> drawEllipse(shape, s, fill, gold, goldStroke)
        "c" -> {
            val center = Offset(shape.n("x") * s, shape.n("y") * s)
            val radius = shape.n("r") * s
            if (fill != null) {
                drawCircle(fill, radius, center)
            } else if (!gold) {
                drawCircle(tissueBrush(s), radius, center)
            }
            if (gold) drawCircle(GOLD, radius, center, style = goldStroke)
        }
        "p" -> {
            val path = scaledPath(shape.text("d").orEmpty(), s)
            if (gold) drawPath(path, GOLD, style = Stroke((GOLD_WIDTH + 1f) * s)) else drawPath(path, fill ?: CAVITY)
        }
        "t" -> {
            val color = if (gold) GOLD else CAVITY
            drawPath(
                polyline(shape.text("pts").orEmpty(), s),
                color,
                style = Stroke(shape.n("w") * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

private fun DrawScope.drawEllipse(
    shape: ExoShape,
    s: Float,
    fill: Color?,
    gold: Boolean,
    goldStroke: Stroke,
) {
    val cx = shape.n("x") * s
    val cy = shape.n("y") * s
    val topLeft = Offset(cx - shape.n("rx") * s, cy - shape.n("ry") * s)
    val size = Size(2 * shape.n("rx") * s, 2 * shape.n("ry") * s)
    rotate(shape.n("r"), Offset(cx, cy)) {
        when {
            fill != null -> drawOval(fill, topLeft, size)
            !gold -> drawOval(tissueBrush(s), topLeft, size)
        }
        if (gold) drawOval(GOLD, topLeft, size, style = goldStroke)
    }
}

private fun DrawScope.drawGap(
    gap: ExoShape,
    s: Float,
) {
    val topLeft = Offset((gap.n("x") - gap.n("rx")) * s, (gap.n("y") - gap.n("ry")) * s)
    val size = Size(2 * gap.n("rx") * s, 2 * gap.n("ry") * s)
    drawOval(CAVITY, topLeft, size)
    drawOval(GOLD, topLeft, size, style = Stroke(2f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * s, 3f * s))))
}

/** True when the jet runs towards the probe at the apex: red in colour Doppler. */
internal fun jetTowardsProbe(jet: List<Float>): Boolean {
    val (x1, y1, x2, y2) = jet
    val bx = (x1 + x2) / 2 - APEX_X
    val by = (y1 + y2) / 2 - APEX_Y
    return ((x2 - x1) * bx + (y2 - y1) * by) / hypot(bx, by) < 0f
}

private fun DrawScope.drawJet(
    jet: List<Float>,
    s: Float,
    phase: Float,
) {
    val color = if (jetTowardsProbe(jet)) DOPPLER_RED else DOPPLER_BLUE
    val start = Offset(jet[0] * s, jet[1] * s)
    val end = Offset(jet[2] * s, jet[3] * s)
    val dash = PathEffect.dashPathEffect(floatArrayOf(JET_DASH * s, JET_GAP * s), -phase * (JET_DASH + JET_GAP) * 2 * s)
    drawLine(color, start, end, JET_WIDTH * s, cap = StrokeCap.Round, pathEffect = dash)
    val length = (end - start).getDistance().coerceAtLeast(1f)
    val dir = (end - start) / length
    val normal = Offset(-dir.y, dir.x)
    val tip = end + dir * (HEAD_HALF * s)
    val base = tip - dir * (HEAD_LENGTH * s)
    val head =
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(base.x + normal.x * HEAD_HALF * s, base.y + normal.y * HEAD_HALF * s)
            lineTo(base.x - normal.x * HEAD_HALF * s, base.y - normal.y * HEAD_HALF * s)
            close()
        }
    drawPath(head, color)
}

private fun speckles(): List<FloatArray> {
    var seed = SPECKLE_SEED
    val next = {
        seed = seed * LCG_MULTIPLIER % LCG_MODULUS
        seed.toFloat() / LCG_MODULUS
    }
    return List(SPECKLES) { floatArrayOf(20f + next() * 360f, 14f + next() * 300f, 1f + next() * 2.5f, next() * 0.18f) }
}

private fun DrawScope.drawLabels(
    labels: List<ScanLabel>,
    measurer: TextMeasurer,
    s: Float,
) {
    for (label in labels) {
        val style =
            TextStyle(
                color = if (label.small) LABEL_SMALL else LABEL,
                fontSize = ((if (label.small) 12f else 14f) * s / density).sp,
                fontWeight = if (label.small) FontWeight.Medium else FontWeight.SemiBold,
                shadow = Shadow(Color.Black, blurRadius = 6f),
            )
        val layout = measurer.measure(label.text, style)
        drawText(layout, topLeft = Offset(label.x * s - layout.size.width / 2f, label.y * s - layout.size.height / 2f))
    }
}

/** The echo picture of [viewId] (with [defectId]'s hole and jets when given). */
@Composable
internal fun ExoScan(
    course: ExoCourse,
    viewId: String,
    defectId: String?,
    modifier: Modifier = Modifier,
) {
    val view = course.views[viewId] ?: return
    val overlay = defectId?.let { course.defects[it] }
    val layers = remember(viewId, defectId) { buildLayers(view.shapes, overlay) }
    val dots = remember { speckles() }
    val measurer = rememberTextMeasurer()
    val transition = rememberInfiniteTransition(label = "jets")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(JET_PERIOD_MS, easing = LinearEasing)), label = "jet phase")
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(SCAN_BLACK).padding(8.dp)) {
        Text(view.name, color = LABEL, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(PICTURE_W / PICTURE_H).testTag("exo_scan")) {
            val s = size.width / PICTURE_W
            val sector = scaledPath(SECTOR, s)
            drawPath(sector, SECTOR_FILL)
            clipPath(sector) {
                val tissue = tissueBrush(s)
                layers.tissue.forEach { if (it.has("gold")) drawShape(it, s, cavity = false) else drawTissue(it, s, tissue) }
                layers.cavities.forEach { drawShape(it, s, cavity = true) }
                overlay?.gaps?.forEach { drawGap(it, s) }
                layers.lines.forEach { it(this, s) }
                dots.forEach { (x, y, w, a) -> drawRect(Color.White.copy(alpha = a), Offset(x * s, y * s), Size(w * s, 1.2f * s)) }
                layers.extra.forEach { drawShape(it, s, cavity = it.has("cav") || it.kind != "t") }
                overlay?.jets?.forEach { drawJet(it, s, phase) }
            }
            drawPath(sector, SECTOR_EDGE, style = Stroke(1f))
            drawLabels(layers.labels, measurer, s)
        }
        if (overlay != null) DopplerLegend()
    }
}

private fun DrawScope.drawTissue(
    shape: ExoShape,
    s: Float,
    brush: Brush,
) {
    if (shape.kind == "e") {
        val cx = shape.n("x") * s
        val cy = shape.n("y") * s
        rotate(shape.n("r"), Offset(cx, cy)) {
            drawOval(brush, Offset(cx - shape.n("rx") * s, cy - shape.n("ry") * s), Size(2 * shape.n("rx") * s, 2 * shape.n("ry") * s))
        }
    } else {
        drawShape(shape, s, cavity = false)
    }
}

@Composable
private fun DopplerLegend() {
    Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LegendDot(DOPPLER_RED, "datchik tomon")
        LegendDot(DOPPLER_BLUE, "datchikdan")
        LegendDot(GOLD, "nuqson")
    }
}

@Composable
private fun LegendDot(
    color: Color,
    text: String,
) {
    Row {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Text(text, color = LABEL_SMALL, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
    }
}
