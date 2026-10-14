"""Read one real AUDIO packet and print the header the way VbanCodec does."""
import socket
import struct
import sys

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 6980
HEADER = 28
RATES = [6000, 12000, 24000, 48000, 96000, 192000, 384000,
         8000, 16000, 32000, 64000, 128000, 256000, 512000,
         11025, 22050, 44100, 88200, 176400, 352800, 705600]

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(3.0)
ping = bytearray(HEADER)
ping[0:4] = b"VBAN"
ping[4] = 0x60
ping[8:8 + 12] = b"VBAN Service"
struct.pack_into("<I", ping, 24, 1)
s.sendto(bytes(ping), (HOST, PORT))

seen = 0
first_frame = None
last_frame = None
while seen < 400:
    try:
        buf, _ = s.recvfrom(2048)
    except socket.timeout:
        break
    if len(buf) < HEADER or buf[0:4] != b"VBAN":
        continue
    if (buf[4] & 0xE0) != 0x00:          # AUDIO
        continue
    sr_idx = buf[4] & 0x1F
    nbs, nbc, bit = buf[5], buf[6], buf[7]
    nuframe = struct.unpack_from("<I", buf, 24)[0]
    if seen == 0:
        print("first AUDIO packet, %d bytes total" % len(buf))
        print("  sr index   = %d  -> %d Hz" % (sr_idx, RATES[sr_idx] if sr_idx < len(RATES) else 0))
        print("  nbs        = %d  -> %d samples/packet" % (nbs, nbs + 1))
        print("  nbc        = %d  -> %d channels" % (nbc, nbc + 1))
        print("  bit        = 0x%02X -> datatype %d, codec nibble 0x%02X"
              % (bit, bit & 0x07, bit & 0xF0))
        print("  stream     = %r" % bytes(buf[8:24]).split(b"\0")[0].decode("ascii", "replace"))
        print("  payload    = %d bytes" % (len(buf) - HEADER))
        expect = (nbs + 1) * (nbc + 1) * (2 if (bit & 7) == 1 else 4)
        print("  expected   = %d bytes for int16 stereo -> %s"
              % (expect, "MATCH" if expect == len(buf) - HEADER else "MISMATCH"))
        first_frame = nuframe
    last_frame = nuframe
    seen += 1

print("  %d audio packets, nuFrame %s..%s, contiguous=%s"
      % (seen, first_frame, last_frame,
         (last_frame - first_frame + 1) == seen if first_frame is not None else "n/a"))
s.close()
