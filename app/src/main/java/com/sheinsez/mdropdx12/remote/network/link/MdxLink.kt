package com.sheinsez.mdropdx12.remote.network.link

import com.sheinsez.mdropdx12.remote.data.model.ServerIdentity
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.MessageParser
import com.sheinsez.mdropdx12.remote.network.RelayPolicy
import com.sheinsez.mdropdx12.remote.network.vban.Forward
import com.sheinsez.mdropdx12.remote.network.vban.FwdState
import com.sheinsez.mdropdx12.remote.network.vban.MdxmCommands
import com.sheinsez.mdropdx12.remote.network.vban.VbanSession
import com.sheinsez.mdropdx12.remote.network.vban.VbanSessionState
import com.sheinsez.mdropdx12.remote.service.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One MDropDX12 record, and which wire brought it.
 *
 * The carrier is not decoration. A record that arrived over the relay is a
 * BROADCAST -- mdx12 sends every reply to every client it has, so this may be
 * the answer to a command the PC's own UI sent, or another phone's, or an
 * unprompted engine push. A screen that answers a record by issuing more
 * commands must check this first, or one slider drag at the PC turns into a
 * storm of queries whose replies are relayed back to every subscriber.
 */
data class Record(val text: String, val via: Carrier)

/** A command that could not be sent, and what to tell someone about it. */
data class Refusal(val command: String, val why: String)

/**
 * The one object that knows there are two ways to reach the visualizer.
 *
 * Application-scoped, beside the other app-scoped collaborators, because a
 * link has to outlive any screen. It owns neither transport: `ConnectionManager`
 * keeps meaning exactly one TCP socket and `VbanSession` keeps meaning one
 * mixer session, which is what every member of each is about. This is the
 * fork, and nothing else is.
 *
 * It defines no routing table of its own. [RelayPolicy] already classifies the
 * verb surface six ways and [Routing] turns that into a carrier, so a second
 * table here would be a second answer to the same question -- and the two
 * would disagree the moment one of them was updated.
 */
class MdxLink(
    private val session: VbanSession,
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
    /** Whether any screen is in front. See the subscription rule in `init`. */
    private val foreground: StateFlow<Boolean>,
    /**
     * The one reader with no screen: the media session, showing a watch what
     * the PC is playing. See the subscription rule in `init`.
     */
    private val nowPlayingShown: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state

    /**
     * Every MDropDX12 record from either wire, tagged with its carrier.
     *
     * A SharedFlow rather than a StateFlow because these are events: two
     * `PRESET=` pushes conflated into one loses a preset change.
     */
    private val _messages = MutableSharedFlow<Record>(extraBufferCapacity = 256)
    val messages: SharedFlow<Record> = _messages

    /**
     * Published refusals, so a command that cannot travel is never merely
     * dropped.
     *
     * Collected once, high up, rather than handled at each of the hundred-odd
     * call sites -- which is the whole reason `send` stays a one-argument
     * function that cannot fail at the caller.
     */
    private val _unsendable = MutableSharedFlow<Refusal>(extraBufferCapacity = 16)
    val unsendable: SharedFlow<Refusal> = _unsendable

    /** What the PC says it supports, from whichever wire answered. */
    private val _identity = MutableStateFlow(ServerIdentity())
    val identity: StateFlow<ServerIdentity> = _identity

    val fwd: StateFlow<FwdState?> get() = session.fwdState

    /**
     * The link can carry a question AND bring the answer back.
     *
     * This, not [state], is what a screen should wait on before asking. The
     * two settle independently and the order between them matters: a screen
     * that queries the moment the link reports Live can get its answer
     * RELAYED TO NOBODY, because the subscription is set by its own collector
     * and may not have left yet. The preset list did exactly that -- the
     * query went out, sixty-one records came back off the PC, and the phone
     * was not yet a subscriber, so it sat on "0 of 0" with a spinner.
     *
     * Built on `fwdSubAsserted` -- the request having reached the WIRE --
     * rather than on having asked for it. The first attempt used the latter
     * and did not fix the bug, because the want is routinely set before there
     * is a socket to put it on, and the send is silently dropped.
     */
    val ready: StateFlow<Boolean> = MutableStateFlow(false).also { out ->
        scope.launch {
            combine(_state, session.fwdSubAsserted) { s, subbed ->
                s is LinkState.Live && subbed
            }
                .distinctUntilChanged()
                .collect {
                    android.util.Log.i(
                        "MdrLink",
                        "ready=$it (state=${_state.value} subAsserted=${session.fwdSubAsserted.value})",
                    )
                    out.value = it
                }
        }
    }

    init {
        // The link state, from the four things that know something about it.
        scope.launch {
            combine(
                session.state,
                session.fwdState,
                connectionManager.connectionState,
                session.unreachable,
            ) { s, f, tcp, unreachable ->
                LinkStateMachine.derive(s, f, tcp, unreachable)
            }.collect {
                if (it != _state.value) {
                    // Logged on CHANGE only, so it is quiet on a steady link.
                    // This is the state every screen now gates on, and when it
                    // is wrong the symptom is "nothing works" with nothing on
                    // screen saying why -- which is exactly how it presented.
                    android.util.Log.i("MdrLink", "${_state.value} -> $it")
                }
                _state.value = it
            }
        }

        // Find out what the PC is, and what it can forward, as soon as the
        // session can carry the question.
        scope.launch {
            var wasStreaming = false
            session.state.collect { s ->
                val streaming = s == VbanSessionState.Streaming
                if (streaming && !wasStreaming) {
                    session.queryFwdState()
                }
                wasStreaming = streaming
            }
        }

        // Find out what the visualizer is -- ONCE THE ANSWER HAS SOMEWHERE
        // TO GO.
        //
        // Over the relay this is the only way the capability list is ever
        // learned: ConnectionManager provokes it on a TCP connect, and with
        // the visualizer's TCP server off that never happens.
        //
        // It used to be asked the moment the session started streaming,
        // beside the query above, and that is the hazard [ready] documents:
        // the subscription is set by its own collector and may not have left
        // yet, and a mixer with no subscriber relays the answer to nobody.
        // Which of the two went out first was a race, and when this lost it
        // the phone never learned a single feature for the rest of the
        // session -- saved arrangements, the all-screens and screensaver
        // switches and the per-display verbs all quietly absent, on a PC that
        // has every one of them. Seen against the fake mixer: one launch in
        // four sent GET_VERSION ahead of MDXM_FWDSUB|1.
        //
        // Asked again each time the link becomes ready, which is each time a
        // screen comes back to the front. Two small records, and it is how a
        // visualizer updated on the PC is noticed at all.
        scope.launch {
            ready.collect { if (it) session.forward(CommandBuilder.getVersion()) }
        }

        // SUBSCRIBE ONLY WHILE SOMEBODY IS READING, which is what the
        // subscription is for.
        //
        // The relay is a firehose: it carries every reply mdx12 makes to every
        // client, plus unprompted engine pushes, down the same Wi-Fi link that
        // is already carrying about 1.5 Mbit/s of audio. Holding it open from
        // the moment the session starts meant taking all of that while the
        // screen was off and nothing was listening -- the phone playing audio
        // in a pocket has no use for another client's preset listing.
        //
        // Two conditions, and BOTH are needed. `subscriptionCount` alone was
        // tried and is not enough: a ViewModel's collector outlives the home
        // button, so it answers "somebody is reading" about a phone in a
        // pocket -- measured, with `fwd=1` still set on the mixer's peer row
        // after pressing Home. The foreground flag is what actually tracks a
        // screen being in front.
        //
        // Actions never need the subscription at all: MDXM_OK is the
        // acknowledgement, and what a command DID shows up here only if
        // something is watching for it.
        //
        // ONE EXCEPTION, AND IT IS A READER. While the media session shows
        // the stream, a watch or the lock screen is drawing what the PC is
        // playing, and a song change reaches the phone only as a TRACK| push
        // down this relay. Without it the watch would show whatever was
        // playing when the screen last went off, for as long as it stayed
        // off -- which, for a phone in a pocket, is the whole time. The cost
        // is the firehose while audio plays, and its records are small and
        // rare beside ~188 audio packets a second.
        scope.launch {
            combine(_messages.subscriptionCount, foreground, nowPlayingShown) { readers, visible, shown ->
                readers > 0 && (visible || shown)
            }
                .distinctUntilChanged()
                .collect { wanted -> session.setFwdSub(wanted) }
        }

        // A refusal from the PC reaches the same place a local one does. It
        // is the same event from the user's side -- the thing they pressed
        // did not happen -- and the only difference is who decided.
        scope.launch {
            session.pcRefusal.collect { why ->
                // Translated, and only the ones that are news. A permanent
                // limit of the relay belongs where the missing content is --
                // it showed up here as the raw wire word "toolong", over
                // whatever screen happened to be in front.
                if (!MdxmCommands.errIsPermanent(why)) {
                    _unsendable.tryEmit(Refusal("", MdxmCommands.explainErr(why)))
                }
            }
        }

        // Relayed records in, one per record, verbatim.
        //
        // NOT split further, and it would not help if it were. mdx12 packs
        // several records into one pipe message joined with '\n', and the
        // mixer joins its own records for a datagram with '\n' too -- the
        // payload byte and the record separator are the same byte. So when a
        // mixer wraps a whole pipe message into one `msg=`, the TXT payload
        // is already torn apart by the time it reaches here: the first line
        // keeps its MDXM_FWDR prefix and the rest arrive as bare,
        // unattributable records. There is nothing to recover downstream,
        // which is why it was a wire question and was filed as one (mdxmixer
        // #24).
        //
        // A mixer that sends parts settles it at the source: it takes each
        // message apart and relays a line per record, so a `msg=` never holds
        // a newline. Against one that does not, nothing this app sends can
        // reach the torn case, because every verb whose reply is packed that
        // way is refused by RelayPolicy before it takes this wire.
        scope.launch {
            session.relay.collect { record -> emit(record, Carrier.Relay) }
        }

        // TCP records in. Already arrive batched with embedded newlines.
        scope.launch {
            connectionManager.messages.collect { raw ->
                for (line in raw.lineSequence()) {
                    val t = line.trim()
                    if (t.isNotEmpty()) emit(t, Carrier.Tcp)
                }
            }
        }
    }

    /**
     * Suspends rather than dropping, like the relay feeding it.
     *
     * DROP_OLDEST on a 256-record buffer looks safe and is not: a batch
     * arrives as one burst, so what it drops is the front of the very reply
     * somebody is waiting for, and the screen renders a list quietly missing
     * its first rows. A batch protocol has no way to notice that.
     */
    private suspend fun emit(text: String, via: Carrier) {
        MessageParser.parseIdentity(text)?.let { _identity.value = it }
        _messages.emit(Record(text, via))
    }

    /**
     * Send one command to the visualizer by whichever wire can carry it.
     *
     * Cannot fail at the caller, deliberately. The alternative -- a return
     * value every one of the hundred-odd call sites must consume -- was
     * considered and rejected: at that many sites the failure arm gets an
     * empty block, which is the swallowed error this exists to prevent. A
     * refusal goes to [unsendable] instead, where one collector draws it.
     */
    fun send(command: String) {
        when (val r = Routing.route(command, Routing.relayUsable(_state.value), tcpUp(), parts())) {
            is Route.Via -> dispatch(command, r.carrier)?.let {
                android.util.Log.i("MdrLink", "refused locally '$command': ${it.why}")
                _unsendable.tryEmit(Refusal(command, it.why))
            }
            is Route.Refused -> {
                // A refused command is the one failure that leaves no trace
                // on screen beyond a control that did nothing, so it is worth
                // a line naming the verb and the state that refused it.
                android.util.Log.i("MdrLink", "refused '$command' in ${_state.value}: ${r.why}")
                // EVERY refusal is published, because a screen waiting on a
                // reply needs to know its question was never asked -- that is
                // what left a spinner running for ever.
                //
                // Which of them deserve a BANNER is a separate question,
                // answered where the banner is drawn. A permanent transport
                // limit is a property of one screen and belongs where its
                // content would be; put on a global banner it appears over
                // whatever happens to be in front, which is how the Displays
                // tab's DIAG_MIRRORS ended up reading as the preset list
                // having failed.
                _unsendable.tryEmit(Refusal(command, r.why))
            }
        }
    }

    /**
     * Send, and hand the refusal back rather than publishing it.
     *
     * For the two places a person typed the command themselves -- the raw
     * command box and a user-authored button -- where the answer belongs next
     * to what they typed.
     */
    fun offer(command: String): Refusal? {
        return when (val r = Routing.route(command, Routing.relayUsable(_state.value), tcpUp(), parts())) {
            is Route.Via -> {
                dispatch(command, r.carrier)?.let { return Refusal(command, it.why) }
                null
            }
            is Route.Refused -> Refusal(command, r.why)
        }
    }

    /** Null on success; the local refusal if the relay would not take it. */
    private fun dispatch(command: String, carrier: Carrier): Forward.Refused? = when (carrier) {
        Carrier.Tcp -> {
            connectionManager.send(command)
            null
        }
        Carrier.Relay -> session.forward(command)
    }

    private fun tcpUp() =
        connectionManager.connectionState.value ==
            com.sheinsez.mdropdx12.remote.network.ConnectionState.Connected

    /**
     * Whether THIS mixer carries a reply too large for one packet.
     *
     * It says so with `maxparts` in `MDXM_FWDSTATE`, and one that says
     * nothing answers `toolong` instead. Read at the moment of sending rather
     * than cached: the state record comes back on every ping tick while a
     * screen is in front, so a mixer replaced by a newer build is believed
     * within a tick, with nothing here to invalidate.
     */
    private fun parts() = (session.fwdState.value?.maxParts ?: 0) > 0

    /** [parts], for a screen that has to wait for it. */
    val carriesLargeReplies: Flow<Boolean> =
        session.fwdState.map { (it?.maxParts ?: 0) > 0 }.distinctUntilChanged()

    /** Whether a command typed by hand is one this app knows the shape of. */
    fun isKnown(command: String): Boolean = RelayPolicy.isKnown(command)

    /**
     * Would an ordinary command travel right now?
     *
     * The gate screens and services should ask, in place of
     * `ConnectionState == Connected`. That test meant "can I reach the
     * visualizer" everywhere it was used, and once the visualizer's own server
     * is switched off it answers no for ever while every button works.
     *
     * Verb-independent on purpose: it is for enabling a control, not for
     * routing one. A command that needs a batch reply is still refused with a
     * sentence by [send].
     */
    val canSend: Boolean get() = Routing.relayUsable(_state.value) || tcpUp()
}
