package com.sheinsez.mdropdx12.remote.network.vban

import com.sheinsez.mdropdx12.remote.data.model.MixerDevice
import com.sheinsez.mdropdx12.remote.data.model.MixerFader
import kotlin.math.roundToInt

/** One complete `MDXM_STATE`, as the rows the Mixer screen already draws. */
data class MdxmMixerState(
    /** Every fader, in the order the mixer sent them. Nothing filtered. */
    val faders: List<MixerFader> = emptyList(),
    val devices: List<MixerDevice> = emptyList(),
)

/**
 * The mixer's own records, turned into the Mixer screen's rows.
 *
 * WHY THE SCREEN'S MODEL AND NOT A NEW ONE. MDropDX12 reads these same records
 * and publishes them as `MIXER_FADER`, which is what the screen was built on
 * -- so the mapping below is the visualizer's (`mixer_provider_mdxmixer.cpp`),
 * done on the phone instead of across a relay that cannot carry the result.
 *
 * AND WHY THE SAME KEYS, EXACTLY. `<channel>|monitoring`, `<channel>|streaming`
 * and `endpoint:<id>|main` are what the volume-key links are stored under on
 * this phone. A different spelling here would not fail: every link would just
 * quietly stop matching a row.
 *
 * Pure, so the keys can be argued about in a test.
 */
object MdxmMixerRows {

    /** The visualizer's word for the mix mdxmixer calls `personal`. */
    private const val PERSONAL = "monitoring"
    private const val STREAMING = "streaming"

    private const val ENDPOINT_PREFIX = "endpoint:"
    private const val DEVICE_FADER = "main"

    /**
     * mdxmixer's `IsProviderOwnedEndpoint`, copied.
     *
     * Sonar's own endpoints sit at 100 and ignore a write, right beside the
     * Sonar channel that does not, and the mixer leaves them out of its
     * window by this name test. It sends no flag saying so. Until it does,
     * the rule is repeated here rather than drawing eight decoys -- and it is
     * the one place this file decides something the PC did not say.
     */
    private val OWNED_BY_A_PROVIDER = listOf("SteelSeries Sonar - ")

    private fun percentOf(scalar: Float) = (scalar * 100f).roundToInt().coerceIn(0, 100)

    /**
     * Is this batch a mixer state at all?
     *
     * Every listing the mixer sends is bracketed by the same MDXM_BEGIN and
     * MDXM_END, so a batch is told apart by its contents. Without this a peer
     * table would be read as a mixer that has no channels.
     */
    fun isState(batch: List<String>): Boolean = batch.any {
        val verb = it.substringBefore('|').trim()
        verb == "MDXM_CHAN" || verb == "MDXM_DEVLVL"
    }

    fun fromState(batch: List<String>): MdxmMixerState {
        val channels = batch.mapNotNull { MdxmCommands.parseChannel(it) }
        val levels = batch.mapNotNull { MdxmCommands.parseDevice(it) }
        return MdxmMixerState(
            // The order the mixer sent, and nothing cleverer. Its window sorts
            // these by a stored order it does not publish, and guessing at
            // that would be a second arrangement to drift from the first.
            faders = channels.flatMap { channelFaders(it) } + levels.mapNotNull { deviceFader(it) },
            devices = levels.map { device(it) },
        )
    }

    /** Personal first, then Streaming: the order they are read in everywhere. */
    fun channelFaders(c: MdxmChannel): List<MixerFader> {
        // The mixer refuses a mute on Sonar's master rather than performing
        // it, so the row says so and the control is not drawn at all.
        val canMute = c.id != "sonar:masters"
        fun fader(id: String, label: String, vol: Float, muted: Boolean) = MixerFader(
            channelId = c.id,
            channelName = c.name,
            provider = "mdxm",
            faderId = id,
            label = label,
            volumePercent = percentOf(vol),
            muted = muted,
            health = if (c.healthy) "ok" else "bad",
            virtual = false,
            canMute = canMute,
        )
        return listOf(
            fader(PERSONAL, "Personal", c.personalVol, c.personalMute),
            fader(STREAMING, "Streaming", c.streamingVol, c.streamingMute),
        )
    }

    /**
     * A device's volume row, or null when it has none worth drawing.
     *
     * Connected outputs only. A headset in a drawer is listed by the mixer
     * and has no level to move; inputs have never been on this screen.
     */
    fun deviceFader(d: MdxmDevice): MixerFader? {
        if (!d.render || !d.active) return null
        return MixerFader(
            channelId = ENDPOINT_PREFIX + d.id,
            channelName = d.alias,
            provider = "endpoint",
            faderId = DEVICE_FADER,
            label = "Volume",
            volumePercent = percentOf(d.vol),
            muted = d.mute,
            health = "ok",
            virtual = OWNED_BY_A_PROVIDER.any { d.name.startsWith(it) },
            // Present only when a name was chosen, which is the field's rule.
            windowsName = if (d.alias != d.name) d.name else "",
            batteryPercent = d.battery,
            hidden = d.hidden,
        )
    }

    private fun device(d: MdxmDevice) = MixerDevice(
        id = d.id,
        name = d.alias,
        windowsName = d.name,
        flow = if (d.render) "render" else "capture",
        active = d.active,
        // The mixer does not say which endpoints are a monitor's. Nothing is
        // lost by not guessing: the list already puts connected devices first.
        displayAudio = false,
        handsfree = d.handsfree,
        lastSeen = d.seen,
        batteryPercent = d.battery,
    )

    /**
     * The rows to draw.
     *
     * Filed-away devices and another program's endpoints are left out, and
     * come LAST when asked for -- the same place the visualizer path puts
     * them, because the PC draws them nowhere and so gives them no position.
     */
    fun forDisplay(faders: List<MixerFader>, showHidden: Boolean): List<MixerFader> {
        val (tucked, drawn) = faders.partition { it.hidden || it.virtual }
        return if (showHidden) drawn + tucked else drawn
    }

    /** The write that sets this fader's level. */
    fun volumeCommand(f: MixerFader, percent: Int): String {
        val scalar = percent.coerceIn(0, 100) / 100f
        return if (f.channelId.startsWith(ENDPOINT_PREFIX)) {
            MdxmCommands.setDeviceVolume(f.channelId.removePrefix(ENDPOINT_PREFIX), scalar)
        } else {
            MdxmCommands.setVolume(f.channelId, mixOf(f), scalar)
        }
    }

    /** The write that mutes or unmutes this fader. */
    fun muteCommand(f: MixerFader, muted: Boolean): String =
        if (f.channelId.startsWith(ENDPOINT_PREFIX)) {
            MdxmCommands.setDeviceMute(f.channelId.removePrefix(ENDPOINT_PREFIX), muted)
        } else {
            MdxmCommands.setMute(f.channelId, mixOf(f), muted)
        }

    private fun mixOf(f: MixerFader) =
        if (f.faderId == STREAMING) MdxmMix.Streaming else MdxmMix.Personal
}
