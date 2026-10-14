"""A fake mdxmixer that forwards commands to a fake MDropDX12.

WHY THIS EXISTS. mdxmixer's forwarding design is built as far as task 7 of 14:
the record-slicing helper and the VBAN peer grant are in, but `MDXM_FWD|cmd=`
is dispatched nowhere and its pipe client to mdx12 is discovery-only. So the
real server cannot execute a forwarded command, and the phone's half of the
protocol has nothing to answer it.

This answers it. Hand-rolled from
`mdxmixer/docs/specs/2026-10-08-ipc-forwarding-design.md` §4.2-4.5 and checked
against the real `ChunkReplies`, so a client that works here is working against
the contract rather than against a mock shaped to agree with it.

IT IS DELIBERATELY HOSTILE. Everything a naive client gets wrong is something
this does on purpose:

  * relayed payloads carry `|` and `=`, so a client that splits an
    `MDXM_FWDR` on `|` shreds it (§4.1);
  * the relay is a firehose -- it pushes replies to commands a PHANTOM OTHER
    CLIENT sent, and unprompted engine pushes, so a client that treats the
    next record as "the answer to what I just asked" misreads them (§4.4);
  * peers expire after 10 s without a PING0 and `fwdSub` dies with them, so a
    client that subscribes once and assumes forever goes quiet (§7.2);
  * `MDXM_OK` means queued, never executed (§4.2), and with `--lose` some
    commands are accepted and then dropped on the floor, which is what UDP and
    a bounded queue actually do.

Usage:
    python tools/fake_mdxmixer.py                       # 0.0.0.0:6985
    python tools/fake_mdxmixer.py --port 6985 --pin <the phone's PIN>
    python tools/fake_mdxmixer.py --off                 # fwd.enabled = 0
    python tools/fake_mdxmixer.py --notarget            # mdx12 not running
    python tools/fake_mdxmixer.py --no-expiry           # keep peers forever
    python tools/fake_mdxmixer.py --lose 3              # drop every 3rd forward
    python tools/fake_mdxmixer.py --quiet-foreign       # no phantom client
    python tools/fake_mdxmixer.py --no-parts            # a mixer before MDXM_FWDP
    python tools/fake_mdxmixer.py --lose-part 2         # lose a part of every 2nd cut record

IT IS TWO MIXERS. A reply too large for one packet -- DIAG_MIRRORS is the one
that matters, about 2.7 KB with three displays -- is cut into numbered
MDXM_FWDP parts by the current mixer and answered `MDXM_ERR|msg=toolong` by
the one before it (`--no-parts`). Both are in the field, MDXM_FWDSTATE's
`maxparts` is how a client tells them apart, and a client has to be right
against each.

IT ALSO ANSWERS FOR THE MIXER ITSELF: `MDXM_STATE`, and `MDXM_SET`,
`MDXM_MUTE`, `MDXM_DEVVOL` and `MDXM_DEVMUTE` against it. That is what the
phone's Mixer screen reads, and it is the way to try that screen without a
single real fader moving -- point the phone's stream port at this and every
level on it is make-believe. See FakeMixerState.

Nothing here is a model of mdxmixer's internals -- only of its wire.
"""
import argparse
import socket
import struct
import sys
import threading
import time

HEADER = 28
MAX_DATA = 1436               # vban_protocol.cpp kMaxData
MAX_RELAY_PARTS = 16          # vban_protocol.h kMaxRelayParts
# What one part carries: a packet, less the longest header a part can have.
# Sized for the worst case, as the real one is, so the count is known before
# the first part is written (vban_protocol.h kRelayPartData).
RELAY_PART_DATA = MAX_DATA - len("MDXM_FWDP|id=4294967295|i=15|n=16|msg=")
PROTO_AUDIO, PROTO_TXT, PROTO_SERVICE, PROTO_FRAME = 0x00, 0x40, 0x60, 0x80
PROTO_MASK = 0xE0
TXT_UTF8 = 0x10
SERVICE_IDENTIFICATION = 0
SERVICE_FN_PING0 = 0
SERVICE_REPLY_BIT = 0x80
PING0_PAYLOAD = 676
FEATURE_AUDIO, FEATURE_FRAME, FEATURE_TXT = 0x1, 0x1000, 0x10000

EXPIRY_S = 10.0               # kExpiryMs
STREAM_NAME = "mdxmixer"


# ── the wire ──────────────────────────────────────────────────────────────────

def build_header(proto, sr, nbs, nbc, bit, name, nuframe):
    out = bytearray(HEADER)
    out[0:4] = b"VBAN"
    out[4] = (proto & PROTO_MASK) | (sr & 0x1F)
    out[5] = nbs & 0xFF
    out[6] = nbc & 0xFF
    out[7] = bit & 0xFF
    enc = name.encode("ascii")[:16]
    out[8:8 + len(enc)] = enc
    struct.pack_into("<I", out, 24, nuframe & 0xFFFFFFFF)
    return bytes(out)


def parse_packet(buf):
    if len(buf) < HEADER or buf[0:4] != b"VBAN":
        return None
    sr = buf[4]
    return {
        "proto": sr & PROTO_MASK,
        "nbs": buf[5], "nbc": buf[6], "bit": buf[7],
        "name": bytes(buf[8:24]).split(b"\0")[0].decode("ascii", "replace"),
        "nuFrame": struct.unpack_from("<I", buf, 24)[0],
        "data": bytes(buf[HEADER:]),
    }


def identification_payload(device, app, host):
    """The 676-byte PING0 reply, at the offsets VbanCodec.kt reads.

    Those offsets were wrong in the Kotlin by four bytes until a real server
    disagreed with it, which is the whole argument for probing rather than
    reasoning -- so they are reproduced here exactly and not recomputed.
    """
    d = bytearray(PING0_PAYLOAD)
    struct.pack_into("<I", d, 0, 0x00000001)                               # bitType
    struct.pack_into("<I", d, 4, FEATURE_AUDIO | FEATURE_TXT | FEATURE_FRAME)
    struct.pack_into("<I", d, 12, 48000)                                   # preferred
    struct.pack_into("<I", d, 16, 48000)                                   # min
    struct.pack_into("<I", d, 20, 48000)                                   # max

    def put(off, text, width=64):
        enc = text.encode("ascii", "replace")[:width - 1]
        d[off:off + len(enc)] = enc

    put(164, device)
    put(292, app)
    put(356, host)
    return bytes(d)


def chunk_replies(records):
    """`vban::ChunkReplies` (vban_protocol.cpp:184), reproduced exactly.

    A record is NEVER split: records are joined with '\\n' up to kMaxData, and
    one too long to fit becomes an error rather than a fragment the far end
    cannot parse. A client may therefore split a TXT payload on '\\n' and trust
    every line to be a whole record -- which is the only reason the relay is
    parseable at all.
    """
    out, current, current_bytes = [], "", 0
    for rec in records:
        rec_bytes = len(rec.encode("utf-8"))
        if rec_bytes > MAX_DATA:
            if current:
                out.append(current)
                current, current_bytes = "", 0
            out.append("MDXM_ERR|msg=toolong")
            continue
        joined = rec_bytes if not current else current_bytes + 1 + rec_bytes
        if current and joined > MAX_DATA:
            out.append(current)
            current, current_bytes = "", 0
        if current:
            current += "\n"
            current_bytes += 1
        current += rec
        current_bytes += rec_bytes
    if current:
        out.append(current)
    return out


def split_records(message):
    """`mw::SplitRecords` (ipc/milkwave_client.cpp), reproduced.

    mdx12 often writes several records into ONE pipe message, joined with
    newlines -- MIXER_STATE, MIXER_VIEW, MIXER_ORDER and DISPLAY_PROFILE_LIST
    all arrive that way. Only the line ending comes off, a CRLF's CR with it,
    and a blank line is dropped rather than relayed empty.
    """
    out = []
    for line in message.split("\n"):
        if line.endswith("\r"):
            line = line[:-1]
        if line:
            out.append(line)
    return out


def relay_records(record, ident):
    """`vban::RelayRecords` (net/vban_protocol.cpp), reproduced.

    One mdx12 record, as what crosses the wire. One that fits a packet is a
    single MDXM_FWDR. One that does not is cut into numbered parts,

        MDXM_FWDP|id=<n>|i=<k>|n=<N>|msg=<slice>

    each a whole record that fits. The cut is by CODE POINT, counting the
    bytes each becomes, so every part is valid UTF-8 on its own -- a client
    decodes a datagram as it arrives, before it knows there is a next one.
    Past MAX_RELAY_PARTS the answer is still `toolong`.
    """
    if not record:
        return []
    whole = "MDXM_FWDR|msg=" + record
    if len(whole.encode("utf-8", "replace")) <= MAX_DATA:
        return [whole]
    slices, current, used = [], "", 0
    for ch in record:                    # a str iterates by code point
        size = len(ch.encode("utf-8", "replace"))
        if used + size > RELAY_PART_DATA:
            slices.append(current)
            current, used = "", 0
        current += ch
        used += size
    slices.append(current)
    if len(slices) > MAX_RELAY_PARTS:
        return ["MDXM_ERR|msg=toolong"]
    return ["MDXM_FWDP|id=%d|i=%d|n=%d|msg=%s" % (ident, k, len(slices), s)
            for k, s in enumerate(slices)]


# ── the fake mdx12 behind the relay ──────────────────────────────────────────

class FakeMdx12:
    """Only as much visualizer as makes the relay's shape visible.

    The point is not to emulate 301 verbs. It is to answer in mdx12's three
    different framings -- END_BATCH, per-verb `*_END` sentinels, and indexed
    chunks -- because reproducing none of them is exactly what the relay does,
    and the client has to cope.
    """

    PRESETS = [
        "Geiss - Cruise for a Bruise",
        "Rovastar - Altars of Madness",
        "Flexi - mindblob",
        "martin - mandelbox explorer",
    ]

    # Three displays, laid out as the rig's are: a landscape pair stacked to
    # the right of a portrait panel, which is also where the render window is.
    DISPLAYS = [
        (1, "(0,-1440)-(2560,0) 2560x1440", 0),
        (2, "(-2160,-1165)-(0,2675) 2160x3840", 1),
        (3, "(0,0)-(2560,1440) 2560x1440", 0),
    ]

    def __init__(self):
        self.preset = 0
        self.modes = {1: "mirror", 2: "preset", 3: "off"}
        # Named by timestamp, because that is how the PC names and sorts them.
        self.profiles = ["2026-09-%02d_%02d-%02d-38" % (1 + i % 28, 8 + i % 12, i % 60)
                         for i in range(82)]
        self.profiles.sort()
        self.startup_file = self.profiles[-1] + ".json"
        self.startup_on = True

    def mirrors(self):
        """DIAG_MIRRORS: ONE record, and far too large for a packet.

        Shaped on the real reply -- 2726 bytes with three displays attached,
        most of it render diagnostics no remote reads -- because its size is
        the whole point: it is the record that parts exist for.
        """
        surf = ("surf=\\\\.\\DISPLAY%d,own=1,preset=%s.milk,renders=classic,"
                "size=2560x1440,ready=1,frames=160921,pub=0,gate=0,pipeRdy=1,"
                "need=2560x1440,seenPf=5677398,try=1664353,rec=1664353,shaders=1,"
                "fb=1,simFps=22.0,pf=160922,ovr=160922,simTime=60977.477,"
                "bindBase=2237,parityMissed=1746309,workUs=1723,sub=160922,"
                "done=160922,prims=55756,psInv=9751177,overdraw=2.6,gpuUs=148")
        mon = ("mon%d=\\\\.\\DISPLAY%d,enabled=1,opacity=100,clickthru=0,watermark=0,"
               "alwaysontop=0,independent=0,skipped=%d,ownProcess=0,childOwns=0,"
               "display=%s,portrait=%d,ready=1,softDis=1,hwnd=00000000010A16FA,"
               "swapsize=2560x1440,bufs=2,rtvBase=80,winrect=%s,visible=0,layered=0,"
               "trans=0,alpha=100,styleok=1,placeok=1,path=orient,drawFI=1,presFI=1,"
               "presHr=0x00000000,ok=160918,fail=0,skip=0,draws=160918,mustBlock=0,"
               "oppIndep=1,wipe=0,paintMask=0x3,everPres=1,bb0=COMMON,bb1=COMMON")
        parts = ["MIRRORS", "active=0", "independent=1", "aot=0", "watermark=0",
                 surf % (1, self.PRESETS[0]), surf % (3, self.PRESETS[1]),
                 "main=0x0,mainPort=0,needSrv=0,canSample=0,anyOpp=0,surfaces=2,"
                 "orientFps=0.0,mirrorFps=0.0,maxFps=0,peakGpuUs=7371",
                 "pace_ms=orientDt(min=45.3,avg=45.5,max=45.6),lock(avg=28.1,max=28.7)",
                 "render_on=\\\\.\\DISPLAY2,visible=1,revealed=1,"
                 "renderwin=(-2160,-1165)-(0,2675) 2160x3840,opacity=1.00,fs=1,clickthru=0"]
        for i, (n, rect, portrait) in enumerate(self.DISPLAYS):
            parts.append(mon % (i, n, 1 if n == 2 else 0, rect, portrait, rect))
        return "|".join(parts)

    def startup(self):
        return "DISPLAY_PROFILE_STARTUP|file=%s|enabled=%d" % (
            self.startup_file, 1 if self.startup_on else 0)

    def handle(self, raw):
        """mdx12's replies to one command: its pipe MESSAGES, in order.

        A message is usually one record. It is sometimes many, joined with
        newlines, and whether the relay takes those apart is one of the two
        things a mixer either does or does not do. May be empty.
        """
        cmd = raw.strip()
        up = cmd.upper()

        if up == "DIAG_MIRRORS":
            return [self.mirrors()]

        if up == "DISPLAY_PROFILE_LIST":
            # PACKED, as mdx12 packs it: every row in one message.
            return ["\n".join("DISPLAY_PROFILE|file=%s.json|name=%s" % (p, p)
                              for p in self.profiles)]

        if up == "DISPLAY_PROFILE_STARTUP":
            return [self.startup()]

        if up.startswith("DISPLAY_PROFILE_STARTUP="):
            # An empty name switches startup loading off.
            name = cmd.split("=", 1)[1].strip()
            self.startup_file = name
            self.startup_on = bool(name)
            return [self.startup()]

        if up in ("NEXT", "NEXT_PRESET"):
            self.preset = (self.preset + 1) % len(self.PRESETS)
            return [self._preset_push()]
        if up in ("PREV", "PREV_PRESET"):
            self.preset = (self.preset - 1) % len(self.PRESETS)
            return [self._preset_push()]

        if up == "GET_VERSION":
            # The real reply's shape, because the app gates every optional
            # screen on the feature list in it: a fake that answered in its
            # own words left those screens quietly absent rather than broken.
            # It carries '=' and ',' inside a field value, so a client that
            # re-splits the relayed record loses the list.
            return [
                "VERSION=3.3.0|build=fake|config=Release|arch=x64|protocol=11"
                "|pid=4242|child=0|commands=301|script=56|signals=21"
                "|features=displayprofiles,allfullscreen,idleactive,mirrorwatermark,"
                "windowzorder,displaywatermark,displaytiers",
                "END_BATCH",
            ]

        if up == "GET_DISPLAY_MODE":
            # One record naming every display, then its own terminator.
            return ["DISPLAY_MODE|" + "|".join(
                "%d=%s,device=\\\\.\\DISPLAY%d,auto=0,independent=0" % (n, m, n)
                for n, m in sorted(self.modes.items())) + "|END"]

        if up.startswith("SET_DISPLAY_MODE="):
            arg = cmd.split("=", 1)[1]
            try:
                num, mode = arg.split(",", 1)
                self.modes[int(num)] = mode.strip()
                # Only the `DISPLAY_MODE=` prefix is known, from the app
                # watching for it; what follows is a placeholder.
                return ["DISPLAY_MODE=%d,%s" % (int(num), mode.strip())]
            except ValueError:
                return ["ERR|BADARG|%s" % arg]

        if up.startswith("DISPLAY|"):
            return ["DISPLAY_ACK|%s" % cmd.split("|", 1)[1]]

        if up in ("RAISE_WINDOW", "LOWER_WINDOW"):
            return ["WINDOW_Z|%s" % ("front" if up == "RAISE_WINDOW" else "back")]

        if up.startswith("PRESET_ROWS"):
            # The bulk reply the spec says not to put through the relay (§2).
            # Answered anyway, so a client can SEE what arriving incomplete
            # looks like instead of being told about it.
            rows = []
            for i, name in enumerate(self.PRESETS):
                rows.append("PRESET_ROW|i=%d|name=%s|path=C:\\presets\\%s.milk|rating=0"
                            % (i, name, name.replace(" ", "_")))
            return rows + ["PRESET_ROWS_END|total=%d" % len(self.PRESETS)]

        if up == "GET_PRESET":
            return [self._preset_push()]

        if up == "STATE":
            # The parts of mdx12's answer the phone reads, in its order
            # (engine_messages.cpp, the STATE branch): opacity, the preset,
            # then the track -- which is what the media session asks for, so
            # a watch shown mid-song is not left on "PC audio".
            return ["OPACITY=100", self._preset_push(),
                    "TRACK|artist=Fake Artist|title=Fake Song|album=Fake Album"]

        # mdx12 treats unknown text as a script line and, failing that, draws
        # it on the visualizer. It does NOT answer (§2, no STRICT handshake).
        return []

    def _preset_push(self):
        return "PRESET=%s" % self.PRESETS[self.preset]

    def idle_push(self):
        """Unprompted engine traffic, which the relay carries too."""
        return "IDLE_TIMER=%d" % int(time.time() % 600)


# ── the mixer's own channels and devices ─────────────────────────────────────

def fmt_num(v):
    """`FmtNum` (ipc/protocol.cpp:10): `%g`, so 0.85 and not 0.850000."""
    return "%g" % v


class FakeMixerState:
    """`MDXM_STATE` and the four verbs that write to it.

    The phone's Mixer screen reads this, not MDropDX12, so it has to be
    answerable with no visualizer behind the relay at all -- `--notarget` and
    this still work together, which is the case the screen exists for.

    Record and field order are `ChannelRecord` and `DeviceRecord` in
    mdxmixer's ipc/protocol.cpp. The contents are invented, and chosen to be
    awkward in the ways the real list is:

      * enough devices that the reply spans several datagrams, so a client
        that cannot assemble across them shows nothing;
      * Sonar's own endpoints, which the mixer's window hides by NAME and
        sends no flag for;
      * a device the user filed away (`hidden=1`), a headset that is paired
        but switched off (`active=0`, and a stale battery figure beside it),
        and a capture endpoint -- none of which is a row to draw;
      * `sonar:masters`, which refuses a mute.
    """

    def __init__(self):
        # [id, name, pvol, pmute, svol, smute]
        self.channels = [
            ["sonar:game", "Game", 0.13, 0, 1.0, 0],
            ["sonar:chatRender", "Chat", 0.29, 0, 0.5, 1],
            ["sonar:media", "Media", 0.04, 0, 0.8, 0],
            ["sonar:aux", "Aux", 0.4, 1, 1.0, 0],
            ["sonar:chatCapture", "Mic", 1.0, 0, 1.0, 0],
            ["sonar:masters", "Master", 1.0, 0, 1.0, 0],
        ]
        # [id, name, alias, render, default, vol, mute, active, battery, seen,
        #  handsfree, hidden, pinned]
        self.devices = []
        self._dev("a1", "Headphones (Test Buds One)", "Buds One", 1, 1, 0.42, 0, 1, 80, 0, 0, 1)
        self._dev("a2", "Headset (Test Buds One Hands-Free)", "", 1, 0, 1.0, 0, 1, 80, 1, 0, 0)
        self._dev("a3", "Headphones (Test Buds Two)", "Buds Two", 1, 0, 0.6, 0, 0, 1, 0, 0, 0)
        self._dev("a4", "Speakers (Desk Audio)", "", 1, 0, 0.25, 1, 1, -1, 0, 0, 0)
        self._dev("a5", "Monitor (Display Audio)", "", 1, 0, 1.0, 0, 1, -1, 0, 1, 0)
        self._dev("a6", "Microphone (Desk Mic)", "", 0, 1, 0.9, 0, 1, -1, 0, 0, 0)
        for i, ch in enumerate(("Gaming", "Chat", "Media", "Aux", "Microphone",
                                "Stream", "Alerts", "Game Chat")):
            self._dev("b%d" % i,
                      "SteelSeries Sonar - %s (SteelSeries Sonar Virtual Audio Device)" % ch,
                      "", 0 if ch == "Microphone" else 1, 0, 1.0, 0, 1, -1, 0, 0, 0)
        for i in range(6):
            self._dev("c%d" % i, "Line (Virtual Cable %d)" % i, "", 1, 0, 1.0, 0, 0, -1, 0, 0, 0)

    def _dev(self, tag, name, alias, render, default, vol, mute, active, battery,
             handsfree, hidden, pinned):
        ident = "{0.0.0.00000000}.{00000000-0000-0000-0000-0000000000%s}" % tag
        self.devices.append([ident, name, alias or name, render, default, vol, mute,
                             active, battery, "2026-10-11 16:07", handsfree, hidden, pinned])

    def channel_record(self, c):
        return ("MDXM_CHAN|id=%s|name=%s|health=ok|pvol=%s|pmute=%d|svol=%s|smute=%d"
                "|eq=0|peak=-1|idle=1" % (c[0], c[1], fmt_num(c[2]), c[3], fmt_num(c[4]), c[5]))

    def device_record(self, d):
        return ("MDXM_DEVLVL|id=%s|name=%s|alias=%s|flow=%s|default=%d|vol=%s|mute=%d"
                "|active=%d|battery=%d|seen=%s|handsfree=%d|hidden=%d|pinned=%d"
                "|container={c0000000-0000-0000-0000-000000000000}|bt=|peak=-1"
                % (d[0], d[1], d[2], "render" if d[3] else "capture", d[4], fmt_num(d[5]),
                   d[6], d[7], d[8], d[9], d[10], d[11], d[12]))

    def state(self):
        """Channels, routes, the raw endpoint list, then the levelled one."""
        out = ["MDXM_BEGIN"]
        out += [self.channel_record(c) for c in self.channels]
        out.append("MDXM_ROUTE|id=personal|device=%s" % self.devices[0][0])
        out += ["MDXM_DEV|id=%s|name=%s|flow=%s|active=%d"
                % (d[0], d[1], "render" if d[3] else "capture", d[7]) for d in self.devices]
        out += [self.device_record(d) for d in self.devices]
        out.append("MDXM_END")
        return out

    def _channel(self, ident):
        return next((c for c in self.channels if c[0] == ident), None)

    def _device(self, ident):
        return next((d for d in self.devices if d[0] == ident), None)

    def handle(self, record):
        """The reply to one record, or None when the verb is not one of these.

        Positional, as the real parser reads them: a field with no '=' is an
        argument, in order. The error texts are protocol.cpp's own.
        """
        parts = record.split("|")
        verb, a = parts[0], parts[1:]

        if verb == "MDXM_STATE":
            return self.state()

        if verb in ("MDXM_SET", "MDXM_MUTE"):
            if len(a) != 3:
                return ["MDXM_ERR|msg=%s wants <ch>|<personal|streaming>|<%s>"
                        % (verb, "0..1" if verb == "MDXM_SET" else "0|1")]
            if a[1] not in ("personal", "streaming"):
                return ["MDXM_ERR|msg=bad mix: %s" % a[1]]
            c = self._channel(a[0])
            if verb == "MDXM_SET":
                try:
                    vol = min(1.0, max(0.0, float(a[2])))
                except ValueError:
                    return ["MDXM_ERR|msg=bad volume: %s" % a[2]]
                if c is None:
                    return ["MDXM_ERR|msg=unknown channel: %s" % a[0]]
                c[2 if a[1] == "personal" else 4] = vol
            else:
                if a[2] not in ("0", "1"):
                    return ["MDXM_ERR|msg=bad mute flag: %s" % a[2]]
                # Sonar's master mute rewrites every channel's mute, so the
                # mixer refuses it. A client that offers the control anyway
                # finds out here.
                if c is None or c[0] == "sonar:masters":
                    return ["MDXM_ERR|msg=unknown channel: %s" % a[0]]
                c[3 if a[1] == "personal" else 5] = int(a[2])
            # The echo carries the value asked for (ipc.md §3).
            return [self.channel_record(c)]

        if verb == "MDXM_DEVVOL":
            if len(a) != 2:
                return ["MDXM_ERR|msg=MDXM_DEVVOL wants <endpointId>|<0..1>"]
            try:
                vol = min(1.0, max(0.0, float(a[1])))
            except ValueError:
                return ["MDXM_ERR|msg=bad volume: %s" % a[1]]
            d = self._device(a[0])
            if d is None:
                return ["MDXM_ERR|msg=unknown endpoint: %s" % a[0]]
            d[5] = vol
            return ["MDXM_OK"]

        if verb == "MDXM_DEVMUTE":
            if len(a) != 2:
                return ["MDXM_ERR|msg=MDXM_DEVMUTE wants <endpointId>|<0|1>"]
            if a[1] not in ("0", "1"):
                return ["MDXM_ERR|msg=bad mute flag: %s" % a[1]]
            d = self._device(a[0])
            if d is None:
                return ["MDXM_ERR|msg=unknown endpoint: %s" % a[0]]
            d[6] = int(a[1])
            return ["MDXM_OK"]

        return None


# ── peers ────────────────────────────────────────────────────────────────────

class Peer:
    __slots__ = ("key", "auth", "fwd_sub", "audio", "last_ping", "tx_nuframe",
                 "device", "pending_since")

    def __init__(self, key):
        self.key = key
        self.auth = "none"          # none | pending | authorized | denied
        self.fwd_sub = False
        self.audio = False
        self.last_ping = time.time()
        self.tx_nuframe = 0
        self.device = ""
        self.pending_since = 0.0


class FakeMixer:
    def __init__(self, args):
        self.args = args
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # NO SO_REUSEADDR, deliberately. On Windows two UDP sockets that both
        # set it can bind the same port, and arriving datagrams then go to one
        # of them arbitrarily -- which looks exactly like a server that answers
        # intermittently. A loud "address in use" is the better failure, and
        # the default port below is not the real mixer's for the same reason.
        self.sock.bind((args.host, args.port))
        self.lock = threading.Lock()
        self.peers = {}
        self.mdx = FakeMdx12()
        self.mixer = FakeMixerState()
        self.sent = 0
        self.received = 0
        self.queued = 0
        self.dropped = 0
        self.forward_n = 0
        # One per relayed record, cut or not, as VbanServer::m_fwdPartId is.
        self.part_id = 0
        self.cut_n = 0
        self.running = True

    # -- sending ----------------------------------------------------------

    def _send_txt_locked(self, peer, records):
        for chunk in chunk_replies(records):
            pkt = build_header(PROTO_TXT, 0, 0, 0, TXT_UTF8,
                               STREAM_NAME, peer.tx_nuframe) + chunk.encode("utf-8")
            peer.tx_nuframe = (peer.tx_nuframe + 1) & 0xFFFFFFFF
            self.sock.sendto(pkt, peer.key)

    def reply(self, peer, records):
        if not records:
            return
        with self.lock:
            self._send_txt_locked(peer, records)
        self.log("  -> %s:%d  %s" % (peer.key[0], peer.key[1], " ; ".join(records)))

    def wire_for(self, messages):
        """mdx12's pipe messages, as the records that cross the wire.

        No batching, no correlation, no re-framing -- those are mdx12's
        semantics and the subscriber's problem (§4.4). What a mixer does do
        is decide what a packet can carry, and there are two mixers:

        THE CURRENT ONE takes each message apart, relays a line per
        MDXM_FWDR, and cuts a line too large for a packet into MDXM_FWDP
        parts. It says so with `maxparts` in MDXM_FWDSTATE.

        THE OLDER ONE (--no-parts) wraps each message whole. One over 1422
        bytes becomes `toolong`, and a smaller packed one arrives with its
        first line prefixed and the rest bare -- which is not a simplification
        here, it is what that mixer puts on the wire.
        """
        if self.args.no_parts:
            return ["MDXM_FWDR|msg=%s" % m for m in messages]
        wire = []
        for message in messages:
            for record in split_records(message):
                recs = relay_records(record, self.part_id)
                self.part_id = (self.part_id + 1) & 0xFFFFFFFF
                if len(recs) > 1:
                    self.cut_n += 1
                    if self.args.lose_part and self.cut_n % self.args.lose_part == 0:
                        # One packet of a cut record never arrives, which is
                        # what a part costs: nothing is sent twice, so the
                        # whole record is lost with it.
                        self.log("  !! dropped part 1 of %d (id %d)"
                                 % (len(recs), self.part_id - 1))
                        del recs[1]
                wire += recs
        return wire

    def push_relay(self, messages):
        """Relay mdx12's messages to every subscribed peer, in order."""
        if not messages:
            return 0
        with self.lock:
            entitled = [p for p in self.peers.values()
                        if p.auth == "authorized" and p.fwd_sub]
            if not entitled:
                # Nobody asked, so build nothing: no id is spent on it.
                return 0
            wire = self.wire_for(messages)
            for p in entitled:
                self._send_txt_locked(p, wire)
            self.sent += len(wire) * len(entitled)
        shown = " ; ".join(r if len(r) <= 160 else r[:157] + "..." for r in wire[:6])
        self.log("  ~> relay x%d  %d record(s)  %s%s"
                 % (len(entitled), len(wire), shown, "" if len(wire) <= 6 else " ; ..."))
        return len(entitled)

    # -- receiving --------------------------------------------------------

    def peer_for(self, key):
        p = self.peers.get(key)
        if p is None:
            p = Peer(key)
            self.peers[key] = p
            self.log("  + peer %s:%d" % key)
        return p

    def on_service(self, key, pkt):
        p = self.peer_for(key)
        p.last_ping = time.time()
        payload = identification_payload("FakeMixer", "fake_mdxmixer", socket.gethostname())
        out = build_header(PROTO_SERVICE, 0,
                           SERVICE_FN_PING0 | SERVICE_REPLY_BIT,
                           SERVICE_IDENTIFICATION, 0,
                           "VBAN Service", pkt["nuFrame"]) + payload
        self.sock.sendto(out, key)

    def fwd_state(self):
        """§4.3. Field order is the contract: appended, never inserted, and
        the two free-text fields last so a reader that stops at an unknown key
        has already seen every machine-readable value."""
        with self.lock:
            subs = sum(1 for p in self.peers.values()
                       if p.auth == "authorized" and p.fwd_sub)
        target = not self.args.notarget
        # `maxparts` is the newest machine-readable field, so it is the last
        # of them and still ahead of the free text. A mixer that predates
        # parts does not send it at all, and that absence is the signal.
        maxparts = "" if self.args.no_parts else "|maxparts=%d" % MAX_RELAY_PARTS
        return ("MDXM_FWDSTATE|enabled=%d|targetExe=mdropdx12.exe|pid=%d|connected=%d"
                "|launching=0|autolaunch=0|learned=%d|subs=%d|queued=%d|dropped=%d"
                "|sent=%d|received=%d%s|path=%s|lastError=%s"
                % (0 if self.args.off else 1,
                   4242 if target else 0,
                   1 if target else 0,
                   1 if target else 0,
                   subs, self.queued, self.dropped, self.sent, self.received,
                   maxparts,
                   "C:\\Program Files\\MDropDX12\\mdropdx12.exe" if target else "",
                   ""))

    def on_record(self, peer, record, raw_payload):
        verb = record.split("|", 1)[0]

        if verb == "MDXM_AUTH":
            # The exact replies vban_server.cpp:475-544 sends. Spelled out
            # rather than paraphrased, because MdxmCommands.parseAuthState
            # keys on `ok=`/`pending=`/`err=` and a fake that invented
            # `state=ok` would simply never authorize the phone -- a protocol
            # disagreement wearing the costume of a broken client.
            fields = dict(f.split("=", 1) for f in record.split("|")[1:] if "=" in f)
            peer.device = fields.get("device", "")
            if not peer.device:
                self.reply(peer, ["MDXM_AUTHSTATE|ok=0|err=nodevice"])
                return
            if fields.get("pin", "") != self.args.pin:
                peer.auth = "denied"
                self.reply(peer, ["MDXM_AUTHSTATE|ok=0|err=badpin"])
                return
            if self.args.pending and peer.auth != "authorized":
                # Right PIN, unknown device: the PC has been asked. The phone
                # re-sends AUTH every 2 s, and this flips once the wait is up,
                # which is what a human clicking Approve looks like from here.
                if peer.auth != "pending":
                    peer.auth = "pending"
                    peer.pending_since = time.time()
                    self.reply(peer, ["MDXM_AUTHSTATE|pending=1"])
                    return
                if time.time() - peer.pending_since < self.args.pending:
                    self.reply(peer, ["MDXM_AUTHSTATE|pending=1"])
                    return
                self.log("  = approved %s" % peer.device)
            peer.auth = "authorized"
            self.reply(peer, ["MDXM_AUTHSTATE|ok=1"])
            return

        if (peer.auth == "none" and self.args.trust_loopback
                and peer.key[0].startswith("127.")):
            # `vban.trustLoopback`: a sender on the mixer's own machine needs
            # no PIN. It is what lets a PC-side probe with no PIN exchange in
            # it -- ai-tools' vban_relay.py -- read this wire as a phone would.
            peer.auth = "authorized"

        if peer.auth != "authorized":
            # Pre-auth senders cannot reach the relay (§10.2) -- and are told
            # NOTHING. The real mixer drops an unapproved peer's records in
            # silence; the word "unauthorized" appears nowhere in its source.
            #
            # This used to answer `MDXM_ERR|msg=unauthorized`, and the
            # difference is not cosmetic. The phone treats any TXT reply as
            # proof it is still approved, because from the real mixer it is,
            # and re-sends MDXM_AUTH only when replies stop. A mixer
            # restarted under a running phone therefore recovers by itself --
            # and against the invented error it never did, the phone being
            # told every two seconds that somebody was still there.
            self.log("  (dropped, unauthorized)  %s" % record.split("|", 1)[0])
            return

        if verb == "MDXM_VBAN":
            if "|" in record:
                for f in record.split("|")[1:]:
                    if f.startswith("audio="):
                        peer.audio = f[6:] == "1"
            self.reply(peer, [
                "MDXM_VBANSTATE|emitting=%d|source=personal|format=i16|rate=48000"
                "|channels=2|gain=100|srclatencyms=20|sent=%d|starved=0|dropped=0"
                "|depthms=21|behindms=0" % (1 if peer.audio else 0, self.sent)])
            return

        if verb == "MDXM_FWDSUB":
            # Positional, mirroring MDXM_SUBSCRIBE. Applied locally, then the
            # authoritative state comes from the one place that knows it.
            parts = record.split("|", 1)
            peer.fwd_sub = len(parts) > 1 and parts[1].strip() != "0"
            self.log("  = fwdSub %s for %s:%d" % (peer.fwd_sub, peer.key[0], peer.key[1]))
            if self.args.as_built:
                # THE TRAP, and it is today's real behaviour. VbanServer
                # intercepts the verb, applies the grant under the lock, then
                # forwards the record UNCHANGED to the core -- which has no
                # handler for it, so the answer is an error about an unknown
                # verb while the subscription is live. A client that reads this
                # reply as a failed subscribe concludes the opposite of the
                # truth.
                self.reply(peer, ["MDXM_ERR|msg=unknown verb"])
            else:
                self.reply(peer, [self.fwd_state()])
            return

        if verb == "MDXM_FWDSTATE":
            if self.args.as_built:
                self.reply(peer, ["MDXM_ERR|msg=unknown verb"])
                return
            self.reply(peer, [self.fwd_state()])
            return

        if verb == "MDXM_FWDTARGETS":
            if self.args.as_built:
                self.reply(peer, ["MDXM_ERR|msg=unknown verb"])
                return
            rows = []
            if not self.args.notarget:
                rows.append("MDXM_FWDTARGET|pid=4242|exe=mdropdx12.exe|target=1"
                            "|path=C:\\Program Files\\MDropDX12\\mdropdx12.exe")
                rows.append("MDXM_FWDTARGET|pid=9100|exe=mdropdx12.exe|target=0"
                            "|path=C:\\builds\\dev\\mdropdx12.exe")
            self.reply(peer, ["MDXM_BEGIN"] + rows + ["MDXM_END"])
            return

        if verb == "MDXM_FWD":
            self.on_forward(peer, raw_payload)
            return

        # The mixer's own state, which is not forwarding and needs no mdx12:
        # it answers the same with --notarget and with --off.
        own = self.mixer.handle(record)
        if own is not None:
            self.reply(peer, own)
            return

        self.reply(peer, ["MDXM_ERR|msg=unknown verb"])

    def on_forward(self, peer, raw_payload):
        """`MDXM_FWD|cmd=<raw>` -- the whole reason this file exists.

        `cmd=` is a RAW REMAINDER: everything after the literal `|cmd=`,
        verbatim, '|' and '=' included. Reconstructing it from parsed fields is
        lossy, which is §4.1's entire argument, so this slices the original
        bytes exactly as `RawRemainderAfter` does.
        """
        marker = "|cmd="
        i = raw_payload.find(marker)
        if i < 0:
            self.reply(peer, ["MDXM_ERR|msg=empty command"])
            return
        cmd = raw_payload[i + len(marker):]

        if not cmd:
            self.reply(peer, ["MDXM_ERR|msg=empty command"])
            return
        if self.args.off:
            self.reply(peer, ["MDXM_ERR|msg=forwarding is off"])
            return
        # CASE-SENSITIVE, matching `raw.rfind(L"MDXM_", 0) == 0` at
        # app/app_controller.cpp:1214. A lowercase `mdxm_ping` gets past this
        # on the real server and reaches mdx12, which draws unrecognised text
        # on the visualizer -- so the fake must let it past too, or the client
        # guard that actually closes the hole never gets exercised.
        if cmd.startswith("MDXM_"):
            self.reply(peer, ["MDXM_ERR|msg=refusing to forward an MDXM record"])
            return
        if self.args.notarget or self.args.as_built:
            # ForwardCommand's last line is `return fail(L"notarget")`
            # unconditionally (app/app_controller.cpp:1216) -- MilkwaveClient
            # has no connect or write yet, so every accepted command ends here
            # on the real server today.
            self.reply(peer, ["MDXM_ERR|msg=notarget"])
            return

        self.forward_n += 1
        # MDXM_OK means accepted into the queue -- not written to the pipe, not
        # executed (§4.2). What the command DID shows up on the relay, which is
        # what the relay is for.
        self.reply(peer, ["MDXM_OK"])

        if self.args.lose and (self.forward_n % self.args.lose == 0):
            self.dropped += 1
            self.log("  !! dropped after MDXM_OK: %r" % cmd)
            return

        self.received += 1
        self.push_relay(self.mdx.handle(cmd))

    def on_txt(self, key, pkt):
        peer = self.peer_for(key)
        peer.last_ping = time.time()
        try:
            payload = pkt["data"].decode("utf-8")
        except UnicodeDecodeError:
            return
        # ChunkReplies never splits a record, in either direction, so every
        # line is whole.
        for line in payload.split("\n"):
            line = line.strip()
            if not line:
                continue
            self.log("  <- %s:%d  %s" % (key[0], key[1], line))
            self.on_record(peer, line, line)

    # -- loops ------------------------------------------------------------

    def expire_loop(self):
        """A peer expires after 10 s without a PING0, and `fwdSub` dies with it.

        Enforced on purpose. A client that subscribes once and assumes forever
        simply goes quiet, which is the failure the spec documents rather than
        works around -- and it is invisible unless something actually expires.
        """
        while self.running:
            time.sleep(0.5)
            if self.args.no_expiry:
                continue
            now = time.time()
            with self.lock:
                gone = [k for k, p in self.peers.items()
                        if now - p.last_ping > EXPIRY_S]
                for k in gone:
                    del self.peers[k]
            for k in gone:
                self.log("  - peer %s:%d EXPIRED (fwdSub lost with it)" % k)

    def foreign_loop(self):
        """A phantom other client, and unprompted engine pushes.

        mdx12 broadcasts every reply to every connected client, so a relay
        subscriber receives answers to commands it never sent. A client that
        reads the next record as its own answer gets this wrong, and nothing
        on the rig makes that visible until it matters.
        """
        n = 0
        while self.running:
            time.sleep(7.0)
            if self.args.quiet_foreign:
                continue
            n += 1
            if n % 2:
                self.push_relay(["PRESET=Zylot - In The Rain (phantom client)"])
            else:
                self.push_relay([self.mdx.idle_push(),
                                 "TRACK|artist=Someone Else|title=Not Your Query"])

    def log(self, msg):
        if not self.args.silent:
            sys.stdout.write(msg + "\n")
            sys.stdout.flush()

    def serve(self):
        self.log("fake mdxmixer on %s:%d  pin=%s  fwd=%s  target=%s"
                 % (self.args.host, self.args.port, self.args.pin,
                    "off" if self.args.off else "on",
                    "absent" if self.args.notarget else "mdropdx12.exe pid 4242"))
        threading.Thread(target=self.expire_loop, daemon=True).start()
        threading.Thread(target=self.foreign_loop, daemon=True).start()
        buf = bytearray(2048)
        while True:
            try:
                n, key = self.sock.recvfrom_into(buf)
            except KeyboardInterrupt:
                break
            except OSError:
                continue
            pkt = parse_packet(bytes(buf[:n]))
            if not pkt:
                continue
            if pkt["proto"] == PROTO_SERVICE:
                self.on_service(key, pkt)
            elif pkt["proto"] == PROTO_TXT:
                self.on_txt(key, pkt)
        self.running = False


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--host", default="0.0.0.0")
    # 6985, not mdxmixer's 6980: the real mixer runs on this machine almost
    # all the time, and a fake sharing its port is a coin-flip over which one
    # a datagram reaches. Point the phone's stream port at this instead.
    ap.add_argument("--port", type=int, default=6985)
    # A placeholder, not anyone's PIN. Pass --pin to match whatever the phone
    # is configured with; a real PIN does not belong in a tracked default.
    ap.add_argument("--pin", default="0000")
    ap.add_argument("--off", action="store_true", help="fwd.enabled = 0")
    ap.add_argument("--notarget", action="store_true", help="mdx12 is not running")
    ap.add_argument("--as-built", action="store_true",
                    help="behave like the REAL server does today (tasks 1-7 of "
                         "14): the FWDSUB grant is applied and then answered "
                         "with 'unknown verb', the other forwarding verbs are "
                         "unknown, every forward ends at notarget, and nothing "
                         "is ever relayed. A client must stay sane against this.")
    ap.add_argument("--no-expiry", action="store_true", help="never expire a peer")
    ap.add_argument("--pending", type=float, default=0.0, metavar="SECONDS",
                    help="model the approval prompt: answer pending=1 for this "
                         "long before ok=1, the way an unknown device waits on "
                         "a human clicking Approve")
    ap.add_argument("--lose", type=int, default=0,
                    help="accept then drop every Nth forward")
    ap.add_argument("--trust-loopback", action="store_true",
                    help="authorize a sender on 127.x without a PIN, as the "
                         "real mixer's vban.trustLoopback does")
    ap.add_argument("--no-parts", action="store_true",
                    help="behave like a mixer that predates MDXM_FWDP: no "
                         "`maxparts` in MDXM_FWDSTATE, each pipe message "
                         "wrapped whole, and one too large for a packet "
                         "answered `toolong`")
    ap.add_argument("--lose-part", type=int, default=0,
                    help="drop one part of every Nth record that is cut into "
                         "parts, so that record never completes")
    ap.add_argument("--quiet-foreign", action="store_true",
                    help="no phantom client, no engine pushes")
    ap.add_argument("--silent", action="store_true")
    args = ap.parse_args()
    FakeMixer(args).serve()


if __name__ == "__main__":
    main()
