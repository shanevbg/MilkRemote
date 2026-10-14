"""Exercise mdxmixer's VBAN server the way MDR_Android will.

Deliberately hand-rolled from the spec rather than from any library: the point
is to check the byte layout VbanCodec.kt was written against against a real
server, before that layout is baked into Kotlin.
"""
import socket
import struct
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 6980
# A placeholder. Pass the real one as the third argument; it does not belong
# in a tracked default.
PIN = sys.argv[3] if len(sys.argv) > 3 else "0000"

HEADER = 28
PROTO_SERVICE, PROTO_TXT, PROTO_AUDIO, PROTO_FRAME = 0x60, 0x40, 0x00, 0x80
PROTO_MASK = 0xE0


def header(proto, sr, nbs, nbc, bit, name, nuframe):
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


def parse(buf):
    if len(buf) < HEADER or buf[0:4] != b"VBAN":
        return None
    sr = buf[4]
    name = bytes(buf[8:24]).split(b"\0")[0].decode("ascii", "replace")
    return {
        "proto": sr & PROTO_MASK,
        "sr": sr & 0x1F,
        "nbs": buf[5], "nbc": buf[6], "bit": buf[7],
        "name": name,
        "nuFrame": struct.unpack_from("<I", buf, 24)[0],
        "data": buf[HEADER:],
    }


s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(3.0)

print("-> PING0")
t0 = time.time()
s.sendto(header(PROTO_SERVICE, 0, 0, 0, 0, "VBAN Service", 4242), (HOST, PORT))
try:
    buf, src = s.recvfrom(2048)
except socket.timeout:
    print("   no reply within 3 s")
    raise SystemExit(1)
rtt_ms = int((time.time() - t0) * 1000)
p = parse(buf)
print("   reply from %s:%d, %d bytes, rtt=%d ms" % (src[0], src[1], len(buf), rtt_ms))
print("   proto=0x%02X sr=%d nbs=0x%02X nbc=%d nuFrame=%d name=%r"
      % (p["proto"], p["sr"], p["nbs"], p["nbc"], p["nuFrame"], p["name"]))

# The 676-byte identification payload, at the offsets VbanCodec.kt uses.
d = p["data"]
print("   payload %d bytes (spec says 676)" % len(d))
if len(d) >= 676:
    bit_type, bit_feature, _ex, pref, mn, mx = struct.unpack_from("<6I", d, 0)
    def ascii_at(off, width):
        return bytes(d[off:off + width]).split(b"\0")[0].decode("ascii", "replace")
    print("   bitType=0x%X bitFeature=0x%X" % (bit_type, bit_feature))
    print("     AUDIO=%s TXT=%s FRAME=%s"
          % (bool(bit_feature & 0x1), bool(bit_feature & 0x10000), bool(bit_feature & 0x1000)))
    print("   rates: preferred=%d min=%d max=%d" % (pref, mn, mx))
    print("   deviceName=%r @164" % ascii_at(164, 64))
    print("   applicationName=%r @292" % ascii_at(292, 64))
    print("   hostName=%r @356" % ascii_at(356, 64))

seq = 0


def txt(record):
    global seq
    body = record.encode("utf-8")
    pkt = header(PROTO_TXT, 0, 0, 0, 0x10, "mdxmixer", seq) + body
    seq += 1
    s.sendto(pkt, (HOST, PORT))


def drain(label, timeout=2.0):
    end = time.time() + timeout
    got = []
    while time.time() < end:
        s.settimeout(max(0.05, end - time.time()))
        try:
            buf, _ = s.recvfrom(4096)
        except socket.timeout:
            break
        q = parse(buf)
        if not q:
            continue
        if q["proto"] == PROTO_TXT:
            for line in q["data"].decode("utf-8", "replace").split("\n"):
                if line.strip():
                    got.append(line.strip())
        elif q["proto"] == PROTO_AUDIO:
            got.append("<audio sr=%d ch=%d samples=%d bytes=%d>"
                       % (q["sr"], q["nbc"] + 1, q["nbs"] + 1, len(q["data"])))
        elif q["proto"] == PROTO_FRAME:
            got.append("<frame %s idx=%d>" % (q["name"], q["nbs"] | (q["nbc"] << 8)))
    print("%s:" % label)
    if not got:
        print("   (nothing)")
    seen_audio = 0
    for g in got:
        if g.startswith("<audio"):
            seen_audio += 1
            continue
        print("   %s" % g)
    if seen_audio:
        print("   ... and %d audio packets" % seen_audio)


print()
print("-> MDXM_AUTH (pin=%s)" % PIN)
txt("MDXM_AUTH|pin=%s|device=claude-probe-01|name=Claude probe" % PIN)
drain("<- auth")

print()
print("-> MDXM_VBAN (bare = query)")
txt("MDXM_VBAN")
drain("<- state")

s.close()
