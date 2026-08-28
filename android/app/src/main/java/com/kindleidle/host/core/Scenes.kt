package com.kindleidle.host.core

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The idle scenes, ported from server/idle.js by not porting it.
 *
 * The geometry lives in `assets/scenes.json`, generated from idle.js itself
 * by android/tools/gen-scenes.js, which checks that reassembling the pieces
 * reproduces `idle.renderScenes()` character for character. So the markup the
 * Kindle gets from this host is the markup it gets from the Node server, and
 * a change to a scene is made in one place.
 *
 * Each scene arrives in two forms: the SVG strings for the web pages, and a
 * flattened list of paths per layer for the native screens, which draw with
 * Compose.
 */
class Scenes private constructor(
    val frameCount: Int,
    val frameMs: Int,
    val defaultScene: String,
    private val viewBox: String,
    val list: List<Scene>
) {

    class Shape(
        val pathData: String,
        val fill: String?,
        val stroke: String?,
        val strokeWidth: Float,
        val cap: String?,
        val join: String?,
        val dash: FloatArray?
    )

    class Scene(
        val id: String,
        val name: String,
        val statics: String,
        val frames: List<String>,
        /** The layer drawn once, natively. */
        val vectorStatics: List<Shape>,
        /** The overlay frames, natively. Additive: each draws over the statics. */
        val vectorFrames: List<List<Shape>>
    ) {
        /**
         * What to draw for frame [i]. Frames are additive, so this is the
         * static layer with one overlay on top -- the same composition the
         * stylesheets make by showing one `<g class="fr">` at a time.
         */
        fun shapesAt(i: Int): List<Shape> =
            vectorStatics + vectorFrames[i.mod(vectorFrames.size)]

        /** Static layer plus frame 0 -- the same shapes idle.renderThumb() shows. */
        val thumb: List<Shape> get() = shapesAt(0)
    }

    fun isScene(id: String?): Boolean = id != null && list.any { it.id == id }

    private fun svgOpen(attrs: String) =
        "<svg $attrs viewBox=\"$viewBox\" preserveAspectRatio=\"xMidYMid meet\" aria-hidden=\"true\">"

    /**
     * Every scene inlined, only the chosen one displayed. Switching is then a
     * class swap over markup the device already holds, which costs one small
     * repaint instead of a page load.
     */
    fun renderScenes(active: String?): String {
        val chosen = if (isScene(active)) active else defaultScene
        return list.joinToString("") { s ->
            val frames = s.frames.withIndex().joinToString("") { (i, markup) ->
                "<g id=\"fr-${s.id}-$i\" class=\"fr${if (i == 0) " on" else ""}\">$markup</g>"
            }
            svgOpen("id=\"sc-${s.id}\" class=\"sc${if (s.id == chosen) " on" else ""}\"") +
                "<g>${s.statics}</g>$frames</svg>"
        }
    }

    /** The web picker's thumbnails: static layer plus the first frame. */
    fun renderThumb(id: String?): String {
        val s = list.firstOrNull { it.id == id } ?: list.first { it.id == defaultScene }
        return svgOpen("class=\"th\"") + "<g>${s.statics}</g><g>${s.frames[0]}</g></svg>"
    }

    companion object {

        private fun shapes(arr: JSONArray): List<Shape> {
            val out = ArrayList<Shape>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                var dash: FloatArray? = null
                s.optJSONArray("dash")?.let { d ->
                    dash = FloatArray(d.length()) { k -> d.getDouble(k).toFloat() }
                }
                out.add(
                    Shape(
                        pathData = s.getString("d"),
                        fill = s.optString("fill").ifEmpty { null },
                        stroke = s.optString("stroke").ifEmpty { null },
                        strokeWidth = s.optDouble("width", 1.0).toFloat(),
                        cap = s.optString("cap").ifEmpty { null },
                        join = s.optString("join").ifEmpty { null },
                        dash = dash
                    )
                )
            }
            return out
        }

        fun load(assets: AssetManager): Scenes {
            val text = assets.open("scenes.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val root = JSONObject(text)
            val arr = root.getJSONArray("scenes")

            val scenes = ArrayList<Scene>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)

                val frameArr = o.getJSONArray("frames")
                val frames = ArrayList<String>(frameArr.length())
                for (f in 0 until frameArr.length()) frames.add(frameArr.getString(f))

                val vector = o.getJSONObject("vector")
                val vectorFrameArr = vector.getJSONArray("frames")
                val vectorFrames = ArrayList<List<Shape>>(vectorFrameArr.length())
                for (f in 0 until vectorFrameArr.length()) {
                    vectorFrames.add(shapes(vectorFrameArr.getJSONArray(f)))
                }

                scenes.add(
                    Scene(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        statics = o.getString("statics"),
                        frames = frames,
                        vectorStatics = shapes(vector.getJSONArray("statics")),
                        vectorFrames = vectorFrames
                    )
                )
            }

            return Scenes(
                frameCount = root.getInt("frameCount"),
                frameMs = root.getInt("frameMs"),
                defaultScene = root.getString("defaultScene"),
                viewBox = root.getString("viewBox"),
                list = scenes
            )
        }
    }
}
