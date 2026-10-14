"""Is forwarding to MDropDX12 live, and does a reply come back?

Read-only on purpose: the only command it forwards is GET_VERSION, so nothing
on screen changes. One fixed device id, so it occupies a single row in the
mixer's remembered devices however many times it runs.
"""
import socket
import struct
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 6980
PIN = sys.argv[3] if len(sys.argv) > 3 else "0000"

HEADER = 28
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.settimeout(0.4)
seq = 0
tx = 1


def hdr(proto, bit, nuframe, name):
    o = bytearray(HEADER)
    o[0:4] = b"VBAN"
    o[4] = proto
    o[7] = bit
    enc = name.encode("ascii")[:16]
    o[8:8 + len(enc)] = enc
    struct.pack_into("<I", o, 24, nuframe & 0xFFFFFFFF)
    return bytes(o)


def ping():
    global tx
    sock.sendto(hdr(0x60, 0, tx, "VBAN Service"), (HOST, PORT))
    tx += 1


def txt(rec):
    global seq
    sock.sendto(hdr(0x40, 0x10, seq, "mdxmixer") + rec.encode("utf-8"), (HOST, PORT))
    seq += 1


def drain(t=1.2):
    out = []
    end = time.time() + t
    while time.time() < end:
        sock.settimeout(max(0.02, end - time.time()))
        try:
            buf, _ = sock.recvfrom(4096)
        except socket.timeout:
            break
        if len(buf) < HEADER or buf[0:4] != b"VBAN" or (buf[4] & 0xE0) != 0x40:
            continue
        for line in buf[HEADER:].decode("utf-8", "replace").split("\n"):
            if line.strip():
                out.append(line.strip())
    return out


def relayed(recs):
    """The raw slice: everything after the literal |msg=, pipes and all."""
    out = []
    for r in recs:
        i = r.find("|msg=")
        if r.startswith("MDXM_FWDR") and i >= 0:
            out.append(r[i + 5:])
    return out


ping()
time.sleep(0.2)
# ONE identity for every probe, and it is the one already approved.
# The id is what authorizes; the display name is cosmetic and a rename keeps
# the single row (config.h:276-278). A fresh id per script is how the
# remembered-devices list grew three rows deep.
txt("MDXM_AUTH|pin=%s|device=claude-clock-watch|name=Claude probe" % PIN)
auth = drain(1.5)
print("auth: %s" % (auth or "(nothing)"))
if not any("ok=1" in a for a in auth):
    print("\nnot authorized -- approve 'Claude probe' (id claude-clock-watch) on the PC")
    raise SystemExit(1)

print("\n-- MDXM_FWDSTATE")
txt("MDXM_FWDSTATE")
for r in drain():
    print("   %s" % r)

print("\n-- MDXM_FWDTARGETS")
txt("MDXM_FWDTARGETS")
for r in drain():
    print("   %s" % r)

print("\n-- MDXM_FWDSUB|1")
txt("MDXM_FWDSUB|1")
for r in drain():
    print("   %s" % r)

print("\n-- MDXM_FWD|cmd=GET_VERSION   (read-only; nothing on screen changes)")
txt("MDXM_FWD|cmd=GET_VERSION")
recs = drain(2.5)
for r in recs:
    print("   %s" % r)
rel = relayed(recs)
if rel:
    print("\n   RELAYED FROM mdx12, sliced:")
    for r in rel:
        print("     %s" % r)
    print("\n   => forwarding is live, end to end.")
else:
    print("\n   => no MDXM_FWDR came back. Accepted is not executed: MDXM_OK only")
    print("      ever meant queued, so check MDXM_FWDSTATE's connected/pid above.")

txt("MDXM_FWDSUB|0")
drain(0.5)
sock.close()
