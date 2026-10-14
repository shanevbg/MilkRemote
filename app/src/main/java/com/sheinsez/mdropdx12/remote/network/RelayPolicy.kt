package com.sheinsez.mdropdx12.remote.network

/**
 * What a MDropDX12 command needs from the transport carrying it.
 *
 * Over mdx12's own TCP socket this question does not arise: the socket is
 * ordered, framed, reliable, and the replies on it are answers to what this
 * phone asked. Through mdxmixer's relay none of those four hold, and they fail
 * one at a time rather than together -- so "does this command work through the
 * mixer" has four different answers depending on what the caller needed back.
 *
 * Pure and verb-keyed, so the honest answer can be given in a test and drawn
 * in the UI rather than discovered on the rig.
 */
enum class RelayFitness {
    /**
     * Delivery is all the caller wanted. Works through the relay.
     *
     * The bulk of the surface. CommandBuilder was written absolute-not-toggle
     * throughout -- `SET_MIRROR_ENABLED=1`, never a flip -- specifically so a
     * client that cannot read PC state can still drive a switch, and that
     * discipline is what makes the relay viable at all.
     */
    Action,

    /**
     * Delivery is all the caller wanted, but a DUPLICATE inverts it.
     *
     * The two toggles left in the surface. Safe to send once; never safe to
     * re-send on a hunch that the first one was lost, which is exactly the
     * instinct a lossy transport encourages.
     */
    ToggleAction,

    /**
     * A short reply comes back, but it is not addressed to this phone.
     *
     * mdx12 broadcasts every reply to every client, so the relay carries
     * answers to commands the PC's own UI and any other phone sent. Usable
     * only where the record is self-describing -- it names the display, the
     * fader, the value -- so that adopting a stranger's answer is harmless
     * because it is the same truth.
     */
    SharedAnswer,

    /**
     * The caller needs a COMPLETE batch, and the relay cannot promise one.
     *
     * A dropped datagram costs the terminator, and a foreign terminator
     * publishes this phone's half-collected rows as though they were finished.
     * Both failures look like a correct, smaller answer -- which is worse than
     * an error, because nothing asks again.
     */
    Batch,

    /**
     * The reply cannot cross the relay.
     *
     * A record that will not fit a 1436-byte payload was always answered
     * `MDXM_ERR|msg=toolong` rather than cut at the packet boundary, which is
     * the right call -- half a record with nothing to rejoin it by is worse
     * than an error. A newer mixer carries such a record as numbered parts
     * instead and says so with `maxparts`, so for some verbs this is the
     * answer only against a mixer that does not; see [RelayPolicy.fitnessOf].
     * For the rest it is the answer however patient the caller is.
     */
    TooBig,

    /**
     * Subscribing succeeds and then nothing ever arrives.
     *
     * Three of mdx12's push sites broadcast to TCP clients only and never
     * reach a pipe client such as mdxmixer, so the subscription is accepted
     * and the stream is empty (filed as mdropdx12 #480). The worst of the five
     * to leave unlabelled: every other kind fails visibly.
     */
    DeadSubscription,
}

/**
 * Which commands survive the trip through mdxmixer, and which only look like
 * they do.
 *
 * Matched on the verb -- everything before the first `=` or `|` -- because the
 * argument never changes the answer.
 */
object RelayPolicy {

    /** A duplicate delivery inverts these, so they must never be retried. */
    private val TOGGLES = setOf(
        "TOGGLE_DEVICE_MUTE",
        // The PC verb is a toggle despite the SET_ spelling.
        "SET_MIRROR_WATERMARK",
    )

    /**
     * Replies too large for the relay WHATEVER THE MIXER DOES.
     *
     * Both of these would physically cross a mixer that sends parts, and
     * neither is worth sending that way. Parts make a large reply possible;
     * they do not make the relay a bulk transport.
     */
    private val TOO_BIG = setOf(
        // Packed by the PC into 8000-character chunks, six parts each, and
        // the whole listing is about thirty datagrams that ALL have to arrive
        // with nothing sent twice. On a link losing 2 packets in 100 that is
        // a coin toss.
        "HELP",
        // Answers with a header and then AN ENTIRE PRESET FILE -- multi-KB,
        // full of newlines. A mixer that takes a message apart relays it a
        // line at a time with the blank lines gone and nothing to say where
        // it ends, which is not the file. Not reachable from any screen, but
        // the raw command box and the user-authored buttons can carry any of
        // mdx12's verbs, and the default for an unlisted one is "assume it
        // travels".
        "GET_PRESET_SOURCE",
    )

    /**
     * ONE record too large for a packet, which a mixer that sends parts cuts
     * into `MDXM_FWDP` records and an older one answers `toolong`.
     *
     * `DIAG_MIRRORS` is a single record whose length grows with the display
     * count: 2726 bytes with three displays attached, measured off the
     * visualizer's own pipe, against a 1436-byte payload. That is two parts.
     */
    private val IN_PARTS = setOf(
        "DIAG_MIRRORS",
    )

    /**
     * MANY small rows that mdx12 packs into one pipe message.
     *
     * Never large records, only large messages: an older mixer wraps the
     * message whole and answers `toolong` for it -- twenty-two in a row for
     * `MIXER_STATE`, measured against the live relay, because mdx12 packs it
     * into 3000-character messages. A mixer that sends parts also takes each
     * message apart and relays a row at a time, so these arrive as the
     * batches they always were, with a batch's caveats.
     */
    private val PACKED = setOf(
        "MIXER_STATE",
        "MIXER_VIEW",
        "MIXER_ORDER",
        // Classified a batch once on the shape of its name, then measured
        // answering `toolong`: 82 rows of about 70 bytes in one 5902-byte
        // message.
        "DISPLAY_PROFILE_LIST",
    )

    /** Accepted, then silent: the pushes behind these never reach a pipe client. */
    private val DEAD_SUBSCRIPTIONS = setOf(
        "MIXER_SUBSCRIBE",
    )

    /**
     * Queries whose reply is a batch, framed by a terminator or a sentinel.
     *
     * THESE TRAVEL. They were refused at first, on the reasoning that a lost
     * terminator and a stranger's terminator both publish a smaller answer
     * that looks correct -- true, but a reason to handle the batch carefully,
     * not a reason to refuse a reply that arrives perfectly well. Refusing
     * them emptied the Displays and Presets screens, which is how the app came
     * to look as though nothing worked at all.
     *
     * Measured against the live relay rather than reasoned about:
     *
     *     GET_CHILDREN        4 records, CHILDREN_END included
     *     GET_PRESET_FOLDERS  1 record
     *     GET_PRESET_TAGS     1 record
     *     GET_AUDIO_DEVICES   1 record (24 devices in it)
     *     BROWSE_PRESETS=0,2  3 records, PRESET_ROWS_END|total=1795 included
     *
     * Each row is its own small record, so the 1436-byte ceiling is nowhere
     * near. The ones a mixer has refused are in IN_PARTS and PACKED, and they
     * are there because the server said `toolong`, not because they looked
     * big.
     *
     * The caution that remains is real and belongs to the CALLER: a batch can
     * arrive incomplete, and a terminator can belong to somebody else.
     */
    private val BATCHES = setOf(
        "BROWSE_PRESETS",
        "GET_CHILDREN",
        "GET_AUDIO_DEVICES",
        "GET_PRESET_FOLDERS",
        "GET_PRESET_TAGS",
    )

    /**
     * NOT a batch, despite answering with two records and an `END_BATCH`.
     *
     * Both records fit one packet, and there is only one PC on the far end --
     * so a relayed VERSION reply provoked by somebody else's query carries the
     * same truth this phone would have got by asking. Adopting a stranger's
     * answer is harmless here in a way it is not for a preset page.
     *
     * Worth stating as its own case because the alternative is costly: every
     * feature gate in the app reads this, and treating it as unavailable makes
     * display profiles, fullscreen, idle and the per-display verbs all
     * silently absent rather than refused -- features quietly missing, with
     * nothing to diagnose.
     */
    private val SELF_EVIDENT = setOf("GET_VERSION")

    /**
     * Verbs this table has actually been reasoned about, as opposed to
     * defaulted.
     *
     * `fitnessOf` answers `Action` for anything it does not recognise, which
     * is right for the app's own surface -- mdx12 grows verbs, and a client
     * that refused every unfamiliar one would break each time the PC gained
     * one. It is wrong for the two places a PERSON types the verb: the raw
     * command box and a user-authored button, which can carry any of mdx12's
     * three hundred. For those, "I have not thought about this one" is worth
     * saying out loud before the reply goes missing.
     */
    fun isKnown(command: String): Boolean {
        if (command.startsWith("DISPLAY|")) return true
        val verb = verbOf(command)
        return verb in TOO_BIG ||
            verb in IN_PARTS ||
            verb in PACKED ||
            verb in DEAD_SUBSCRIPTIONS ||
            verb in TOGGLES ||
            verb in SELF_EVIDENT ||
            verb in BATCHES ||
            verb in KNOWN_ACTIONS
    }

    /**
     * The action surface, named rather than inferred.
     *
     * A prefix rule was tempting and is wrong in both directions: `GET_` is
     * not reliably a small query (`GET_CHILDREN` and `GET_PRESET_SOURCE` are
     * not), and plenty of actions share no prefix at all.
     */
    private val KNOWN_ACTIONS = setOf(
        "SIGNAL", "SEND", "MSG", "WAVE", "AMP", "PRESET",
        "SET_MIRROR_OPACITY", "SET_MIRROR_CLICKTHRU", "SET_MIRROR_ENABLED",
        "SET_MIRROR_INDEPENDENT", "SET_MIRROR_MAXFPS",
        "SET_WATERMARK", "GET_WATERMARK", "SET_DISPLAY_WATERMARK", "GET_DISPLAY_WATERMARK",
        "SET_ALL_FULLSCREEN", "GET_ALL_FULLSCREEN",
        "SET_IDLE_ACTIVE", "GET_IDLE_ACTIVE",
        "SET_DISPLAY_MODE", "GET_DISPLAY_MODE", "GET_MIRROR_MAXFPS",
        "SET_DISPLAY_PRESET", "SET_DISPLAY_STARTUP_PRESET", "SET_DISPLAY_PRESET_DIR",
        "SET_DISPLAY_ORDER", "SET_DISPLAY_TIME_BETWEEN",
        "DISPLAY_NEXT", "DISPLAY_PREV",
        "DISPLAY_PROFILE_SAVE", "DISPLAY_PROFILE_LOAD", "DISPLAY_PROFILE_DELETE",
        "DISPLAY_PROFILE_STARTUP",
        "SET_ALWAYS_ON_TOP", "GET_ALWAYS_ON_TOP", "RAISE_WINDOW", "LOWER_WINDOW",
        "MOVE_TO_DISPLAY",
        "SET_PRESET_ORDER", "SET_PRESET_LOCK", "SET_TIME_BETWEEN_PRESETS",
        "GET_PRESET_CYCLE", "GET_PRESET",
        "SET_PRESET_RATING_FOR", "SET_PRESET_FLAG_FOR", "SET_PRESET_TAG_FOR",
        "COL_HUE", "COL_SATURATION", "COL_BRIGHTNESS", "HUE_AUTO",
        "VAR_TIME", "VAR_INTENSITY", "VAR_QUALITY",
        "FFT_ATTACK", "FFT_DECAY",
        "SET_DEVICE_VOLUME", "SET_DEVICE_MUTE", "GET_DEVICE_VOLUME", "DEVICE",
        "MIXER_SET", "MIXER_MUTE", "MIXER_ORDER_REV", "MIXER_ORDER_MOVE", "MIXER_ORDER_SET",
    )

    /** The verb, with its argument and any relayed payload removed. */
    fun verbOf(command: String): String {
        val head = command.substringBefore('|').substringBefore('=')
        return head.trim().uppercase()
    }

    /**
     * @param parts whether the mixer in use cuts a large reply into parts,
     * which it says with `maxparts` in `MDXM_FWDSTATE`. False is the older
     * mixer and the default: not knowing is treated as not being able to.
     */
    fun fitnessOf(command: String, parts: Boolean = false): RelayFitness {
        // Addressed to one display, carrying another command for it. The outer
        // verb is what travels; what the inner command needs back is the inner
        // command's business, and mdx12 answers `DISPLAY|` with an ack either
        // way.
        if (command.startsWith("DISPLAY|")) return RelayFitness.Action

        return when (val verb = verbOf(command)) {
            in TOO_BIG -> RelayFitness.TooBig
            // Joined, it names every display it describes, so a stranger's is
            // the same truth as this phone's own.
            in IN_PARTS -> if (parts) RelayFitness.SharedAnswer else RelayFitness.TooBig
            in PACKED -> if (parts) RelayFitness.Batch else RelayFitness.TooBig
            in DEAD_SUBSCRIPTIONS -> RelayFitness.DeadSubscription
            in TOGGLES -> RelayFitness.ToggleAction
            in SELF_EVIDENT -> RelayFitness.SharedAnswer
            in BATCHES -> RelayFitness.Batch
            else -> if (verb.startsWith("GET_") || verb.endsWith("_REV")) {
                RelayFitness.SharedAnswer
            } else {
                RelayFitness.Action
            }
        }
    }

    /** Can a tap on this be expected to do what the user meant? */
    fun travels(command: String, parts: Boolean = false): Boolean = when (fitnessOf(command, parts)) {
        RelayFitness.Action,
        RelayFitness.ToggleAction,
        RelayFitness.SharedAnswer,
        // Measured arriving intact, terminator and all. A batch can still be
        // incomplete, which the caller handles; it is not a reason to refuse.
        RelayFitness.Batch,
        -> true

        // Refused by this mixer, with `toolong` or with silence, or not worth
        // putting through any mixer.
        RelayFitness.TooBig,
        RelayFitness.DeadSubscription,
        -> false
    }

    /**
     * Why a screen cannot work through the mixer, for a person to read.
     *
     * Null when it can. Deliberately says what to do about it rather than
     * naming the mechanism: "the reply is too big for the mixer's relay" is
     * true and useless to someone holding a phone.
     */
    fun explain(command: String, parts: Boolean = false): String? = when (fitnessOf(command, parts)) {
        RelayFitness.Action,
        RelayFitness.ToggleAction,
        RelayFitness.SharedAnswer,
        RelayFitness.Batch,
        -> null
        RelayFitness.TooBig ->
            // Two different sentences, because only one of them has a remedy.
            // A reply an up-to-date mixer would carry is the mixer's age, and
            // saying so names what to change; the other is the relay itself.
            if (verbOf(command) in TOO_BIG) {
                "The visualizer's answer to this is too large to come through the mixer."
            } else {
                "The visualizer's answer to this is too large for this version of " +
                    "mdxmixer to pass on."
            }
        RelayFitness.DeadSubscription ->
            "Live updates need the visualizer connected directly."
    }
}
