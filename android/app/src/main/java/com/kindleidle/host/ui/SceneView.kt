package com.kindleidle.host.ui

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import com.kindleidle.host.core.Scenes
import kotlin.math.min

/**
 * A scene, drawn natively.
 *
 * The web pages get the scene as SVG; five WebViews on the picker, or one
 * animating on the idle screen, would be a browser's worth of machinery for
 * some paths. The generator flattens every layer to plain path data instead,
 * and this draws it.
 *
 * Colours are resolved by role, the same way the stylesheets do it: idle.js
 * paints in a fixed set of greys and the CSS maps them onto ink and paper, so
 * a scene follows the theme instead of staying black-on-white in the dark.
 */

private class Drawable(val shape: Scenes.Shape, val path: Path)

private class ParsedScene(
    val statics: List<Drawable>,
    val frames: List<List<Drawable>>
)

/**
 * Path parsing is not free and a scene is 20-40 of them per layer, so it
 * happens once per scene rather than on every frame tick or recomposition.
 */
@Composable
private fun rememberParsed(scene: Scenes.Scene): ParsedScene = remember(scene.id) {
    fun parse(shapes: List<Scenes.Shape>) =
        shapes.map { Drawable(it, SvgPath.parse(it.pathData)) }

    ParsedScene(
        statics = parse(scene.vectorStatics),
        frames = scene.vectorFrames.map { parse(it) }
    )
}

/**
 * Draws [scene] at overlay [frame]. Frames are additive -- each is painted
 * over the static layer, not instead of it.
 */
@Composable
fun SceneView(
    scene: Scenes.Scene,
    frame: Int,
    modifier: Modifier = Modifier
) {
    val parsed = rememberParsed(scene)
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface

    Canvas(modifier) {
        val scale = min(size.width / VIEW_W, size.height / VIEW_H)
        val dx = (size.width - VIEW_W * scale) / 2f
        val dy = (size.height - VIEW_H * scale) / 2f

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val saved = native.save()
            native.translate(dx, dy)
            native.scale(scale, scale)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val overlay = parsed.frames[frame.mod(parsed.frames.size)]

            for (drawable in parsed.statics) draw(native, paint, drawable, ink, paper)
            for (drawable in overlay) draw(native, paint, drawable, ink, paper)

            native.restoreToCount(saved)
        }
    }
}

/** The picker's tile: the static layer plus frame 0, standing still. */
@Composable
fun SceneThumb(scene: Scenes.Scene, modifier: Modifier = Modifier) =
    SceneView(scene, 0, modifier)

private fun draw(
    canvas: android.graphics.Canvas,
    paint: Paint,
    drawable: Drawable,
    ink: Color,
    paper: Color
) {
    val shape = drawable.shape

    shape.fill?.let { hex ->
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = roleColor(hex, ink, paper).toArgb()
        canvas.drawPath(drawable.path, paint)
    }

    shape.stroke?.let { hex ->
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = roleColor(hex, ink, paper).toArgb()
        paint.strokeWidth = shape.strokeWidth
        paint.strokeCap = when (shape.cap) {
            "round" -> Paint.Cap.ROUND
            "square" -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        paint.strokeJoin = when (shape.join) {
            "round" -> Paint.Join.ROUND
            "bevel" -> Paint.Join.BEVEL
            else -> Paint.Join.MITER
        }
        // A dash array needs at least two entries, and an odd count means
        // "repeat it twice" per the SVG spec.
        shape.dash?.let { dash ->
            val pattern = if (dash.size % 2 == 0) dash else dash + dash
            if (pattern.size >= 2 && pattern.any { it > 0f }) {
                paint.pathEffect = DashPathEffect(pattern, 0f)
            }
        }
        canvas.drawPath(drawable.path, paint)
    }
}

/**
 * idle.js draws in a fixed palette and the stylesheets give each colour a
 * role: black is ink, white is paper, the mid greys are "soft" and the light
 * ones "faint". Same mapping here.
 */
private fun roleColor(hex: String, ink: Color, paper: Color): Color = when (hex.lowercase()) {
    "#000", "#000000" -> ink
    "#fff", "#ffffff" -> paper
    "#666", "#888", "#999", "#666666", "#888888", "#999999" -> ink.copy(alpha = 0.55f)
    "#ccc", "#ddd", "#cccccc", "#dddddd" -> ink.copy(alpha = 0.25f)
    else -> ink
}

private const val VIEW_W = 420f
private const val VIEW_H = 300f
