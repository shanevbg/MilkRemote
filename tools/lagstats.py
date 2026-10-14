"""Summarise MdrLag / MdrAudio lines into one comparable row.

Written so the near measurement and the far one are reduced the same way --
the whole point is the difference between two runs, and a figure computed two
different ways is not a difference.

Takes the LAST `window` seconds present in the file, because `adb logcat`
dumps the existing ring buffer before it starts following and that history is
not part of the sample.
"""
import re
import statistics
import sys
from datetime import datetime

PATH = sys.argv[1]
WINDOW = int(sys.argv[2]) if len(sys.argv) > 2 else 60
LABEL = sys.argv[3] if len(sys.argv) > 3 else PATH

TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)")
LAG = re.compile(
    r"lagMs=(\d+) = net=(\d+) \+ src=(\d+) \+ buffered=(\d+) \+ output=(\d+)"
    r" \+ manual=(-?\d+)\s*\| queued=(\d+)ms"
)
CNT = re.compile(
    r"Counters\(underruns=(\d+), inserts=(\d+), restarts=(\d+), drops=(\d+),"
    r" writeErrors=(\d+)"
)
PCS = re.compile(r"starved=(\d+) dropped=(\d+) depthMs=(\d+) behindMs=(-?\d+)")


def stamp(line):
    m = TS.match(line)
    if not m:
        return None
    return datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f")


rows = []
for line in open(PATH, "r", errors="replace"):
    t = stamp(line)
    if t is None:
        continue
    rows.append((t, line))

if not rows:
    print("no timestamped lines in %s" % PATH)
    raise SystemExit(1)

end = rows[-1][0]
sample = [(t, l) for (t, l) in rows if (end - t).total_seconds() <= WINDOW]

lag, net, src, buf, out, queued = [], [], [], [], [], []
counters, pcstate = [], []
for _, line in sample:
    m = LAG.search(line)
    if m:
        lag.append(int(m.group(1)))
        net.append(int(m.group(2)))
        src.append(int(m.group(3)))
        buf.append(int(m.group(4)))
        out.append(int(m.group(5)))
        queued.append(int(m.group(7)))
    c = CNT.search(line)
    if c:
        counters.append(tuple(int(c.group(i)) for i in range(1, 6)))
    p = PCS.search(line)
    if p:
        pcstate.append(tuple(int(p.group(i)) for i in range(1, 5)))


def s(name, xs, unit="ms"):
    if not xs:
        print("  %-10s (none)" % name)
        return
    sd = statistics.pstdev(xs) if len(xs) > 1 else 0.0
    xs_sorted = sorted(xs)
    p95 = xs_sorted[min(len(xs_sorted) - 1, int(len(xs_sorted) * 0.95))]
    print("  %-10s min=%-6g avg=%-7.1f p95=%-6g max=%-6g sd=%-5.1f  (n=%d) %s"
          % (name, min(xs), sum(xs) / len(xs), p95, max(xs), sd, len(xs), unit))


print("== %s ==  last %ds, %d lines" % (LABEL, WINDOW, len(sample)))
s("lagMs", lag)
s("net", net)
s("src", src)
s("buffered", buf)
s("output", out)
s("queued", queued)

if counters:
    a, b = counters[0], counters[-1]
    names = ["underruns", "inserts", "restarts", "drops", "writeErrors"]
    delta = ["%s +%d" % (n, y - x) for n, x, y in zip(names, a, b)]
    print("  counters over window: " + ", ".join(delta))
    print("  (absolute at end: " + ", ".join(
        "%s=%d" % (n, y) for n, y in zip(names, b)) + ")")
else:
    print("  counters: none logged -- the fault line is silent, so no trouble")

if pcstate:
    a, b = pcstate[0], pcstate[-1]
    print("  pc: starved +%d, dropped +%d, depthMs=%d, behindMs=%d"
          % (b[0] - a[0], b[1] - a[1], b[2], b[3]))
