package com.sheinsez.mdropdx12.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheinsez.mdropdx12.remote.data.model.DisplayInfo

/**
 * The desktop as Windows lays it out, drawn from the display rects DIAG_MIRRORS
 * already reports, with the monitor you are configuring picked by tapping it.
 *
 * Monitor origins are signed — a panel left of or above the primary has negative
 * coordinates — so the whole arrangement is normalised into a bounding box and
 * scaled to fit rather than assuming anything starts at zero.
 */
@Composable
fun MonitorMap(
    monitors: List<DisplayInfo>,
    primaryDeviceNumber: Int?,
    selectedDeviceNumber: Int?,
    /** Displays showing a preset of their own, in this process or their own. */
    ownPresetDisplays: Set<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (monitors.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No display information yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme

    val selectedFill = scheme.primary.copy(alpha = 0.30f)
    val childFill = scheme.tertiary.copy(alpha = 0.30f)
    val mirrorFill = scheme.surfaceVariant
    val offFill = scheme.surfaceVariant.copy(alpha = 0.35f)
    val selectedStroke = scheme.primary
    val normalStroke = scheme.outline
    val labelColor = scheme.onSurface
    val subLabelColor = scheme.onSurfaceVariant

    // World bounds across every monitor, including negative origins.
    val minX = monitors.minOf { it.displayRect.x }.toFloat()
    val minY = monitors.minOf { it.displayRect.y }.toFloat()
    val maxX = monitors.maxOf { it.displayRect.right }.toFloat()
    val maxY = monitors.maxOf { it.displayRect.bottom }.toFloat()
    val worldW = (maxX - minX).coerceAtLeast(1f)
    val worldH = (maxY - minY).coerceAtLeast(1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .pointerInput(monitors) {
                detectTapGestures { tap ->
                    val scale = minOf(size.width / worldW, size.height / worldH) * 0.92f
                    val originX = (size.width - worldW * scale) / 2f - minX * scale
                    val originY = (size.height - worldH * scale) / 2f - minY * scale
                    // Reverse order: the last drawn sits on top, so it wins a tap
                    // where two monitors overlap in the layout.
                    monitors.lastOrNull { m ->
                        val left = m.displayRect.x * scale + originX
                        val top = m.displayRect.y * scale + originY
                        tap.x >= left && tap.x <= left + m.displayRect.w * scale &&
                            tap.y >= top && tap.y <= top + m.displayRect.h * scale
                    }?.let { onSelect(it.deviceNumber) }
                }
            },
    ) {
        val scale = minOf(size.width / worldW, size.height / worldH) * 0.92f
        val originX = (size.width - worldW * scale) / 2f - minX * scale
        val originY = (size.height - worldH * scale) / 2f - minY * scale

        monitors.forEach { m ->
            val left = m.displayRect.x * scale + originX
            val top = m.displayRect.y * scale + originY
            val w = m.displayRect.w * scale
            val h = m.displayRect.h * scale
            val isSelected = m.deviceNumber == selectedDeviceNumber
            val isChild = m.deviceNumber in ownPresetDisplays

            drawRoundRect(
                color = when {
                    isSelected -> selectedFill
                    isChild -> childFill
                    m.enabled -> mirrorFill
                    else -> offFill
                },
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
            )
            drawRoundRect(
                color = if (isSelected) selectedStroke else normalStroke,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                style = Stroke(width = if (isSelected) 4f else 2f),
            )

            val number = measurer.measure(
                text = "${m.deviceNumber}",
                style = TextStyle(color = labelColor, fontSize = 20.sp),
            )
            val caption = measurer.measure(
                text = "${m.displayRect.w}×${m.displayRect.h}",
                style = TextStyle(color = subLabelColor, fontSize = 9.sp),
            )

            // Stack from the measured heights rather than fixed pixel nudges:
            // the offsets are raw pixels, so on a dense screen a nudge that
            // looked like a gap put the caption straight through the number.
            val captionFits = w > caption.size.width + 8f &&
                h > number.size.height + caption.size.height + 8f
            val blockHeight = number.size.height + if (captionFits) caption.size.height else 0
            val blockTop = top + (h - blockHeight) / 2f

            drawText(
                textLayoutResult = number,
                topLeft = Offset(left + (w - number.size.width) / 2f, blockTop),
            )
            if (captionFits) {
                drawText(
                    textLayoutResult = caption,
                    topLeft = Offset(
                        left + (w - caption.size.width) / 2f,
                        blockTop + number.size.height,
                    ),
                )
            }

            // The display hosting the render window gets a filled corner dot.
            if (m.deviceNumber == primaryDeviceNumber) {
                drawCircle(
                    color = selectedStroke,
                    radius = 5f,
                    center = Offset(left + 12f, top + 12f),
                )
            }

            // A disabled monitor is crossed through, so "off" reads at a glance
            // rather than depending on a fill colour the theme might wash out.
            if (!m.enabled) {
                drawLine(
                    color = normalStroke,
                    start = Offset(left, top),
                    end = Offset(left + w, top + h),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

/** Legend colours are meaningless without this; keep it beside the map. */
@Composable
fun MonitorMapLegend(modifier: Modifier = Modifier) {
    Text(
        text = "Tap a monitor to configure it  •  dot = primary",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}
