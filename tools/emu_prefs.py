"""Point the app, in the EMULATOR, at tools/fake_mdxmixer.py on this machine.

The app keeps its settings in a Preferences DataStore file, and the mixer's
address is one of them. Writing that file before the first launch is the way
to aim a fresh install at the fake without tapping through Settings.

    python tools/emu_prefs.py <out-file> [port] [--background]

writes the file. `--background` also switches on "Stay connected in the
background", which is what runs the service that holds the media session --
without it there is no notification, so nothing for a media controller to
find. Getting it into the app is three adb commands, every one of
them pinned to the emulator because the real phone is usually attached too:

    adb -s emulator-5554 push <out-file> /data/local/tmp/settings.preferences_pb
    adb -s emulator-5554 shell chmod 644 /data/local/tmp/settings.preferences_pb
    adb -s emulator-5554 shell "run-as com.sheinsez.mdropdx12.remote sh -c \\
        'mkdir -p files/datastore && cp /data/local/tmp/settings.preferences_pb \\
         files/datastore/settings.preferences_pb'"

From Git Bash, `export MSYS_NO_PATHCONV=1` first, or the device paths are
rewritten into C:/Program Files/Git/... before adb sees them.

The host is 10.0.2.2, which is how an emulator reaches the loopback of the
machine it runs on. The fake listens on 6985 by default, not the real mixer's
6980, so the two cannot be confused. Audio is switched off: nothing needs it,
and somebody is usually listening to the real thing.

THE FORMAT is androidx.datastore's PreferenceMap. Field 1 is a map entry of
key (field 1, string) and Value (field 2), and a Value is one of boolean
(field 1), integer (field 3) or string (field 5).
"""
import sys


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def length_delimited(field, payload):
    return varint((field << 3) | 2) + varint(len(payload)) + payload


def value(v):
    if isinstance(v, bool):
        return varint((1 << 3) | 0) + varint(1 if v else 0)
    if isinstance(v, int):
        return varint((3 << 3) | 0) + varint(v)
    return length_delimited(5, v.encode("utf-8"))


def entry(key, v):
    return length_delimited(1, length_delimited(1, key.encode("utf-8"))
                            + length_delimited(2, value(v)))


def main():
    args = [a for a in sys.argv[1:] if a != "--background"]
    if not args:
        sys.exit(__doc__)
    port = int(args[1]) if len(args) > 1 else 6985
    prefs = {
        "vban_host": "10.0.2.2",
        "vban_port": port,
        # A placeholder, matching the fake's own default.
        "vban_pin": "0000",
        "vban_audio_on": False,
    }
    if "--background" in sys.argv[1:]:
        prefs["background_service"] = True
    with open(args[0], "wb") as f:
        for k, v in prefs.items():
            f.write(entry(k, v))
    print("wrote %s: %s" % (args[0], prefs))


if __name__ == "__main__":
    main()
