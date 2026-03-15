package com.sheinsez.mdropdx12.remote.network

object CommandBuilder {
    fun signal(name: String) = "SIGNAL|$name"
    fun sendKey(hexCode: String) = "SEND=$hexCode"
    fun raw(command: String) = command
    fun message(text: String) = "MSG|text=$text"

    fun wave(params: Map<String, String>): String {
        val parts = params.entries.joinToString("|") { "${it.key}=${it.value}" }
        return "WAVE|$parts"
    }

    fun setMirrorOpacity(value: Int) = "SET_MIRROR_OPACITY=$value"
    fun setMirrorOpacity(display: Int, value: Int) = "SET_MIRROR_OPACITY=$display,$value"
    fun setMirrorClickThru(enabled: Boolean) = "SET_MIRROR_CLICKTHRU=${if (enabled) 1 else 0}"
    fun moveToDisplay(n: Int) = "MOVE_TO_DISPLAY=$n"
    fun diagMirrors() = "DIAG_MIRRORS"
    fun state() = "STATE"

    fun colorHue(value: Float) = "COL_HUE=$value"
    fun colorSaturation(value: Float) = "COL_SATURATION=$value"
    fun colorBrightness(value: Float) = "COL_BRIGHTNESS=$value"
    fun hueAuto(enabled: Boolean) = "HUE_AUTO=${if (enabled) 1 else 0}"

    fun varTime(value: Float) = "VAR_TIME=$value"
    fun varIntensity(value: Float) = "VAR_INTENSITY=$value"
    fun varQuality(value: Float) = "VAR_QUALITY=$value"

    fun amp(left: Float, right: Float) = "AMP|l=$left|r=$right"
    fun fftAttack(value: Float) = "FFT_ATTACK=$value"
    fun fftDecay(value: Float) = "FFT_DECAY=$value"

    fun setDeviceVolume(value: Float) = "SET_DEVICE_VOLUME=${"%.2f".format(value)}"
    fun setDeviceMute(muted: Boolean) = "SET_DEVICE_MUTE=${if (muted) 1 else 0}"
    fun getDeviceVolume() = "GET_DEVICE_VOLUME"
    fun toggleDeviceMute() = "TOGGLE_DEVICE_MUTE"
    fun getAudioDevices() = "GET_AUDIO_DEVICES"
    fun setAudioDevice(name: String) = "DEVICE=OUT|$name"

    fun shaderImport(json: String) = "SHADER_IMPORT=$json"
    fun shaderGlsl(code: String) = "SHADER_GLSL=$code"

    fun mediaPlayPause() = sendKey("0xB3")
    fun mediaNext() = sendKey("0xB0")
    fun mediaPrev() = sendKey("0xB1")

    fun nextPreset() = signal("NEXT_PRESET")
    fun prevPreset() = signal("PREV_PRESET")
}
