"""Cold-launch the app in the EMULATOR against tools/fake_mdxmixer.py, open the
Displays tab, and report what it asked the fake.

    python tools/emu_launch.py <fake.log> <launches> [--install <apk>]

`fake.log` is the fake's own output, redirected to a file: the fake logs every
record in and out, which makes it the one place that shows what the app really
sent. Per launch this prints how many times each MDropDX12 verb was forwarded,
and whether GET_VERSION left before or after the relay subscription.

Both of those have been wrong without anything on screen saying so:

  * opening the Displays tab asked for everything twice, every time;
  * GET_VERSION sometimes left ahead of MDXM_FWDSUB|1, so the answer was
    relayed to nobody and the app learned none of the PC's features for the
    rest of the session. One cold launch in four, which is why this takes a
    count rather than launching once.

EVERYTHING IS PINNED TO emulator-5554. The real phone is usually attached to
adb as well, and an unqualified command would land on the app somebody is
using.

The tap that opens the Displays tab is a coordinate, for a 1080x2400 screen
with the six-tab bar. Read the screen and change it if the layout has moved.
"""
import collections
import re
import subprocess
import sys
import time

ADB = ["adb", "-s", "emulator-5554"]
PKG = "com.sheinsez.mdropdx12.remote"
DISPLAYS_TAB = ("630", "2200")
# The fake forgets a peer after 10 s of silence. Waiting it out makes each
# launch a first meeting, with no subscription left over from the last one.
PEER_EXPIRY_S = 11


def adb(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True).stdout


def read(path):
    return open(path, encoding="utf-8", errors="replace").readlines()


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    log_path, launches = sys.argv[1], int(sys.argv[2])

    if "--install" in sys.argv:
        apk = sys.argv[sys.argv.index("--install") + 1]
        adb("shell", "am", "force-stop", PKG)
        print("install:", adb("install", "-r", apk).strip().splitlines()[-1])

    for n in range(1, launches + 1):
        adb("shell", "am", "force-stop", PKG)
        time.sleep(PEER_EXPIRY_S)
        start = len(read(log_path))
        adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
        time.sleep(5)
        adb("shell", "input", "tap", *DISPLAYS_TAB)
        time.sleep(6)
        lines = read(log_path)[start:]
        asked = collections.Counter(
            m.group(1) for line in lines
            for m in [re.search(r"<- .*MDXM_FWD\|cmd=([A-Z_]+)", line)] if m)
        order = [("SUB" if "MDXM_FWDSUB|1" in line else "VERSION")
                 for line in lines
                 if re.search(r"<- .*(MDXM_FWDSUB\|1|MDXM_FWD\|cmd=GET_VERSION)", line)]
        first = order.index("VERSION") if "VERSION" in order else -1
        relayed = any("~> relay" in line and "VERSION=" in line for line in lines)
        print("launch %d: GET_VERSION %s the subscription, relayed=%s"
              % (n, "AFTER" if first > 0 else "BEFORE" if first == 0
                 else "never asked, vs", relayed))
        print("   " + "  ".join(
            "%s x%d" % (k.replace("GET_", "").replace("DISPLAY_", "D_"), v)
            for k, v in sorted(asked.items())))


if __name__ == "__main__":
    main()
