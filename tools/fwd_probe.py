"""Exercise the MDXM forwarding verbs, and check the hostile cases.

Written against `mdxmixer/docs/specs/2026-10-08-ipc-forwarding-design.md`
§4.2-4.5. Points at `tools/fake_mdxmixer.py` by default; point it at the real
mixer once `MDXM_FWD` is dispatched there and the two should agree record for
record. Where they disagree, the real server is right and this says so loudly.

    python tools/fwd_probe.py                      # 127.0.0.1:6985
    python tools/fwd_probe.py 192.168.0.58 6980 <the phone's PIN>

It checks four things that matter more than the happy path:

  * a relayed record carrying '|' and '=' survives the slice rule (§4.1);
  * an `MDXM_` command is refused rather than looped (§10.4);
  * replies arrive for commands this probe never sent (§4.4 firehose);
  * `fwdSub` dies with the peer after 10 s of silence (§7.2).
"""
import socket
import struct
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 6985
# A placeholder. Pass the real one as the third argument; it does not belong
# in a tracked default.
PIN = sys.argv[3] if len(sys.argv) > 3 else "0000"

HEADER = 28
PROTO_TXT, PROTO_SERVICE = 0x40, 0x60
PROTO_MASK = 0xE0
TXT_UTF8 = 0x10

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.settimeout(0.3)
seq = 0
transaction = 1
failures = []


def header(proto, nbs, nbc, bit, name, nuframe):
    out = bytearray(HEADER)
    out[0:4] = b"VBAN"
    out[4] = proto & PROTO_MASK
    out[5] = nbs & 0xFF
    out[6] = nbc & 0xFF
    out[7] = bit & 0xFF
    enc = name.encode("ascii")[:16]
    out[8:8 + len(enc)] = enc
    struct.pack_into("<I", out, 24, nuframe & 0xFFFFFFFF)
    return bytes(out)


def ping():
    global transaction
    sock.sendto(header(PROTO_SERVICE, 0, 0, 0, "VBAN Service", transaction), (HOST, PORT))
    transaction += 1


def txt(record):
    global seq
    sock.sendto(header(PROTO_TXT, 0, 0, TXT_UTF8, "mdxmixer", seq)
                + record.encode("utf-8"), (HOST, PORT))
    seq += 1


def drain(seconds=1.0):
    """Every TXT record that arrives in the window. Lines are whole: the
    server's ChunkReplies never splits a record."""
    out = []
    end = time.time() + seconds
    while time.time() < end:
        sock.settimeout(max(0.02, end - time.time()))
        try:
            buf, _ = sock.recvfrom(4096)
        except socket.timeout:
            break
        if len(buf) < HEADER or buf[0:4] != b"VBAN":
            continue
        if (buf[4] & PROTO_MASK) != PROTO_TXT:
            continue
        for line in buf[HEADER:].decode("utf-8", "replace").split("\n"):
            if line.strip():
                out.append(line.strip())
    return out


def relayed(records):
    """The mdx12 records inside MDXM_FWDR, sliced the one correct way.

    Everything after the literal '|msg=' is mdx12's bytes, '|' and '='
    included. Splitting the record on '|' is what §4.1 spends two pages
    forbidding.
    """
    out = []
    for r in records:
        marker = "|msg="
        i = r.find(marker)
        if r.startswith("MDXM_FWDR") and i >= 0:
            out.append(r[i + len(marker):])
    return out


def check(label, ok, detail=""):
    # Detail only on failure: printing the "why this would fail" note beside a
    # PASS reads as if the check had failed.
    print("  %s %s%s" % ("PASS" if ok else "FAIL", label,
                         ("  -- " + detail) if (detail and not ok) else ""))
    if not ok:
        failures.append(label)


print("probing %s:%d" % (HOST, PORT))
ping()
if not drain(0.5) and True:
    pass  # a PING0 reply is SERVICE, not TXT -- absence here means nothing

txt("MDXM_AUTH|pin=%s|device=claude-fwd-probe|name=Claude fwd probe" % PIN)
auth = drain(1.0)
print("\nauth: %s" % (auth or "(nothing)"))
if not any("state=ok" in a for a in auth):
    print("\nnot authorized -- approve the device on the PC and run again")
    raise SystemExit(1)

# ── state and targets ────────────────────────────────────────────────────────
print("\n-- MDXM_FWDSTATE")
txt("MDXM_FWDSTATE")
st = drain(1.0)
for s in st:
    print("   %s" % s)
state = next((s for s in st if s.startswith("MDXM_FWDSTATE")), "")
fields = [f.split("=", 1)[0] for f in state.split("|")[1:]]
expected = ["enabled", "targetExe", "pid", "connected", "launching", "autolaunch",
            "learned", "subs", "queued", "dropped", "sent", "received", "path",
            "lastError"]
check("state field order matches the spec", fields == expected,
      "got %s" % fields if fields != expected else "")

print("\n-- MDXM_FWDTARGETS")
txt("MDXM_FWDTARGETS")
tg = drain(1.0)
for t in tg:
    print("   %s" % t)
check("targets are batched between MDXM_BEGIN and MDXM_END",
      any(t == "MDXM_BEGIN" for t in tg) and any(t == "MDXM_END" for t in tg))

# ── subscribe, then forward ──────────────────────────────────────────────────
print("\n-- MDXM_FWDSUB|1")
txt("MDXM_FWDSUB|1")
sub = drain(1.0)
for s in sub:
    print("   %s" % s)
subs = next((f[5:] for s in sub for f in s.split("|") if f.startswith("subs=")), "0")
check("the subscription is counted", subs != "0", "subs=%s" % subs)

print("\n-- MDXM_FWD|cmd=GET_VERSION")
txt("MDXM_FWD|cmd=GET_VERSION")
fwd = drain(1.5)
for f in fwd:
    print("   %s" % f)
check("accepted with MDXM_OK", any(f == "MDXM_OK" for f in fwd))
rel = relayed(fwd)
print("   sliced: %s" % rel)
check("a relayed record keeps its pipes and equals signs",
      any("|" in r and "features=" in r for r in rel),
      "a naive split on '|' would have shredded this")

print("\n-- MDXM_FWD|cmd=PRESET_ROWS  (the bulk reply the spec warns about)")
txt("MDXM_FWD|cmd=PRESET_ROWS")
rows = relayed(drain(1.5))
for r in rows:
    print("   %s" % r)
check("a path containing a backslash and an equals survives",
      any("\\" in r for r in rows))
check("the batch terminator arrives", any(r.startswith("PRESET_ROWS_END") for r in rows))

# ── the refusals ─────────────────────────────────────────────────────────────
print("\n-- the refusals")
txt("MDXM_FWD|cmd=MDXM_VBAN")
r1 = drain(0.8)
check("an MDXM_ record is refused, not forwarded",
      any("refusing to forward an MDXM record" in r for r in r1),
      str(r1))

txt("MDXM_FWD|cmd=")
r2 = drain(0.8)
check("an empty command is refused", any("empty command" in r for r in r2), str(r2))

# ── the firehose ─────────────────────────────────────────────────────────────
print("\n-- firehose: waiting 9 s for traffic this probe did not ask for")
ping()
foreign = relayed(drain(9.0))
for f in foreign:
    print("   %s" % f)
check("replies arrive for commands this client never sent", bool(foreign),
      "nothing arrived; the real server only does this when another client "
      "is talking to mdx12")

# ── expiry ───────────────────────────────────────────────────────────────────
print("\n-- expiry: going silent for 12 s, then forwarding without re-subscribing")
time.sleep(12.0)
# Flush first. Pushes sent while this probe was still alive -- the peer only
# expires at 10 s, so the 7 s phantom push beat it -- are sitting in the socket
# buffer, and reading them as post-expiry traffic fails a passing server.
stale = drain(0.3)
if stale:
    print("   (discarded %d record(s) buffered from before expiry)" % len(stale))
txt("MDXM_FWD|cmd=NEXT_PRESET")
after = drain(1.5)
for a in after:
    print("   %s" % a)
check("the peer expired, so the command is refused",
      any("unauthorized" in a for a in after),
      "expected MDXM_ERR|msg=unauthorized; got %s" % after)
check("no relay traffic survives the expiry",
      not relayed(after),
      "still receiving relay traffic after expiry")
print("   (re-authorizing and re-subscribing is the client's job after this)")

print("\n%d check(s) failed%s" % (len(failures), (": " + ", ".join(failures)) if failures else ""))
sock.close()
raise SystemExit(1 if failures else 0)
