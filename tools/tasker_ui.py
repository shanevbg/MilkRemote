"""Drive an Android screen over adb a step at a time, and print what is on it.

Written to build a Tasker profile from the PC, and not specific to Tasker.
Commands chain, separated by the word `then`:

  python tasker_ui.py dump
  python tasker_ui.py tap "Tasks" then dump
  python tasker_ui.py tap "OK" --exact then key BACK then dump
  python tasker_ui.py long "BT codec" then dump
  python tasker_ui.py tapid task_layout_one then sleep 1 then dump
  python tasker_ui.py type "some text" then gettext edittext field.txt
  python tasker_ui.py gettext "~words in the field" field.txt
  python tasker_ui.py xy 540 1200
  python tasker_ui.py focus

Set ANDROID_SERIAL when more than one device is attached -- an emulator
usually is.

Coordinates always come from a fresh hierarchy dump, never from memory: a
dialog that was not there a moment ago moves everything. A step that cannot
find its target prints the screen and stops the chain.

THINGS THIS COST TIME TO LEARN, on Tasker 6.6 / Android 17:

  - Someone is usually watching the phone. Say so before taking the screen and
    keep saying what is happening; a silent run of taps looks like a hang, and
    then two hands are on one screen.
  - Open Tasker with `am start -n net.dinglisch.android.taskerm/.Tasker`. The
    launcher intent can land on its second icon, which opens a dialog instead.
  - Tasker commits a task edit only on leaving the editor, and then shows an
    Apply button on the main screen. The editor's Cancel discards the edit.
  - A toast is not in the hierarchy. Tasker draws its own as an overlay window
    titled with the bare package name, visible in `dumpsys window windows`.
  - `gettext` exists because a long field loses its resource id; find it by
    the words in it ("~...") and compare the whole text with the file it came
    from. A paste that looks right can still be the previous clipboard.
  - Permission prompts are the owner's to answer. Back out of them.
  - Tasker's "Tip" dialogs come back on every visit until told otherwise, and
    one sitting unseen over the screen makes the next step miss.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEV = os.environ.get("ANDROID_SERIAL")
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def adb(*args, binary=False, timeout=60):
    cmd = ["adb"] + (["-s", DEV] if DEV else []) + list(args)
    p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def nodes():
    for _ in range(4):
        sh("uiautomator dump /data/local/tmp/ui.xml")
        raw = adb("exec-out", "cat /data/local/tmp/ui.xml", binary=True)
        try:
            root = ET.fromstring(raw.decode("utf-8", "replace"))
            break
        except ET.ParseError:
            time.sleep(0.7)
    else:
        return []
    out = []
    for n in root.iter("node"):
        b = BOUNDS.match(n.get("bounds", ""))
        if not b:
            continue
        x1, y1, x2, y2 = (int(v) for v in b.groups())
        out.append({
            "t": (n.get("text") or "").strip(),
            "d": (n.get("content-desc") or "").strip(),
            "id": (n.get("resource-id") or "").split("/")[-1],
            "cls": (n.get("class") or "").split(".")[-1],
            "click": n.get("clickable") == "true",
            "long": n.get("long-clickable") == "true",
            "chk": n.get("checked") == "true",
            "checkable": n.get("checkable") == "true",
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
            "w": x2 - x1, "h": y2 - y1,
        })
    return out


def dump(limit=90):
    shown = 0
    for n in nodes():
        if not (n["t"] or n["d"] or (n["click"] and n["id"])):
            continue
        if n["w"] <= 0 or n["h"] <= 0:
            continue
        # c clickable, l long-clickable, K checked, k checkable and unchecked
        flags = "".join(f for f, on in (("c", n["click"]), ("l", n["long"]),
                                         ("K", n["chk"]),
                                         ("k", n["checkable"] and not n["chk"]))
                        if on)
        label = n["t"][:110].replace("\n", "\\n")
        desc = (" «%s»" % n["d"][:50]) if n["d"] and n["d"] != n["t"] else ""
        print("(%4d,%4d) %-3s %-18s %s%s  #%s"
              % (n["cx"], n["cy"], flags, n["cls"][:18], label, desc, n["id"]))
        shown += 1
        if shown >= limit:
            print("... more")
            break
    print("-- %s" % focus_line())


def focus_line():
    m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}", sh("dumpsys window"))
    return m.group(1) if m else "?"


def find(wanted, exact=False, by_id=False):
    w = wanted.lower()
    for n in nodes():
        if by_id:
            if n["id"] == wanted:
                return n
            continue
        for field in (n["t"], n["d"]):
            f = field.lower()
            if (f == w) if exact else (w in f and f):
                return n
    return None


def tap(n, hold=False):
    if hold:
        sh("input swipe %d %d %d %d 900" % (n["cx"], n["cy"], n["cx"], n["cy"]))
    else:
        sh("input tap %d %d" % (n["cx"], n["cy"]))
    time.sleep(1.2)


def run(cmd):
    op, args = cmd[0], cmd[1:]
    exact = "--exact" in args
    args = [a for a in args if a != "--exact"]
    if op == "dump":
        dump()
    elif op == "focus":
        print(focus_line())
    elif op in ("tap", "long", "tapid"):
        n = find(args[0], exact=exact, by_id=(op == "tapid"))
        if not n:
            print("!! not on screen: %r" % args[0])
            return False
        tap(n, hold=(op == "long"))
        print("%s %r at (%d,%d)" % (op, args[0], n["cx"], n["cy"]))
    elif op == "xy":
        sh("input tap %s %s" % (args[0], args[1]))
        time.sleep(1.0)
    elif op == "swipe":
        sh("input swipe %s %s %s %s 350" % tuple(args[:4]))
        time.sleep(0.8)
    elif op == "type":
        # `input text` takes %s for a space; everything else survives single
        # quotes, which is what lets a line of code through intact.
        text = args[0].replace(" ", "%s")
        sh("input text '%s'" % text.replace("'", "'\\''"))
        time.sleep(0.6)
    elif op == "key":
        sh("input keyevent KEYCODE_%s" % args[0])
        time.sleep(1.0)
    elif op == "sleep":
        time.sleep(float(args[0]))
    elif op == "gettext":
        # The whole text of one node, written to a file: dump() truncates.
        # "~text" finds the node by what it contains, for fields with no id.
        n = (find(args[0][1:]) if args[0].startswith("~")
             else find(args[0], by_id=True))
        if not n:
            print("!! not on screen: %r" % args[0])
            return False
        with open(args[1], "w", encoding="utf-8", newline="\n") as f:
            f.write(n["t"])
        print("wrote %d chars to %s" % (len(n["t"]), args[1]))
    else:
        print("!! unknown command %r" % op)
        return False
    return True


def main():
    cmds, cur = [], []
    for a in sys.argv[1:]:
        if a == "then":
            cmds.append(cur)
            cur = []
        else:
            cur.append(a)
    if cur:
        cmds.append(cur)
    if not cmds:
        print(__doc__)
        return 2
    for c in cmds:
        if not run(c):
            dump()
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
