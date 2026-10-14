package com.sheinsez.mdropdx12.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonTemplatesTest {

    @Test
    fun signalTemplatesUseServerSignalNames() {
        val expected = mapOf(
            "Fullscreen" to "FULLSCREEN",
            "Borderless" to "BORDERLESS_FS",
            "Mirror" to "MIRROR",
            "Mirror + WM" to "MIRROR_WM",
            "Watermark" to "WATERMARK",
            "Capture" to "CAPTURE",
        )

        val signalTemplates = ButtonTemplates.categories
            .flatMap { it.templates }
            .filter { it.actionType == ButtonActionType.Signal }

        expected.forEach { (label, payload) ->
            val template = signalTemplates.single { it.label == label }
            assertEquals(payload, template.payload)
        }
    }
}
