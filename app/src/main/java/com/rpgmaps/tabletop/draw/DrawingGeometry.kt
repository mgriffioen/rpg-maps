package com.rpgmaps.tabletop.draw

import com.rpgmaps.tabletop.display.protocol.ARROW_HEAD_HALF_ANGLE_DEG
import com.rpgmaps.tabletop.display.protocol.ARROW_HEAD_LENGTH_FACTOR
import com.rpgmaps.tabletop.display.protocol.ARROW_HEAD_MAX_FRACTION
import com.rpgmaps.tabletop.display.protocol.DrawKind
import com.rpgmaps.tabletop.display.protocol.Drawing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The two barb ends of an arrowhead, as `x0, y0, x1, y1`, for an arrow from
 * ([tailX], [tailY]) to ([tipX], [tipY]). Null for a zero-length arrow, which
 * has no direction to point in.
 *
 * `arrowHead` in receiver/index.html does the same sum, and must: the DM and
 * the table should see the same arrow.
 */
fun arrowHead(tailX: Float, tailY: Float, tipX: Float, tipY: Float, width: Float): FloatArray? {
    val length = hypot(tipX - tailX, tipY - tailY)
    if (length < 0.001f) return null
    val head = minOf(width * ARROW_HEAD_LENGTH_FACTOR, length * ARROW_HEAD_MAX_FRACTION)
    // Pointing back along the shaft, from the tip towards the tail.
    val back = atan2(tailY - tipY, tailX - tipX)
    val spread = ARROW_HEAD_HALF_ANGLE_DEG * PI.toFloat() / 180f
    return floatArrayOf(
        tipX + head * cos(back + spread), tipY + head * sin(back + spread),
        tipX + head * cos(back - spread), tipY + head * sin(back - spread),
    )
}

/**
 * True when ([x], [y]) lies within [tolerance] map pixels of the drawn line --
 * the edge, not the inside, since nothing here is filled. Used by the eraser.
 */
fun Drawing.isHit(x: Float, y: Float, tolerance: Float): Boolean {
    val reach = tolerance + width / 2f
    return outline().any { line -> polylineDistance(line, x, y) <= reach }
}

/**
 * The drawing as polylines of `x, y` pairs. Ovals are approximated closely
 * enough for hit testing; rendering uses the real curve.
 */
internal fun Drawing.outline(): List<FloatArray> {
    val p = pts
    return when (kind) {
        DrawKind.PEN -> if (p.size >= 2) listOf(p.toFloatArray()) else emptyList()
        DrawKind.RECT -> {
            if (p.size < 4) return emptyList()
            listOf(floatArrayOf(p[0], p[1], p[2], p[1], p[2], p[3], p[0], p[3], p[0], p[1]))
        }
        DrawKind.OVAL -> {
            if (p.size < 4) return emptyList()
            val cx = (p[0] + p[2]) / 2f
            val cy = (p[1] + p[3]) / 2f
            val rx = abs(p[2] - p[0]) / 2f
            val ry = abs(p[3] - p[1]) / 2f
            val line = FloatArray((OVAL_SEGMENTS + 1) * 2)
            for (i in 0..OVAL_SEGMENTS) {
                val a = 2f * PI.toFloat() * i / OVAL_SEGMENTS
                line[i * 2] = cx + rx * cos(a)
                line[i * 2 + 1] = cy + ry * sin(a)
            }
            listOf(line)
        }
        DrawKind.ARROW -> {
            if (p.size < 4) return emptyList()
            val shaft = floatArrayOf(p[0], p[1], p[2], p[3])
            val head = arrowHead(p[0], p[1], p[2], p[3], width) ?: return listOf(shaft)
            listOf(shaft, floatArrayOf(head[0], head[1], p[2], p[3], head[2], head[3]))
        }
    }
}

/** Distance from a point to the nearest part of a polyline; a lone point counts. */
internal fun polylineDistance(line: FloatArray, x: Float, y: Float): Float {
    if (line.size < 2) return Float.MAX_VALUE
    if (line.size < 4) return hypot(x - line[0], y - line[1])
    var best = Float.MAX_VALUE
    var i = 0
    while (i + 3 < line.size) {
        best = minOf(best, segmentDistance(x, y, line[i], line[i + 1], line[i + 2], line[i + 3]))
        i += 2
    }
    return best
}

private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val lengthSq = dx * dx + dy * dy
    if (lengthSq == 0f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

private const val OVAL_SEGMENTS = 64
