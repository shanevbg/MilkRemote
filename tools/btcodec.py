"""Set the Bluetooth codec options that Android forgets on every connection.

Android negotiates LDAC at 96 kHz / 32-bit with an adaptive bitrate, whatever
the source is. For a 48 kHz / 16-bit stream that spends the link budget on
bandwidth and bit depth the audio does not contain -- LDAC needs roughly twice
the bitrate at 96 kHz to reach the quality it would at 48. Fixing it in
Developer options works and does not survive the headset reconnecting, which
was watched happening repeatedly on 2026-10-12.

THE AUTOMATIC FIX IS NOT THIS SCRIPT. It is `btcodec.bsh` beside it, the body
of a Tasker "Java Code" action that runs on the phone whenever a headset
connects and calls `BluetoothA2dp.setCodecConfigPreference` directly. This
script is what stands behind it from the PC: `--check` reads the truth off the
device, `--associate` gives Tasker the standing it needs, and the tapping below
is the fallback for a phone without Tasker.

AN EARLIER VERSION OF THIS HEADER SAID THERE WAS NO API. That was wrong, and
the tapping below was built on it. `setCodecConfigPreference` is refused to an
ordinary app -- unless the app holds a companion-device association with that
headset, in which case AOSP lets it through
(`enforceCdmAssociationIfNotBluetoothPrivileged`). The shell can create that
association for any package, which is all `--associate` does.

HOW THE TASKER SIDE IS WIRED, since nothing else records it. One profile:

  context   Event > System > Intent Received, Action
            android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED
  task      Wait 2 seconds, then Java Code holding the loader (--loader),
            which reads btcodec.bsh from Tasker's own folder on the phone

Not the "BT Connected" state with its name left blank. That means ANY
Bluetooth device, so a watch that is always connected holds it permanently
true and it never fires again for a headset.

TO CHANGE WHAT THE PHONE DOES, edit btcodec.bsh and run --push. That is all:
the action is a fixed loader, so nothing in Tasker is touched. The file goes
to Tasker's own corner of shared storage, which it can read without any
storage permission, adb can write, and no other app can reach.

The loader itself only ever needs typing in again if Tasker's data is lost.
Then mind that Tasker commits an edit to a task only on leaving the editor,
and then wants Apply: what was tested with the editor's Play button is NOT
what a connecting headset runs until both have happened. Reopen the saved
action and compare it. tasker_ui.py drives the screen for that, and
tasker_serve.py reaches the script's failure paths for testing.

A BITRATE CHANGED BY HAND DOES NOT TAKE. Changing only the bitrate in
Developer options, mid-connection, updates the config and leaves the encoder
where it was: the row reads 660 and 990 is still sent. --check prints both and
says so when they disagree. It takes when the stream restarts, which is what
happens at connect, where the script's request changes the sample rate too.

WHY THIS SCRIPT STILL CLICKS. From the shell there really is nothing to call:
`cmd bluetooth_manager` offers enable, disable and factoryReset, and there is
no settings-provider key. So the PC-side route drives Developer options.

That is fragile, so the load-bearing part of this script is not the tapping --
it is that it reads back what actually happened from `dumpsys
bluetooth_manager` and says plainly when the tapping achieved nothing.

THREE THINGS THAT HAD TO BE LEARNED THE HARD WAY, all of which silently do
nothing rather than failing:

  - Settings must be FORCE-STOPPED first. `am start` on an already-foreground
    Settings task only brings it forward at whatever page it was left on
    ("Activity not started, its current task has been brought to the front"),
    so the script searches the wrong screen and reports the rows missing.
  - On Android 17 these rows are NOT in the flat Developer options list. They
    are behind a 'Bluetooth' sub-page. Searching the top level finds nothing.
  - Every dialog has an OK button. Tapping the option alone changes nothing.

Zero dependencies, adb on PATH.

  python btcodec.py                 set 48 kHz / 16-bit and verify, at the
                                    bitrate btcodec.bsh asks for
  python btcodec.py --check         report the current config, what the
                                    encoder is really sending, and dropouts
  python btcodec.py --push          put btcodec.bsh on the phone
  python btcodec.py --drops 10      count dropouts a minute at a time
  python btcodec.py --loader        the one line the Tasker action holds
  python btcodec.py --associate     let Tasker set the codec on every headset
  python btcodec.py --associate AA:BB:CC:DD:EE:FF   ...and on this one too
  python btcodec.py --cycle         turn Bluetooth off and on, and report
                                    whether the phone put the codec back
  python btcodec.py --quality 990   a different bitrate from the phone's own
  python btcodec.py --watch         re-apply whenever a headset reconnects
  python btcodec.py --device 192.168.1.5:37000
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

STATE = Path.home() / ".mdr_btcodec.json"

# LDAC's bitrate mode, as Android stores it in mCodecSpecific1.
QUALITY_CODE = {"990": 1000, "660": 1001, "330": 1002, "abr": 1003}
QUALITY_NAME = {1000: "990 kbps", 1001: "660 kbps", 1002: "330 kbps",
                1003: "adaptive", 0: "unset"}


def script_quality():
    """The bitrate btcodec.bsh asks for, as this script's default.

    The phone's script is what decides; were the two to keep their own copies,
    --cycle would report a headset correctly put back to 660 as NOT PUT BACK
    because it was still looking for 990.
    """
    try:
        text = Path(__file__).with_name("btcodec.bsh").read_text(encoding="utf-8")
        code = int(re.search(r"^QUALITY = (\d+)L;", text, re.M).group(1))
        return next(name for name, c in QUALITY_CODE.items() if c == code)
    except Exception:
        return "990"


def script_qualities():
    """Every bitrate btcodec.bsh can ask for: its default, and the one it
    gives the models it lists as holding more."""
    try:
        text = Path(__file__).with_name("btcodec.bsh").read_text(encoding="utf-8")
        return {int(c) for c in re.findall(r"^(?:QUALITY|BETTER) = (\d+)L;", text, re.M)}
    except Exception:
        return set()

# Verbatim from the dialogs on a Pixel 10 Pro XL, Android 17. Matched as
# fragments so a reworded suffix does not break them, but anchored on the
# part that identifies the choice.
ROW_RATE = "Bluetooth Audio Sample Rate"
ROW_BITS = "Bluetooth Audio Bits Per Sample"
ROW_QUALITY = "Bluetooth Audio Playback Quality"
QUALITY_OPTION = {
    "990": "Optimized for Audio Quality",
    "660": "Balanced Audio And Connection Quality",
    "330": "Optimized for Connection Quality",
    "abr": "Best Effort",
}


def adb(dev, *args, timeout=90, binary=False):
    cmd = ["adb"] + (["-s", dev] if dev else []) + list(args)
    p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return p.returncode, (p.stdout if binary else
                          p.stdout.decode("utf-8", "replace"))


def sh(dev, cmdline, timeout=90):
    return adb(dev, "shell", cmdline, timeout=timeout)[1]


# ---------------------------------------------------------------- the device

def attached():
    _, out = adb(None, "devices")
    return [l.split()[0] for l in out.splitlines()[1:]
            if len(l.split()) >= 2 and l.split()[1] == "device"]


def mdns_candidates():
    """Wireless debugging advertises itself; the port rotates, the name does not.

    Needs adb's embedded mDNS, which is off unless the server started with
    ADB_MDNS_OPENSCREEN=1 -- the default backend is Apple's Bonjour and is
    usually absent on Windows. Restarting the server is intrusive, so it only
    happens when discovery is actually needed.
    """
    _, out = adb(None, "mdns", "services")
    hits = re.findall(r"(\S+:\d+)", out)
    if not hits and os.environ.get("ADB_MDNS_OPENSCREEN") != "1":
        os.environ["ADB_MDNS_OPENSCREEN"] = "1"
        adb(None, "kill-server")
        adb(None, "start-server")
        time.sleep(3)
        _, out = adb(None, "mdns", "services")
        hits = re.findall(r"(\S+:\d+)", out)
    return hits


def pick_device(requested):
    if requested:
        if ":" in requested:
            adb(None, "connect", requested)
            time.sleep(1)
        if requested in attached():
            remember(requested)
            return requested
        sys.exit("cannot reach %s" % requested)

    have = attached()
    if len(have) == 1:
        return have[0]
    if len(have) > 1:
        sys.exit("several devices attached, pass --device:\n  " + "\n  ".join(have))

    for cand in mdns_candidates() + [last_used()]:
        if not cand:
            continue
        adb(None, "connect", cand)
        time.sleep(1)
        if cand in attached():
            remember(cand)
            return cand

    sys.exit(
        "no device.\n"
        "  USB: plug in and enable USB debugging.\n"
        "  Wireless: pass --device <ip>:<port>, taking the port from the TOP of\n"
        "  the Wireless debugging screen, not the pairing dialog. They are\n"
        "  different numbers, usually adjacent, and the pairing one gives\n"
        "  'connection refused' on a phone that is perfectly reachable."
    )


def remember(dev):
    if ":" in dev:
        try:
            STATE.write_text(json.dumps({"device": dev}))
        except OSError:
            pass


def last_used():
    try:
        return json.loads(STATE.read_text()).get("device")
    except Exception:
        return None


# --------------------------------------------------------- reading the truth

CODEC = re.compile(
    r"mCodecConfig: \{codecName:(?P<codec>\w+).*?"
    r"mCodecPriority:(?P<prio>-?\d+),"
    r"mSampleRate:0x[0-9a-f]+\((?P<rate>\d+)\),"
    r"mBitsPerSample:0x[0-9a-f]+\((?P<bits>\d+)\).*?"
    r"mCodecSpecific1:(?P<q>-?\d+)"
)


def read_config(dev):
    """The ACTIVE A2DP device's live codec config, or None.

    Taken from the state-machine header rather than the event log: the log
    carries every historical CODEC_CONFIG_CHANGED and its last line is not
    reliably the current state.
    """
    _, raw = adb(dev, "shell", "dumpsys bluetooth_manager", timeout=180, binary=True)
    # Modern Android appends a protobuf blob. The text above it is fine once
    # the NULs are gone -- mistaking that blob for "no data" has cost hours.
    txt = raw.replace(b"\x00", b"").decode("utf-8", "replace")

    for chunk in re.split(r"(?==== A2dpStateMachine for )", txt):
        head = chunk.split("\n", 1)[0]
        if "(Active) ===" not in head:
            continue
        m = CODEC.search(chunk)
        if not m:
            return None
        playing = re.search(r"mIsPlaying: (\w+)", chunk)
        enc = re.search(r"LDAC quality mode\s*:\s*(\w+)\s+"
                        r"LDAC transmission bitrate \(Kbps\)\s*:\s*(\d+)", txt)
        drops = re.search(r"Counts \(flushed/dropped/dropouts\)\s*:\s*\d+ / (\d+) / (\d+)", txt)
        return {
            # What the encoder is really sending, which is not always what the
            # config above says: see show().
            "encoder": (enc.group(1), int(enc.group(2))) if enc else None,
            "dropped": int(drops.group(1)) if drops else None,
            "dropouts": int(drops.group(2)) if drops else None,
            "codec": m.group("codec"),
            "rate": int(m.group("rate")),
            "bits": int(m.group("bits")),
            "quality": int(m.group("q")),
            "priority": int(m.group("prio")),
            "playing": bool(playing and playing.group(1) == "true"),
            "address": re.search(r"for (\S+)", head).group(1),
        }
    return None


def show(label, c):
    if not c:
        print("  %-7s (no active A2DP device)" % label)
        return
    print("  %-7s %s  %d Hz  %d-bit  %-9s  priority=%d  playing=%s"
          % (label, c["codec"], c["rate"], c["bits"],
             QUALITY_NAME.get(c["quality"], c["quality"]),
             c["priority"], c["playing"]))
    if c["codec"] != "LDAC" or not c.get("encoder"):
        return
    mode, kbps = c["encoder"]
    print("  %-7s encoder sending %d kbps (%s); %s dropout(s), %s frames lost since"
          " Bluetooth came on" % ("", kbps, mode, c["dropouts"], c["dropped"]))
    # A bitrate changed on its own, mid-connection, lands in the config and
    # never reaches the encoder: the row in Developer options reads 660 while
    # 990 goes on being sent. It only takes with a change that restarts the
    # stream, which is why the phone's script, acting at connect where the
    # sample rate changes too, works and a hand on the menu later does not. A
    # "660 also cuts out" was concluded from exactly this.
    asked = {1000: 990, 1001: 660, 1002: 330}.get(c["quality"])
    if asked and asked != kbps:
        print("  %-7s THE ENCODER IS NOT DOING WHAT THE SETTING SAYS (%d asked, %d sent)."
              "\n  %-7s Reconnect the headset, or run --cycle, to make it take."
              % ("", asked, kbps, ""))


# ------------------------------------------------- Tasker's standing to set it

TASKER = "net.dinglisch.android.taskerm"

# The display name is matched lazily up to the next field, not to the next
# quote: "Shane's WF-1000XM5" has one in it.
ASSOC = re.compile(
    r"mPackageName='(?P<pkg>[^']+)', mDeviceMacAddress=(?P<mac>[0-9A-Fa-f:]{17}), "
    r"mDisplayName='(?P<name>.*?)', mDeviceProfile='(?P<profile>[^']*)'")


def associations(dev):
    return [m.groupdict() for m in ASSOC.finditer(sh(dev, "cmd companiondevice list 0"))]


def tail(mac):
    """Enough of an address to tell headsets apart, not enough to publish."""
    return "..:" + mac[-8:].lower()


def associate(dev, given):
    """Associate Tasker with every headset, so btcodec.bsh may set its codec.

    The phone's own Bluetooth dump redacts addresses, so the headsets are found
    the other way round: any device another app already holds a plain
    association with -- which is what the vendor's headphone app creates. One
    with a device profile is a watch or similar and is left out. A headset no
    companion app knows can be named on the command line; its address is at the
    bottom of its page in Settings.
    """
    found = associations(dev)
    have = {a["mac"].lower() for a in found if a["pkg"] == TASKER}
    wanted = {}
    for a in found:
        if a["pkg"] != TASKER and a["profile"] == "null":
            wanted.setdefault(a["mac"].lower(), a["name"])
    for mac in given:
        wanted.setdefault(mac.lower(), "(named on the command line)")
    if not wanted:
        print("\nNo headset found. Pass its address: --associate AA:BB:CC:DD:EE:FF")
        return 2

    print()
    for mac, name in sorted(wanted.items(), key=lambda kv: kv[1].lower()):
        if mac in have:
            print("  already   %-12s %s" % (tail(mac), name))
            continue
        # The help text advertises --mac-address; Android 17 takes the address
        # positionally and reads the flag itself as a malformed address. So try
        # one, then the other, and believe neither until the list agrees.
        for form in ("%s", "--mac-address %s"):
            sh(dev, "cmd companiondevice associate 0 %s %s" % (TASKER, form % mac))
            if any(a["pkg"] == TASKER and a["mac"].lower() == mac
                   for a in associations(dev)):
                print("  added     %-12s %s" % (tail(mac), name))
                break
        else:
            print("  FAILED    %-12s %s" % (tail(mac), name))

    now = {a["mac"].lower() for a in associations(dev) if a["pkg"] == TASKER}
    missing = set(wanted) - now
    print("\nTasker is associated with %d device(s)%s"
          % (len(now), "" if not missing else "; %d FAILED" % len(missing)))
    return 1 if missing else 0


# ------------------------------------------- getting the script to the phone

# Tasker's own corner of shared storage: it can read there with no storage
# permission, adb can write there, and no other app can do either.
PHONE_SCRIPT = "/sdcard/Android/data/%s/files/btcodec.bsh" % TASKER

# The whole of the Java Code action. It never changes, so the script can be
# replaced with a file copy instead of a session spent driving Tasker's editor.
LOADER = ('f = new java.io.File(context.getExternalFilesDir(null), "btcodec.bsh"); '
          'if (!f.canRead()) { tasker.showToast("btcodec: cannot read " + f); return "no script"; } '
          'return this.interpreter.eval(new java.io.FileReader(f), this.namespace, "btcodec.bsh");')


def push(dev):
    """Copy btcodec.bsh to where the Tasker action loads it from, and check it."""
    local = Path(__file__).with_name("btcodec.bsh")
    # Normalised to LF: a checkout on Windows may hand this file back as CRLF,
    # and the copy on the phone should not depend on which machine pushed it.
    data = local.read_bytes().replace(b"\r\n", b"\n")
    staged = Path(tempfile.gettempdir()) / "btcodec.bsh"
    staged.write_bytes(data)
    adb(dev, "push", str(staged), PHONE_SCRIPT)
    there = sh(dev, "md5sum %s" % PHONE_SCRIPT).split()
    same = bool(there) and there[0] == hashlib.md5(data).hexdigest()
    print("\n%s -> %s\n%s" % (local.name, PHONE_SCRIPT,
                              "on the phone and identical; the next connection runs it" if same
                              else "NOT COPIED. Has the action run once? Its first run"
                                   " creates that directory."))
    return 0 if same else 1


def drops(dev, minutes):
    """Count transmit dropouts a minute at a time, to compare bitrates fairly.

    The total since Bluetooth came on includes the burst every connection
    starts with, so what matters is how fast it grows while just listening.
    """
    print("\n  time      encoder        dropouts  frames lost   (each line: the minute before it)")
    last = read_config(dev)
    total = 0
    for _ in range(minutes):
        time.sleep(60)
        c = read_config(dev)
        if not c or not last or c["dropouts"] is None or last["dropouts"] is None:
            print("  %s  no LDAC stream to count" % time.strftime("%H:%M:%S"))
        elif c["dropouts"] < last["dropouts"]:
            print("  %s  counters restarted: Bluetooth or the headset reconnected"
                  % time.strftime("%H:%M:%S"))
        else:
            n = c["dropouts"] - last["dropouts"]
            total += n
            print("  %s  %4d kbps %-4s  %8d  %11d" % (
                time.strftime("%H:%M:%S"), c["encoder"][1], c["encoder"][0],
                n, c["dropped"] - last["dropped"]))
        last = c
    print("\n%d dropout(s) in %d minute(s)" % (total, minutes))
    return 0


# ------------------------------------------- proving the phone does it itself

REC = re.compile(r"time=\S+ (?P<t>\d\d:\d\d:\d\d\.\d+).*?org=(?P<org>\w+) "
                 r"dest=(?P<dest>[\w<>]+) what=(?P<what>\d+)")
REC_CODEC = re.compile(r"mSampleRate:0x[0-9a-f]+\((\d+)\),mBitsPerSample:0x[0-9a-f]+\((\d+)\)"
                       r".*?mCodecSpecific1:(-?\d+)")
CONNECTION_STATE_CHANGED = "101"


def machines(dev):
    """{address: (is active, its own records)} for every A2DP state machine.

    Each machine keeps its last twenty events with millisecond times, and that
    is the only account of a connection that can be trusted afterwards. logcat
    cannot: Android prunes old lines from busy processes, so a run that left no
    trace there may simply have been forgotten -- which read, wrongly and for
    some time, as a script that had died.
    """
    _, raw = adb(dev, "shell", "dumpsys bluetooth_manager", timeout=180, binary=True)
    txt = raw.replace(b"\x00", b"").decode("utf-8", "replace").replace("\r", "")
    out = {}
    for chunk in re.split(r"(?==== A2dpStateMachine for )", txt):
        if not chunk.startswith("=== A2dpStateMachine"):
            continue
        head, _, body = chunk.partition("\n")
        recs = []
        for line in body.splitlines():
            if line.strip().startswith("rec["):
                recs.append(line)
            elif recs and not line.strip():
                break   # what follows the blank line belongs to another section
        out[re.search(r"for (\S+)", head).group(1)] = ("(Active)" in head, recs)
    return out


def seconds(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def cycle(dev, off, args):
    """Turn Bluetooth off and on, and say whether the phone put the codec back.

    This is the test of the Tasker side, made without touching a headset: the
    one that was connected comes back by itself, arriving at Android's default,
    and either something on the phone corrects it or nothing does. The verdict
    is read from the stack's own record of that connection.
    """
    want = (int(float(re.search(r"[\d.]+", args.rate).group()) * 1000),
            int(re.search(r"\d+", args.bits).group()))
    # Any bitrate the script can ask for counts as put back: which one a
    # headset gets depends on its model, and this cannot see the model.
    bitrates = {QUALITY_CODE[args.quality]} | script_qualities()
    since = sh(dev, "date +%H:%M:%S").strip()
    print("\nBluetooth off for %d s -- audio, and anything else on Bluetooth, drops" % off)
    sh(dev, "cmd bluetooth_manager disable")
    time.sleep(off)
    sh(dev, "cmd bluetooth_manager enable")
    print("Bluetooth on; waiting for the headset to come back")

    deadline = time.time() + 75
    while time.time() < deadline:
        time.sleep(3)
        for addr, (active, recs) in machines(dev).items():
            up = None
            for line in recs:
                m = REC.search(line)
                if not active or not m or m.group("t") < since:
                    continue
                if m.group("what") == CONNECTION_STATE_CHANGED and m.group("dest") == "Connected":
                    up = m.group("t")
                c = REC_CODEC.search(line)
                got = tuple(int(v) for v in c.groups()) if c else None
                if up and got and got[:2] == want and got[2] in bitrates:
                    # The dump redacts all but the last two octets itself.
                    who = "..:" + addr[-5:].lower()
                    print("\n  %s  connected  %s" % (who, up))
                    print("  %s  corrected  %s" % (who, m.group("t")))
                    print("\nput back to %s, %.2f s after A2DP came up"
                          % (QUALITY_NAME[got[2]], seconds(m.group("t")) - seconds(up)))
                    return 0
    print()
    show("now", read_config(dev))
    print("\nNOT PUT BACK. Is Tasker's profile enabled, and is this headset\n"
          "associated (--associate)? A headset that did not reconnect by itself\n"
          "proves nothing either way.")
    return 1


# ------------------------------------------------------------ driving the UI

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def nodes(dev):
    sh(dev, "uiautomator dump /data/local/tmp/ui.xml")
    _, raw = adb(dev, "exec-out", "cat /data/local/tmp/ui.xml", binary=True)
    try:
        root = ET.fromstring(raw.decode("utf-8", "replace"))
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        b = BOUNDS.match(n.get("bounds", ""))
        t = (n.get("text") or "").strip()
        if not b or not t:
            continue
        x1, y1, x2, y2 = (int(v) for v in b.groups())
        out.append({"t": t, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
    return out


def tap_text(dev, wanted, exact=False, tries=25):
    """Find text, scrolling if needed, and tap it. Coordinates are never
    hardcoded -- a shifted layout would otherwise tap the wrong row."""
    for _ in range(tries):
        for n in nodes(dev):
            hit = (n["t"] == wanted) if exact else (wanted.lower() in n["t"].lower())
            if hit:
                sh(dev, "input tap %d %d" % (n["cx"], n["cy"]))
                time.sleep(1.1)
                return True
        sh(dev, "input swipe 540 1600 540 1150 350")
        time.sleep(0.6)
    return False


def open_bt_page(dev):
    # Force-stop, or `am start` merely resurfaces Settings wherever it was.
    sh(dev, "am force-stop com.android.settings")
    time.sleep(1.0)
    sh(dev, "am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
    time.sleep(2.5)
    # Exact match: 'Bluetooth AVRCP Version' and friends also contain the word.
    return tap_text(dev, "Bluetooth", exact=True)


def set_row(dev, row, option, label):
    if not open_bt_page(dev):
        print("  %-12s could not reach Developer options > Bluetooth" % label)
        return False
    if not tap_text(dev, row):
        print("  %-12s row not found" % label)
        return False
    if not tap_text(dev, option):
        print("  %-12s option %r not offered (greyed out?)" % (label, option))
        sh(dev, "input keyevent KEYCODE_BACK")
        return False
    # The dialog does nothing until OK. Tapping only the radio button looks
    # like success and changes nothing.
    tap_text(dev, "OK", exact=True, tries=3)
    print("  %-12s set" % label)
    return True


def apply(dev, rate, bits, quality):
    set_row(dev, ROW_RATE, rate, "sample rate")
    set_row(dev, ROW_BITS, bits, "bits")
    set_row(dev, ROW_QUALITY, QUALITY_OPTION[quality], "quality")
    sh(dev, "am force-stop com.android.settings")
    sh(dev, "input keyevent KEYCODE_HOME")


# -------------------------------------------------------------------- main

def run_once(dev, args, before):
    print("\nsetting %s / %s / %s" % (args.rate, args.bits, args.quality))
    apply(dev, args.rate, args.bits, args.quality)
    time.sleep(2.0)
    after = read_config(dev)

    print()
    show("before", before)
    show("after", after)

    # THE POINT. Tapping through a menu fails in ways that look like success,
    # so the verdict comes from the device's own report, never from whether
    # the taps were dispatched.
    want_rate = int(float(re.search(r"[\d.]+", args.rate).group()) * 1000)
    want_bits = int(re.search(r"\d+", args.bits).group())
    want_q = QUALITY_CODE[args.quality]
    ok = bool(after and after["rate"] == want_rate
              and after["bits"] == want_bits and after["quality"] == want_q)
    print("\n%s" % ("applied and verified" if ok else
                    "DID NOT TAKE -- set it by hand and check the row names"))
    return 0 if ok else 1


def watch(dev, args):
    """Re-apply on each new connection, which is the actual annoyance.

    Keyed on the active address changing rather than a timer: reconnecting the
    same headset also resets the config, and shows up as the address going
    absent and returning.
    """
    print("\nwatching for headset connections; ctrl-c to stop")
    last = None
    while True:
        c = read_config(dev)
        key = c["address"] if c else None
        if key and key != last:
            print("\n[%s] active: %s" % (time.strftime("%H:%M:%S"), key))
            run_once(dev, args, c)
        last = key
        time.sleep(5)


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device")
    ap.add_argument("--rate", default="48.0 kHz")
    ap.add_argument("--bits", default="16 bits/sample")
    ap.add_argument("--quality", default=script_quality(), choices=sorted(QUALITY_CODE))
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--watch", action="store_true")
    ap.add_argument("--associate", nargs="*", metavar="MAC")
    ap.add_argument("--cycle", nargs="?", type=int, const=6, metavar="SECONDS")
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--loader", action="store_true")
    ap.add_argument("--drops", nargs="?", type=int, const=5, metavar="MINUTES")
    args = ap.parse_args()

    if args.loader:
        print(LOADER)
        return 0

    dev = pick_device(args.device)
    print("device: %s\n" % dev)

    if args.associate is not None:
        return associate(dev, args.associate)
    if args.push:
        return push(dev)
    if args.cycle is not None:
        return cycle(dev, args.cycle, args)
    if args.drops is not None:
        show("current", read_config(dev))
        return drops(dev, args.drops)

    before = read_config(dev)
    show("current", before)
    if args.check:
        return 0
    if not before:
        print("\nNo A2DP device is active. Connect the headset first.")
        return 2
    if not before["playing"]:
        print("\nNote: nothing is playing, so some options may be greyed out.")

    return watch(dev, args) if args.watch else run_once(dev, args, before)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
