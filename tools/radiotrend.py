"""Rx/Tx link rate and RSSI over time, from a walkmon capture.

"Rx Link speed" is a substring of "Max Supported Rx Link speed", so a naive
grep reports the ceiling as though it were the reading -- which is how a link
at 36 Mbps can be made to look like 1200.
"""
import re
import sys

PATH = sys.argv[1]
TS = re.compile(r"^== (\d\d:\d\d:\d\d)")
RX = re.compile(r"(?<!Max Supported )Rx Link speed: (\d+)Mbps")
TX = re.compile(r"(?<!Max Supported )Tx Link speed: (\d+)Mbps")
RSSI = re.compile(r"RSSI: (-?\d+)")

rows = []
t = None
rx = tx = rssi = None
for line in open(PATH, errors="replace"):
    m = TS.match(line.strip())
    if m:
        if t:
            rows.append((t, rx, tx, rssi))
        t, rx, tx, rssi = m.group(1), None, None, None
        continue
    for pat, name in ((RX, "rx"), (TX, "tx"), (RSSI, "rssi")):
        g = pat.search(line)
        if g:
            v = int(g.group(1))
            if name == "rx" and rx is None:
                rx = v
            elif name == "tx" and tx is None:
                tx = v
            elif name == "rssi" and rssi is None:
                rssi = v
if t:
    rows.append((t, rx, tx, rssi))

print("%-9s %7s %7s %6s" % ("time", "rx", "tx", "rssi"))
prev = None
for t, rx, tx, rssi in rows:
    key = (rx, rssi)
    if key != prev:
        flag = ""
        if rx is not None:
            if rx < 40:
                flag = "  <-- COLLAPSED band"
            elif rx < 100:
                flag = "  <-- WEAK band"
        print("%-9s %7s %7s %6s%s" % (t, rx, tx, rssi, flag))
        prev = key

vals = [r[1] for r in rows if r[1] is not None]
if vals:
    print("\n%d samples, rx min=%d max=%d" % (len(rows), min(vals), max(vals)))
    print("below 150 (clear point): %d" % sum(1 for v in vals if v < 150))
    print("below 100 (weak):        %d" % sum(1 for v in vals if v < 100))
    print("below 40  (collapsed):   %d" % sum(1 for v in vals if v < 40))
