package com.kindleidle.host.ui

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * An SVG `d` attribute turned into an [android.graphics.Path].
 *
 * Written out rather than pulled from androidx because the parser there is
 * marked restricted-to-library, and a scene picker is not worth an unstable
 * dependency. The scenes use M, L, H, V, C, Q, A and Z; the rest of the
 * grammar is here anyway, since leaving it out only moves the failure to
 * whenever somebody adds a scene using it.
 */
object SvgPath {

    fun parse(d: String): Path {
        val path = Path()
        val scanner = Scanner(d)

        var command = ' '
        // Current point, subpath start, and the reflected control points that
        // S and T need from whatever came before them.
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var lastCubicX = 0f
        var lastCubicY = 0f
        var lastQuadX = 0f
        var lastQuadY = 0f
        var prev = ' '

        while (true) {
            scanner.skipSeparators()
            if (scanner.atEnd()) break

            val ch = scanner.peek()
            if (ch.isLetter()) {
                command = ch
                scanner.advance()
            } else if (command == ' ') {
                // Numbers before any command: nothing sensible to do.
                break
            } else if (command == 'M') {
                // A repeated coordinate pair after a moveto is a lineto.
                command = 'L'
            } else if (command == 'm') {
                command = 'l'
            }

            val relative = command.isLowerCase()
            when (command.uppercaseChar()) {
                'M' -> {
                    val x = scanner.number() + if (relative) cx else 0f
                    val y = scanner.number() + if (relative) cy else 0f
                    path.moveTo(x, y)
                    cx = x; cy = y; startX = x; startY = y
                }
                'L' -> {
                    val x = scanner.number() + if (relative) cx else 0f
                    val y = scanner.number() + if (relative) cy else 0f
                    path.lineTo(x, y)
                    cx = x; cy = y
                }
                'H' -> {
                    val x = scanner.number() + if (relative) cx else 0f
                    path.lineTo(x, cy)
                    cx = x
                }
                'V' -> {
                    val y = scanner.number() + if (relative) cy else 0f
                    path.lineTo(cx, y)
                    cy = y
                }
                'C' -> {
                    val ox = if (relative) cx else 0f
                    val oy = if (relative) cy else 0f
                    val x1 = scanner.number() + ox; val y1 = scanner.number() + oy
                    val x2 = scanner.number() + ox; val y2 = scanner.number() + oy
                    val x = scanner.number() + ox; val y = scanner.number() + oy
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastCubicX = x2; lastCubicY = y2
                    cx = x; cy = y
                }
                'S' -> {
                    val ox = if (relative) cx else 0f
                    val oy = if (relative) cy else 0f
                    val reflect = prev.uppercaseChar() == 'C' || prev.uppercaseChar() == 'S'
                    val x1 = if (reflect) 2 * cx - lastCubicX else cx
                    val y1 = if (reflect) 2 * cy - lastCubicY else cy
                    val x2 = scanner.number() + ox; val y2 = scanner.number() + oy
                    val x = scanner.number() + ox; val y = scanner.number() + oy
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastCubicX = x2; lastCubicY = y2
                    cx = x; cy = y
                }
                'Q' -> {
                    val ox = if (relative) cx else 0f
                    val oy = if (relative) cy else 0f
                    val x1 = scanner.number() + ox; val y1 = scanner.number() + oy
                    val x = scanner.number() + ox; val y = scanner.number() + oy
                    path.quadTo(x1, y1, x, y)
                    lastQuadX = x1; lastQuadY = y1
                    cx = x; cy = y
                }
                'T' -> {
                    val ox = if (relative) cx else 0f
                    val oy = if (relative) cy else 0f
                    val reflect = prev.uppercaseChar() == 'Q' || prev.uppercaseChar() == 'T'
                    val x1 = if (reflect) 2 * cx - lastQuadX else cx
                    val y1 = if (reflect) 2 * cy - lastQuadY else cy
                    val x = scanner.number() + ox; val y = scanner.number() + oy
                    path.quadTo(x1, y1, x, y)
                    lastQuadX = x1; lastQuadY = y1
                    cx = x; cy = y
                }
                'A' -> {
                    val rx = scanner.number()
                    val ry = scanner.number()
                    val rotation = scanner.number()
                    val largeArc = scanner.flag()
                    val sweep = scanner.flag()
                    val x = scanner.number() + if (relative) cx else 0f
                    val y = scanner.number() + if (relative) cy else 0f
                    arc(path, cx, cy, rx, ry, rotation, largeArc, sweep, x, y)
                    cx = x; cy = y
                }
                'Z' -> {
                    path.close()
                    cx = startX; cy = startY
                }
                else -> return path // an unknown command: stop rather than guess
            }
            prev = command
        }

        return path
    }

    /* ---------------------------------------------------------------------
       elliptical arc -> cubics

       Endpoint to centre parameterisation, per the SVG specification's
       implementation notes (F.6.5), then one cubic per quarter turn. Going
       through Path.arcTo instead would mean building a rotated oval, which is
       more work than this for the same result.
    --------------------------------------------------------------------- */

    private fun arc(
        path: Path,
        x0: Float, y0: Float,
        rxIn: Float, ryIn: Float,
        rotationDeg: Float,
        largeArc: Boolean, sweep: Boolean,
        x: Float, y: Float
    ) {
        // The spec: an arc whose endpoints coincide is dropped entirely.
        if (x0 == x && y0 == y) return

        var rx = abs(rxIn.toDouble())
        var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) {
            path.lineTo(x, y)
            return
        }

        val phi = Math.toRadians(rotationDeg.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (x0 - x) / 2.0
        val dy2 = (y0 - y) / 2.0
        val x1 = cosPhi * dx2 + sinPhi * dy2
        val y1 = -sinPhi * dx2 + cosPhi * dy2

        var rxs = rx * rx
        var rys = ry * ry
        val x1s = x1 * x1
        val y1s = y1 * y1

        // Radii too small to reach: scale them up until they just do.
        val lambda = x1s / rxs + y1s / rys
        if (lambda > 1) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
            rxs = rx * rx
            rys = ry * ry
        }

        var sq = (rxs * rys - rxs * y1s - rys * x1s) / (rxs * y1s + rys * x1s)
        if (sq < 0) sq = 0.0
        var coef = sqrt(sq)
        if (largeArc == sweep) coef = -coef

        val cxp = coef * rx * y1 / ry
        val cyp = -coef * ry * x1 / rx
        val ccx = (x0 + x) / 2.0 + cosPhi * cxp - sinPhi * cyp
        val ccy = (y0 + y) / 2.0 + sinPhi * cxp + cosPhi * cyp

        val ux = (x1 - cxp) / rx
        val uy = (y1 - cyp) / ry
        val vx = (-x1 - cxp) / rx
        val vy = (-y1 - cyp) / ry

        val theta1 = angleBetween(1.0, 0.0, ux, uy)
        var sweepAngle = angleBetween(ux, uy, vx, vy)
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * Math.PI
        else if (sweep && sweepAngle < 0) sweepAngle += 2 * Math.PI

        val segments = ceil(abs(sweepAngle / (Math.PI / 2))).toInt().coerceAtLeast(1)
        val delta = sweepAngle / segments
        val t = 4.0 / 3.0 * tan(delta / 4)

        var theta = theta1
        var px = x0.toDouble()
        var py = y0.toDouble()

        for (i in 0 until segments) {
            val theta2 = theta + delta
            val cos1 = cos(theta); val sin1 = sin(theta)
            val cos2 = cos(theta2); val sin2 = sin(theta2)

            val ex = ccx + rx * cosPhi * cos2 - ry * sinPhi * sin2
            val ey = ccy + rx * sinPhi * cos2 + ry * cosPhi * sin2

            val d1x = -rx * cosPhi * sin1 - ry * sinPhi * cos1
            val d1y = -rx * sinPhi * sin1 + ry * cosPhi * cos1
            val d2x = -rx * cosPhi * sin2 - ry * sinPhi * cos2
            val d2y = -rx * sinPhi * sin2 + ry * cosPhi * cos2

            path.cubicTo(
                (px + t * d1x).toFloat(), (py + t * d1y).toFloat(),
                (ex - t * d2x).toFloat(), (ey - t * d2y).toFloat(),
                ex.toFloat(), ey.toFloat()
            )

            px = ex; py = ey; theta = theta2
        }
    }

    private fun angleBetween(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        if (len == 0.0) return 0.0
        val sign = if (ux * vy - uy * vx < 0) -1.0 else 1.0
        return sign * acos((dot / len).coerceIn(-1.0, 1.0))
    }

    /* ------------------------------------------------------------------ */

    /**
     * Numbers in path data run together -- `1-2` is two of them, and so is
     * `.5.5` -- so this scans them itself rather than splitting on anything.
     */
    private class Scanner(private val src: String) {
        private var i = 0

        fun atEnd() = i >= src.length
        fun peek() = src[i]
        fun advance() { i++ }

        fun skipSeparators() {
            while (i < src.length && (src[i] == ',' || src[i].isWhitespace())) i++
        }

        /** Arc flags are a single character, and may not be separated. */
        fun flag(): Boolean {
            skipSeparators()
            if (i >= src.length) return false
            val c = src[i]
            if (c == '0' || c == '1') {
                i++
                return c == '1'
            }
            return number() != 0f
        }

        fun number(): Float {
            skipSeparators()
            val start = i
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            while (i < src.length && src[i].isDigit()) i++
            if (i < src.length && src[i] == '.') {
                i++
                while (i < src.length && src[i].isDigit()) i++
            }
            if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                val mark = i
                i++
                if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                if (i < src.length && src[i].isDigit()) {
                    while (i < src.length && src[i].isDigit()) i++
                } else {
                    i = mark
                }
            }
            if (i == start) {
                // Nothing numeric here: step over it so the caller cannot spin.
                i++
                return 0f
            }
            return src.substring(start, i).toFloatOrNull() ?: 0f
        }
    }
}
