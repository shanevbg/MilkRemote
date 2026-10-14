"""Timestamped reachability log, driven from the PC.

adb-over-Wi-Fi rides the link under test and keeps dying at the far end, and
the wireless-debugging port rotates every time the network blips -- so the
phone-side measurements keep going out of reach exactly when they get
interesting. This needs nothing from the phone but an ICMP reply.

One ping a second, each line stamped, loss recorded as loss rather than
skipped. The absolute RTT is inflated by Wi-Fi power save; the point is the
SHAPE over the walk -- when it climbs, when it starts dropping, when it stops
answering altogether.
"""
import re
import subprocess
import sys
import time
from datetime import datetime

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.137.64"
SECONDS = int(sys.argv[2]) if len(sys.argv) > 2 else 600
OUT = sys.argv[3] if len(sys.argv) > 3 else None

RTT = re.compile(r"time[=<](\d+)ms")

sink = open(OUT, "w") if OUT else sys.stdout
end = time.time() + SECONDS
sent = lost = 0

while time.time() < end:
    t0 = time.time()
    stamp = datetime.now().strftime("%H:%M:%S")
    try:
        out = subprocess.run(
            ["ping", "-n", "1", "-w", "1500", HOST],
            capture_output=True, text=True, timeout=5,
        ).stdout
    except Exception:
        out = ""
    sent += 1
    m = RTT.search(out)
    if m:
        print("%s rtt=%s" % (stamp, m.group(1)), file=sink, flush=True)
    else:
        lost += 1
        print("%s LOST" % stamp, file=sink, flush=True)
    slack = 1.0 - (time.time() - t0)
    if slack > 0:
        time.sleep(slack)

print("sent=%d lost=%d (%.1f%% loss)" % (sent, lost, 100.0 * lost / max(sent, 1)),
      file=sink, flush=True)
