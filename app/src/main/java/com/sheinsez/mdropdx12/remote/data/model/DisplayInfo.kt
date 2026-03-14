package com.sheinsez.mdropdx12.remote.data.model

data class DisplayInfo(
    val index: Int,
    val deviceName: String,
    val enabled: Boolean,
    val opacity: Int,
    val clickThrough: Boolean,
    val displayRect: Rect,
    val visible: Boolean,
) {
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)
}

data class MirrorState(
    val active: Boolean,
    val renderDisplay: String,
    val renderOpacity: Float,
    val renderFullscreen: Boolean,
    val renderClickThrough: Boolean,
    val monitors: List<DisplayInfo>,
)
