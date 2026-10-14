package com.sheinsez.mdropdx12.remote.network.vban

import java.util.Locale

/** What the PC said about this phone's authorization. */
enum class AuthState { Ok, Pending, BadPin, Locked, Denied }

/** The subset of MDXM_VBANSTATE the stream card draws. */
data class VbanState(
    val on: Boolean,
    val emitting: Boolean,
    /** Makeup gain as a PERCENT, as the wire carries it. 100 is unity. */
    val gainPercent: Int,
    val source: String,
    val format: String,
    val fps: Float,
    val peers: Int,
    val sent: Long,
    val starved: Long,
    val dropped: Long,
    val depthMs: Int,
    val behindMs: Int,
    /** The PC's own pipeline estimate -- the PC term of the lag sum. */
    val srcLatencyMs: Int,
    val framesSent: Long,
    val authPending: Boolean,
    /** The peer is on 127.0.0.0/8 and the PC trusts loopback: no PIN needed. */
    val trustLoopback: Boolean,
)

/**
 * One channel of the mixer, from the mixer.
 *
 * NOT MDropDX12's `MIXER_FADER`. The phone used to read the fader list by
 * asking the visualizer, which is downstream of this: mdx12 subscribes to
 * mdxmixer and relays `MDXM_CHAN` onto a `MIXER_FADER` of its own. Asking it
 * meant asking the mixer to forward a question to the visualizer about data
 * that came from the mixer in the first place -- and the answer could not
 * come back, because mdx12 packs `MIXER_STATE` into 3000-character pipe
 * messages and every one of them exceeds a datagram.
 *
 * Read from the source, each record is its own line and the whole state is
 * 100 records in 16 datagrams.
 *
 * A channel carries TWO levels, which is the shape of this mixer: the
 * personal mix is what the owner hears and the streaming mix is what goes
 * out, and they are set independently on the same channel.
 */
data class MdxmChannel(
    val id: String,
    val name: String,
    val healthy: Boolean,
    /** What the owner hears. */
    val personalVol: Float,
    val personalMute: Boolean,
    /** What goes out to the stream. */
    val streamingVol: Float,
    val streamingMute: Boolean,
    val eqOn: Boolean,
    /** -1 when unknown, which is not the same as silent. */
    val peak: Float,
    /**
     * Not capturing because nothing is pulling the mix.
     *
     * Why a channel can be idle, healthy and reporting a peak of -1 all at
     * once, which otherwise reads as a fault.
     */
    val idle: Boolean,
)

/**
 * One Windows endpoint, as the mixer lists it.
 *
 * `MDXM_DEVLVL`, not `MDXM_DEV`: the same reply carries both, and only this
 * one has a level. It is the record mdxmixer draws its own device rows from.
 */
data class MdxmDevice(
    /** The Windows endpoint id, bare -- no `endpoint:` prefix. */
    val id: String,
    /** What Windows calls it. */
    val name: String,
    /** Our name for it, or the Windows name when none was chosen. */
    val alias: String,
    /** An output. False is a capture endpoint. */
    val render: Boolean,
    val isDefault: Boolean,
    val vol: Float,
    val mute: Boolean,
    /**
     * Usable NOW. Not the same as paired: a headset switched off in a drawer
     * is listed and is not this.
     */
    val active: Boolean,
    /** null when the device reports none, which is not 0%. */
    val battery: Int?,
    /** `YYYY-MM-DD HH:MM` local, or empty. */
    val seen: String,
    /** The mono hands-free twin of a headset: mic on, and it sounds terrible. */
    val handsfree: Boolean,
    /** Filed out of the list by the user, at the PC. */
    val hidden: Boolean,
    val pinned: Boolean,
    /** -1 when unknown, which is not the same as silent. */
    val peak: Float,
)

/** Which of a channel's two mixes a write is aimed at. */
enum class MdxmMix(val wire: String) {
    Personal("personal"),
    Streaming("streaming"),
}

/**
 * One row of the mixer's own client table.
 *
 * The authoritative answer to "does the PC still think it is serving me", and
 * worth more than anything the phone can infer from silence: a peer that has
 * been expired is simply ABSENT here, and one whose audio grant was dropped
 * says so in a field.
 */
data class VbanPeer(
    val addr: String,
    val deviceName: String,
    val authed: Boolean,
    val audioOn: Boolean,
    val framesOn: Boolean,
    /**
     * The device id, which is how a phone finds ITSELF in this list.
     *
     * Matched on rather than `addr`, because NAT and a changing source port
     * rewrite the address while the id is the thing both ends authorize by.
     */
    val deviceId: String,
    /** `authorized`, `pending`, `denied` or `none`. */
    val authState: String,
    val fwdSub: Boolean,
    val denied: Boolean,
    val trusted: Boolean,
    /** A configured always-stream target: it never pings and holds no slot. */
    val always: Boolean,
) {
    val awaitingApproval get() = authState == "pending"
}

/**
 * What mdxmixer says about its link to MDropDX12.
 *
 * Every field exists to answer "why isn't it working" without reading a log,
 * so the screen that shows this should show nearly all of it.
 */
data class FwdState(
    /** Forwarding is switched on at the PC. Off means no command will travel. */
    val enabled: Boolean,
    val targetExe: String,
    /** 0 means no target process is resolved right now. */
    val pid: Int,
    val connected: Boolean,
    val launching: Boolean,
    val autolaunch: Boolean,
    /** The PC knows a path it could launch, learned from having seen mdx12 run. */
    val learned: Boolean,
    /**
     * How many TRANSPORTS are subscribed, 0..2 -- NOT how many phones.
     *
     * The PC computes it as `(pipe ? 1 : 0) + (vban ? 1 : 0)`
     * (`app/app_controller.cpp:1227`), so every subscribed phone collapses
     * into the single VBAN "1". Drawing it as a device count would report two
     * when a phone and the PC's own pipe client are listening, and one no
     * matter how many phones are. The spec's §11 reads it the other way, so
     * this is worth not re-deriving from the field name.
     *
     * Useful only as "is anything listening", which is what a 1 in it means.
     */
    val subs: Int,
    val queued: Int,
    /**
     * Records the PC could not pass on. A non-zero value means YOUR VIEW IS
     * INCOMPLETE -- the relay queue caps at 256 and drops the newest.
     */
    val dropped: Long,
    val sent: Long,
    val received: Long,
    val path: String,
    val lastError: String,
    /**
     * The most `MDXM_FWDP` parts this mixer cuts one relayed record into, or
     * 0 from a mixer that does not send parts at all.
     *
     * Zero is the older behaviour and the default for that reason: such a
     * mixer answers `MDXM_ERR|msg=toolong` in place of any reply too large for
     * one packet, and relays a packed message without taking it apart. A
     * mixer that names a number does both.
     */
    val maxParts: Int = 0,
)

/** One process mdxmixer can see, from MDXM_FWDTARGET. */
data class FwdTarget(
    val pid: Int,
    val exe: String,
    /** This is the one commands are being sent to. */
    val isTarget: Boolean,
    val path: String,
)

/** Whether a command can be put on the wire at all, and why not. */
sealed interface Forward {
    /** Ready to send. */
    data class Record(val text: String) : Forward

    /**
     * Refused HERE, before the wire. [why] is shown to a person, so it says
     * what to do rather than what went wrong internally.
     */
    data class Refused(val why: String) : Forward
}

/**
 * The mdxmixer dialect, which is NOT MDropDX12's.
 *
 * Deliberately not folded into CommandBuilder/MessageParser: the two
 * protocols share a surface grammar and nothing else, and one file that
 * speaks both is one file where a verb goes to the wrong PC.
 */
object MdxmCommands {

    /** `MDXM_FWDR|msg=` and `MDXM_FWD|cmd=`: raw remainders, never fields. */
    private const val RELAY_PREFIX = "MDXM_FWDR|msg="
    private const val FORWARD_PREFIX = "MDXM_FWD|cmd="
    private const val PART_PREFIX = "MDXM_FWDP|"
    private const val RAW_MSG = "|msg="

    /**
     * The most parts the wire can name, not the most a mixer sends.
     *
     * A mixer says how many it will cut a record into with `maxparts`, and
     * today that is 16. This is the ceiling of the format itself -- the part
     * header leaves two digits for `i` and `n` -- so a mixer that raises its
     * own limit is still read, and a count beyond what any mixer could mean is
     * refused before room is set aside for it.
     */
    private const val MAX_PARTS_ON_WIRE = 99

    /** Requests are single-packet; free text truncates rather than overflows. */
    private fun fit(record: String): String {
        var s = record
        while (s.toByteArray(Charsets.UTF_8).size > VbanCodec.MAX_DATA) {
            s = s.substring(0, s.length - 1)
        }
        return s
    }

    fun auth(pin: String, deviceId: String, name: String): String =
        fit("MDXM_AUTH|pin=$pin|device=$deviceId|name=$name")

    /**
     * The state query is a BARE MDXM_VBAN.
     *
     * `MDXM_VBANSTATE` is the reply record and is not a verb -- sending it
     * earns `MDXM_ERR|msg=unknown verb`, confirmed against the running
     * server. The server's rule is "any non-empty-key field present means
     * set, none means query", so a query must carry no keyed fields at all.
     */
    fun vbanQuery(): String = "MDXM_VBAN"

    /**
     * Makeup gain as a PERCENT, 0..6400, which is what the wire carries
     * (`docs/ipc.md` §11).
     *
     * Deliberately an Int percent end to end rather than a 0..1 scalar
     * converted here. The first version of this sent `gain=0.50`, which the
     * server reads as half a percent -- silence -- and then answers with a
     * perfectly healthy `MDXM_VBANSTATE|…|gain=0`. A connection, a good
     * state record, and no sound, with nothing naming the cause
     * (mdxmixer forgejo#20). A unit conversion at a boundary where the wire
     * already states its unit is a bug waiting to be written twice.
     *
     * It reaches 6400 because the personal mix runs at a few percent of full
     * scale -- the faders are the listening level -- so 100 is not a ceiling.
     */
    fun gain(percent: Int): String = "MDXM_VBAN|gain=" + percent.coerceIn(0, 6400)

    fun audio(on: Boolean): String = "MDXM_VBAN|audio=" + if (on) "1" else "0"

    fun frames(on: Boolean, fps: Float?): String =
        if (!on) {
            "MDXM_VBAN|frames=0"
        } else {
            "MDXM_VBAN|frames=1" +
                (fps?.let { "|fps=" + String.format(Locale.ROOT, "%.1f", it) } ?: "")
        }

    // ── forwarding a command to MDropDX12 through the mixer ──────────────

    /**
     * Wrap one mdx12 command as `MDXM_FWD|cmd=<raw>`.
     *
     * `cmd=` is a RAW REMAINDER, not a field: the receiver slices everything
     * after the literal `|cmd=` and never parses it. That is the only way the
     * grammar can carry a foreign record at all -- mdxmixer's parser treats
     * every `|` as an unconditional field boundary and there is no escaping,
     * so `SET_DISPLAY_MODE=2,preset` would otherwise arrive as options
     * addressed to the mixer. It follows that a raw field is always LAST in
     * its record, which is why nothing may be appended after this.
     *
     * REFUSES rather than truncating, unlike every other builder here. The
     * `fit` truncation is safe for free text that is only ever read by a
     * person; it is not safe for a command, where cutting the tail off
     * `SET_DISPLAY_MODE=2,preset` leaves a verb mdx12 will act on differently
     * instead of one it will reject.
     */
    fun forward(raw: String): Forward {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return Forward.Refused("Nothing to send.")
        // Case-INSENSITIVE, unlike the PC's own guard.
        //
        // mdxmixer tests `raw.rfind(L"MDXM_", 0) == 0`
        // (`app/app_controller.cpp:1214`), so a lowercase `mdxm_ping` passes it
        // and reaches mdx12 -- which treats text it does not recognise as a
        // script line and, failing that, DRAWS IT ON THE VISUALIZER. The raw
        // command box and the user-authored buttons can both carry one. One
        // `uppercase()` here closes it for this client whatever the PC does.
        if (cmd.uppercase().startsWith("MDXM_")) {
            return Forward.Refused("That is a mixer command, not a visualizer one.")
        }
        val record = FORWARD_PREFIX + cmd
        if (record.toByteArray(Charsets.UTF_8).size > VbanCodec.MAX_DATA) {
            return Forward.Refused("That command is too long to send through the mixer.")
        }
        return Forward.Record(record)
    }

    /** Subscribe to, or stop, the relay of mdx12's replies. Positional. */
    fun fwdSub(on: Boolean): String = "MDXM_FWDSUB|" + if (on) "1" else "0"

    fun fwdStateQuery(): String = "MDXM_FWDSTATE"

    fun fwdTargetsQuery(): String = "MDXM_FWDTARGETS"

    /**
     * The mdx12 record inside `MDXM_FWDR|msg=<raw>`, verbatim.
     *
     * Sliced, never split. A `PRESET_ROW|i=0|name=Geiss|path=C:\x.milk` comes
     * back through here whole; splitting the relay record on `|` to find the
     * payload shreds it into pieces indistinguishable from fields addressed to
     * the mixer, which is the single most likely way to misimplement this
     * protocol.
     *
     * Null for anything that is not a relay record, and for an empty payload:
     * mdx12 has no empty records, so one would be a framing mistake rather
     * than news.
     */
    fun parseRelay(record: String): String? {
        if (!record.startsWith(RELAY_PREFIX)) return null
        return record.substring(RELAY_PREFIX.length).takeIf { it.isNotEmpty() }
    }

    /**
     * One part of a relayed record too large for a packet:
     * `MDXM_FWDP|id=<n>|i=<k>|n=<N>|msg=<slice>`.
     *
     * `msg=` is a raw remainder here exactly as it is on `MDXM_FWDR`, so the
     * three fields are read from what is AHEAD of the first `|msg=` and
     * everything after it is the slice, untouched. A slice of an mdx12 record
     * is full of `|` and `=`, and nothing stops one from containing `|i=9`.
     *
     * NOT TRIMMED, and it matters more here than anywhere: the cut falls
     * wherever the packet filled, which can be beside a space, and a space
     * taken off the end of one slice is a space missing from the middle of
     * the record.
     *
     * Null for anything that does not say where it belongs. A part with no
     * id, or a place outside its own count, cannot be joined to anything, and
     * holding it would only keep a record open that can never finish.
     */
    fun parsePart(record: String): RelayPart? {
        if (!record.startsWith(PART_PREFIX)) return null
        val at = record.indexOf(RAW_MSG)
        if (at < 0) return null
        val f = fields(record.substring(0, at))
        val id = f["id"].orEmpty()
        val index = f["i"]?.toIntOrNull() ?: return null
        val count = f["n"]?.toIntOrNull() ?: return null
        val slice = record.substring(at + RAW_MSG.length)
        if (id.isEmpty() || slice.isEmpty()) return null
        if (count !in 1..MAX_PARTS_ON_WIRE || index !in 0 until count) return null
        return RelayPart(id, index, count, slice)
    }

    /**
     * The text of an `MDXM_ERR|msg=…`.
     *
     * Also a raw slice. The message is free text and last in its record by the
     * house rule, so it may contain `|` -- and `fields()` would silently
     * return only the part before the first one.
     */
    fun parseErr(record: String): String? {
        val prefix = "MDXM_ERR|msg="
        if (!record.startsWith(prefix)) return null
        return record.substring(prefix.length).takeIf { it.isNotEmpty() }
    }

    /**
     * An `MDXM_ERR|msg=` text, as a sentence for a person.
     *
     * The wire's words are not the user's: "toolong" names a datagram size
     * limit, and it appeared on screen verbatim. Each of these calls for a
     * different action, which is the reason to translate rather than to
     * prefix the raw text with an apology.
     *
     * Unknown messages pass through, because a message this does not
     * recognise is better shown than swallowed.
     */
    fun explainErr(msg: String): String = when (msg.trim()) {
        "toolong" ->
            "The visualizer's answer was too large to come back through the mixer."
        "forwarding is off" ->
            "The mixer is not passing commands to the visualizer. Turn forwarding on in mdxmixer."
        "notarget" -> "The visualizer is not running on the PC."
        "nolaunchpath" ->
            "The PC would start the visualizer but does not know where it is."
        "launchfail" -> "The PC could not start the visualizer."
        "queuefull" -> "The PC is behind; that command was dropped."
        "refusing to forward an MDXM record" ->
            "That is a mixer command, not a visualizer one."
        "unauthorized" -> "The PC no longer recognises this phone."
        "empty command" -> "There was nothing to send."
        else -> msg
    }

    /**
     * Is this refusal a permanent property of the relay rather than news?
     *
     * `toolong` will be true for that verb every time, for ever, so it
     * belongs where the missing content is and not in a banner over whatever
     * screen happens to be in front -- which is where it landed, reading as
     * the preset list having failed.
     */
    fun errIsPermanent(msg: String): Boolean = msg.trim() == "toolong"

    /** True for the bare acceptance of a forwarded command. */
    fun isOk(record: String): Boolean = record.trim() == "MDXM_OK"

    /**
     * The forwarding state.
     *
     * The two free-text fields are LAST in the record by mdxmixer's house rule
     * (`protocol.cpp:124-125`), stated so that a reader which stops at the
     * first one has already seen every machine-readable value. This stops
     * there rather than merely trusting it: `fields()` builds a map, later keys
     * win, and a `lastError` carrying the text `|path=…` would otherwise
     * replace the real path with a fragment of an error message.
     */
    fun parseFwdState(record: String): FwdState? {
        if (!verbIs(record, "MDXM_FWDSTATE")) return null
        val errAt = record.indexOf("|lastError=")
        val head = if (errAt >= 0) record.substring(0, errAt) else record
        val pathAt = head.indexOf("|path=")
        val machine = if (pathAt >= 0) head.substring(0, pathAt) else head
        val f = fields(machine)
        return FwdState(
            enabled = f["enabled"] == "1",
            targetExe = f["targetExe"].orEmpty(),
            pid = f["pid"]?.toIntOrNull() ?: 0,
            connected = f["connected"] == "1",
            launching = f["launching"] == "1",
            autolaunch = f["autolaunch"] == "1",
            learned = f["learned"] == "1",
            subs = f["subs"]?.toIntOrNull() ?: 0,
            queued = f["queued"]?.toIntOrNull() ?: 0,
            dropped = f["dropped"]?.toLongOrNull() ?: 0L,
            sent = f["sent"]?.toLongOrNull() ?: 0L,
            received = f["received"]?.toLongOrNull() ?: 0L,
            path = if (pathAt >= 0) head.substring(pathAt + "|path=".length) else "",
            lastError = if (errAt >= 0) record.substring(errAt + "|lastError=".length) else "",
            maxParts = f["maxparts"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }

    /** One discovered process. `path` is last and free text, so reading stops there. */
    fun parseFwdTarget(record: String): FwdTarget? {
        if (!verbIs(record, "MDXM_FWDTARGET")) return null
        val pathAt = record.indexOf("|path=")
        val f = fields(if (pathAt >= 0) record.substring(0, pathAt) else record)
        return FwdTarget(
            pid = f["pid"]?.toIntOrNull() ?: 0,
            exe = f["exe"].orEmpty(),
            isTarget = f["target"] == "1",
            path = if (pathAt >= 0) record.substring(pathAt + "|path=".length) else "",
        )
    }

    // ── the mixer's own channels, read from the mixer ────────────────────

    /**
     * The whole mixer state: channels, routes, devices, device levels.
     *
     * Batched between MDXM_BEGIN and MDXM_END, so it arrives through
     * [ReplyAssembler] like any other listing. Measured at 100 records in 16
     * datagrams with the longest record 346 bytes -- comfortably inside the
     * payload, because the mixer formats one record per line rather than
     * packing them for a pipe read.
     */
    fun stateQuery(): String = "MDXM_STATE"

    fun parseChannel(record: String): MdxmChannel? {
        if (!verbIs(record, "MDXM_CHAN")) return null
        val f = fields(record)
        val id = f["id"].orEmpty()
        if (id.isEmpty()) return null
        return MdxmChannel(
            id = id,
            name = f["name"].orEmpty(),
            healthy = f["health"] != "bad",
            personalVol = f["pvol"]?.toFloatOrNull() ?: 0f,
            personalMute = f["pmute"] == "1",
            streamingVol = f["svol"]?.toFloatOrNull() ?: 0f,
            streamingMute = f["smute"] == "1",
            eqOn = f["eq"] == "1",
            // -1 means UNKNOWN. Defaulting a missing peak to 0 would draw a
            // live channel as silent, which is the opposite of what -1 says.
            peak = f["peak"]?.toFloatOrNull() ?: -1f,
            idle = f["idle"] == "1",
        )
    }

    /** `MDXM_SET|<ch>|<personal|streaming>|<0..1>`, positional. */
    fun setVolume(channelId: String, mix: MdxmMix, volume: Float): String =
        "MDXM_SET|$channelId|${mix.wire}|" +
            String.format(Locale.ROOT, "%.4f", volume.coerceIn(0f, 1f))

    /** `MDXM_MUTE|<ch>|<personal|streaming>|<0|1>`, positional. */
    fun setMute(channelId: String, mix: MdxmMix, muted: Boolean): String =
        "MDXM_MUTE|$channelId|${mix.wire}|" + if (muted) "1" else "0"

    /**
     * One `MDXM_DEVLVL` row.
     *
     * Verb-exact for the usual reason, and here it has a second victim:
     * `MDXM_DEV` is the raw endpoint list in the very same reply, so a prefix
     * test would read every device twice, the second time with no level.
     */
    fun parseDevice(record: String): MdxmDevice? {
        if (!verbIs(record, "MDXM_DEVLVL")) return null
        val f = fields(record)
        val id = f["id"].orEmpty()
        if (id.isEmpty()) return null
        val name = f["name"].orEmpty()
        return MdxmDevice(
            id = id,
            name = name,
            alias = f["alias"].orEmpty().ifEmpty { name },
            render = f["flow"] != "capture",
            isDefault = f["default"] == "1",
            vol = f["vol"]?.toFloatOrNull() ?: 0f,
            mute = f["mute"] == "1",
            active = f["active"] == "1",
            // -1 is the mixer saying "no battery here". Not 0%.
            battery = f["battery"]?.toIntOrNull()?.takeIf { it >= 0 },
            seen = f["seen"].orEmpty(),
            handsfree = f["handsfree"] == "1",
            hidden = f["hidden"] == "1",
            pinned = f["pinned"] == "1",
            peak = f["peak"]?.toFloatOrNull() ?: -1f,
        )
    }

    /** `MDXM_DEVVOL|<endpointId>|<0..1>`, positional: an endpoint's own Windows volume. */
    fun setDeviceVolume(endpointId: String, volume: Float): String =
        "MDXM_DEVVOL|$endpointId|" +
            String.format(Locale.ROOT, "%.4f", volume.coerceIn(0f, 1f))

    /** `MDXM_DEVMUTE|<endpointId>|<0|1>`, positional. */
    fun setDeviceMute(endpointId: String, muted: Boolean): String =
        "MDXM_DEVMUTE|$endpointId|" + if (muted) "1" else "0"

    /**
     * Ask the mixer who it is serving.
     *
     * Answers a batch between MDXM_BEGIN and MDXM_END, so the reply comes back
     * through [ReplyAssembler] like any other listing.
     */
    fun peersQuery(): String = "MDXM_VBANPEERS"

    fun parseVbanPeer(record: String): VbanPeer? {
        if (!verbIs(record, "MDXM_VBANPEER")) return null
        val f = fields(record)
        return VbanPeer(
            addr = f["addr"].orEmpty(),
            deviceName = f["device"].orEmpty(),
            authed = f["authed"] == "1",
            audioOn = f["audio"] == "1",
            framesOn = f["frames"] == "1",
            deviceId = f["id"].orEmpty(),
            // Only this field separates a peer WAITING FOR APPROVAL from one
            // that has never asked; `authed` cannot, and MDXM_VBANSTATE's
            // `authpending` is a count with no way back to which peer it
            // counted.
            authState = f["state"].orEmpty(),
            fwdSub = f["fwd"] == "1",
            denied = f["denied"] == "1",
            trusted = f["trusted"] == "1",
            always = f["always"] == "1",
        )
    }

    private fun fields(record: String): Map<String, String> =
        record.split("|").drop(1).mapNotNull {
            val k = it.substringBefore("=", "")
            if (k.isEmpty()) null else k to it.substringAfter("=", "")
        }.toMap()

    /**
     * Does this record's verb match exactly?
     *
     * `startsWith` is the wrong rule and this protocol has the pair that
     * proves it: `MDXM_FWDTARGETS` is the QUERY and `MDXM_FWDTARGET` is one
     * reply ROW, one trailing character apart. A prefix test reads the query
     * as a row with no fields -- pid 0, blank exe -- so the phone would have
     * listed a phantom process whenever it echoed its own question.
     */
    private fun verbIs(record: String, verb: String): Boolean =
        record.substringBefore('|').trim() == verb

    fun parseAuthState(record: String): AuthState? {
        if (!verbIs(record, "MDXM_AUTHSTATE")) return null
        val f = fields(record)
        if (f["pending"] == "1") return AuthState.Pending
        if (f["ok"] == "1") return AuthState.Ok
        return when (f["err"]) {
            "badpin" -> AuthState.BadPin
            "locked" -> AuthState.Locked
            "denied" -> AuthState.Denied
            else -> null
        }
    }

    fun parseVbanState(record: String): VbanState? {
        if (!verbIs(record, "MDXM_VBANSTATE")) return null
        val f = fields(record)
        return VbanState(
            on = f["on"] == "1",
            emitting = f["emitting"] == "1",
            gainPercent = f["gain"]?.toIntOrNull() ?: 100,
            source = f["source"].orEmpty(),
            format = f["format"].orEmpty(),
            fps = f["fps"]?.toFloatOrNull() ?: 0f,
            peers = f["peers"]?.toIntOrNull() ?: 0,
            sent = f["sent"]?.toLongOrNull() ?: 0L,
            starved = f["starved"]?.toLongOrNull() ?: 0L,
            dropped = f["dropped"]?.toLongOrNull() ?: 0L,
            depthMs = f["depthms"]?.toIntOrNull() ?: 0,
            behindMs = f["behindms"]?.toIntOrNull() ?: 0,
            srcLatencyMs = f["srclatencyms"]?.toIntOrNull() ?: 0,
            framesSent = f["framessent"]?.toLongOrNull() ?: 0L,
            authPending = f["authpending"] == "1",
            trustLoopback = f["loopback"] == "1",
        )
    }

    /**
     * Assembles a BEGIN...END reply that spans packets.
     *
     * The MDXM surface is idempotent request/reply, so recovery is entirely
     * client-side: a nuFrame gap seen mid-assembly throws the partial away
     * and the caller re-issues the query. Keeping what arrived would hand the
     * UI a snapshot missing rows it cannot know about.
     */
    class ReplyAssembler {
        private val records = mutableListOf<String>()
        private var assembling = false
        private var lastFrame: Long? = null
        var needsRequery: Boolean = false
            private set

        fun reset() {
            records.clear(); assembling = false; lastFrame = null; needsRequery = false
        }

        /**
         * One TXT datagram has arrived, whatever was in it.
         *
         * Called ONCE PER DATAGRAM and before its records, because that is
         * where the frame number lives. Two things follow from counting here
         * rather than per record:
         *
         * THE SAME FRAME IS NOT A GAP. The mixer packs as many whole records
         * into a datagram as will fit, and they all carry that datagram's one
         * number. Counted per record, the second record of any reply looked
         * like a gap and the reply was discarded -- a peer table thrown away
         * at its first row, every time, so nothing downstream of this had
         * ever run against a real server.
         *
         * A DATAGRAM FOR SOMEBODY ELSE IS NOT A GAP EITHER. Relayed records
         * are pushed from another thread on the mixer, numbered from the same
         * per-peer counter, and land between the datagrams of a long reply.
         * The session takes those before the assembler is shown anything, so
         * the frame has to be noted here or a sixteen-datagram state would be
         * lost to any push that happened to arrive during it.
         */
        fun onDatagram(nuFrame: Long) {
            val previous = lastFrame
            lastFrame = nuFrame
            if (previous == null || !assembling) return
            // 32 bits on the wire, so 0 follows 0xFFFFFFFF.
            val forward = (nuFrame - previous) and 0xFFFFFFFFL
            if (forward > 1L) {
                reset()
                needsRequery = true
            }
        }

        /** One record of the datagram last announced. The list on END, else null. */
        fun accept(record: String): List<String>? {
            when {
                record.startsWith("MDXM_BEGIN") -> {
                    records.clear(); assembling = true; needsRequery = false
                }
                record.startsWith("MDXM_END") -> {
                    if (!assembling) return null
                    val out = records.toList()
                    records.clear(); assembling = false
                    return out
                }
                assembling -> records += record
            }
            return null
        }

        /** Both steps at once, for a caller holding a record and its frame. */
        fun accept(nuFrame: Long, record: String): List<String>? {
            onDatagram(nuFrame)
            return accept(record)
        }
    }
}
