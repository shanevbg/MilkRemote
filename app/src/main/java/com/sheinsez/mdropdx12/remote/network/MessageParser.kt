package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.DisplayInfo
import com.sheinsez.mdropdx12.remote.data.model.MirrorState
import com.sheinsez.mdropdx12.remote.data.model.VisualizerState

object MessageParser {
    fun parsePreset(message: String): String? {
        if (!message.startsWith("PRESET=")) return null
        val path = message.removePrefix("PRESET=")
        return path.substringAfterLast("\\").substringAfterLast("/").substringBeforeLast(".")
    }

    fun parseTrack(message: String): Triple<String, String, String>? {
        if (!message.startsWith("TRACK|")) return null
        val params = parseKeyValue(message.removePrefix("TRACK|"))
        return Triple(
            params["artist"] ?: "",
            params["title"] ?: "",
            params["album"] ?: "",
        )
    }

    fun parseOpacity(message: String): Int? {
        if (!message.startsWith("OPACITY=")) return null
        return message.removePrefix("OPACITY=").toIntOrNull()
    }

    fun parseWave(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("WAVE|")) return null
        val params = parseKeyValue(message.removePrefix("WAVE|"))
        return current.copy(
            waveMode = params["MODE"]?.toIntOrNull() ?: current.waveMode,
            waveAlpha = params["ALPHA"]?.toFloatOrNull() ?: current.waveAlpha,
            waveScale = params["SCALE"]?.toFloatOrNull() ?: current.waveScale,
            waveZoom = params["ZOOM"]?.toFloatOrNull() ?: current.waveZoom,
            waveWarp = params["WARP"]?.toFloatOrNull() ?: current.waveWarp,
            waveRotation = params["ROTATION"]?.toFloatOrNull() ?: current.waveRotation,
            waveDecay = params["DECAY"]?.toFloatOrNull() ?: current.waveDecay,
            waveBrighten = params["BRIGHTEN"]?.let { it == "1" } ?: current.waveBrighten,
            waveDarken = params["DARKEN"]?.let { it == "1" } ?: current.waveDarken,
            waveSolarize = params["SOLARIZE"]?.let { it == "1" } ?: current.waveSolarize,
            waveInvert = params["INVERT"]?.let { it == "1" } ?: current.waveInvert,
            waveAdditive = params["ADDITIVE"]?.let { it == "1" } ?: current.waveAdditive,
            waveThick = params["THICK"]?.let { it == "1" } ?: current.waveThick,
        )
    }

    fun parseSettings(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("SETTINGS|")) return null
        val params = parseKeyValue(message.removePrefix("SETTINGS|"))
        return current.copy(
            colorHue = params["HUE"]?.toFloatOrNull() ?: current.colorHue,
            fftAttack = params["FFTATTACK"]?.toFloatOrNull() ?: current.fftAttack,
            fftDecay = params["FFTDECAY"]?.toFloatOrNull() ?: current.fftDecay,
            varQuality = params["QUALITY"]?.toFloatOrNull() ?: current.varQuality,
        )
    }

    fun parseMirrors(message: String): MirrorState? {
        if (!message.startsWith("MIRRORS|")) return null
        val sections = message.removePrefix("MIRRORS|").split("|")
        var active = false
        var renderDisplay = ""
        var renderOpacity = 1f
        var renderFs = false
        var renderClickThru = false
        val monitors = mutableListOf<DisplayInfo>()

        for (section in sections) {
            when {
                section.startsWith("active=") -> {
                    active = section.removePrefix("active=") != "0"
                }
                section.startsWith("render_on=") -> {
                    val parts = parseCommaSeparated(section.removePrefix("render_on="))
                    renderDisplay = parts.firstOrNull() ?: ""
                    renderOpacity = parts.findValue("opacity")?.toFloatOrNull() ?: 1f
                    renderFs = parts.findValue("fs") == "1"
                    renderClickThru = parts.findValue("clickthru") == "1"
                }
                section.startsWith("mon") -> {
                    val idx = section.substringBefore("=").removePrefix("mon").toIntOrNull() ?: continue
                    val parts = parseCommaSeparated(section.substringAfter("="))
                    val deviceName = parts.firstOrNull() ?: ""
                    monitors.add(DisplayInfo(
                        index = idx,
                        deviceName = deviceName,
                        enabled = parts.findValue("enabled") == "1",
                        opacity = parts.findValue("opacity")?.toIntOrNull() ?: 100,
                        clickThrough = parts.findValue("clickthru") == "1",
                        displayRect = parseRect(parts.findValue("display") ?: ""),
                        visible = parts.findValue("visible") == "1",
                    ))
                }
            }
        }

        return MirrorState(active, renderDisplay, renderOpacity, renderFs, renderClickThru, monitors)
    }

    private fun parseKeyValue(s: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in s.split("|")) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                map[part.substring(0, eq)] = part.substring(eq + 1)
            }
        }
        return map
    }

    /**
     * Smart comma splitter that respects parenthesized groups.
     * e.g., "\\.\DISPLAY1,renderwin=(0,0)-(1920,1080) 1920x1080,opacity=0.95"
     * splits to: ["\\.\DISPLAY1", "renderwin=(0,0)-(1920,1080) 1920x1080", "opacity=0.95"]
     */
    private fun parseCommaSeparated(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    result.add(s.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(s.substring(start))
        return result
    }

    private fun List<String>.findValue(key: String): String? {
        return firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
    }

    private fun parseRect(s: String): DisplayInfo.Rect {
        val match = Regex("""\((-?\d+),(-?\d+)\)-\((-?\d+),(-?\d+)\)\s+(\d+)x(\d+)""").find(s)
        return if (match != null) {
            val (x, y, _, _, w, h) = match.destructured
            DisplayInfo.Rect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
        } else {
            DisplayInfo.Rect(0, 0, 0, 0)
        }
    }
}
