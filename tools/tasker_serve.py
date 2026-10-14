"""Test a Tasker "Java Code" script from the PC, down the paths a desk cannot make.

btcodec.bsh normally reaches the phone as a file (`btcodec.py --push`), and
the Tasker action is a fixed loader that reads it. This is for the other job:
running the script with the phone reporting back what happened, and forcing
it down its failure paths. It needs the action's code swapped, in the editor
and unsaved, for a one-line loader that fetches from this server over
`adb reverse` instead -- edit the file here, press Play on the phone, read
what came back. Cancel the edit afterwards and the saved loader is untouched.

  adb reverse tcp:8765 tcp:8765
  python tasker_serve.py btcodec.bsh          serve it (leave running)
  python tasker_serve.py --stub               the line to type into the action
  python tasker_serve.py --log                what the phone has reported

Typed with tasker_ui.py, the loader goes in through `adb shell input text` and
comes back out of the field unchanged; check that it did.

The script is served as shipped, except that its last `return result;` also
posts `result` and `out[1]` (the toast) back here. The phone has no other way
to say what happened.

MODES switch while the server runs, and exist to reach the paths a desk cannot
produce on demand. They are specific to btcodec.bsh:

  --set clip            /b puts the script, verbatim, on the phone's clipboard
                        instead of running it. Then select all in the action's
                        Code field (`input keycombination 113 29`) and paste
                        (`input keyevent 279`). This is how a whole script was
                        put inside the action before it loaded from a file;
                        it remains the way to get any long text into Tasker.
  --set empty=3         the first 3 looks find no headset connected
  --set empty=99        no look ever does
  --set notldac         the script is told LDAC is some other codec
  --set ignored         the config never looks right, and requests are timed
                        instead of sent: a stack that ignores every one
  --set timeout         the wait for the A2DP proxy is abandoned at once
  --clear               all of them off

Whatever was tested through the loader, the SAVED action is a different thing
until the editor has been left and Apply pressed. See btcodec.py's header.
"""
import argparse
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

PORT = 8765
WORK = Path(tempfile.gettempdir()) / "tasker_serve"
LOG = WORK / "results.log"
MODES = ("clip", "empty", "notldac", "ignored", "timeout")

STUB = ('return this.interpreter.eval(new java.io.InputStreamReader(new java.net.URL('
        '"http://127.0.0.1:%d/b").openStream()), this.namespace, "b");' % PORT)

POST = '''
{
    c = new java.net.URL("http://127.0.0.1:%d/r").openConnection();
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    o = c.getOutputStream();
    o.write((result + "\\n[toast] " + out[1]).getBytes("UTF-8"));
    o.close();
    c.getResponseCode();
    c.disconnect();
}
return result;
''' % PORT

# adb cannot set the clipboard; an app can, and this runs inside one.
CLIP = '''
r = new java.io.BufferedReader(new java.io.InputStreamReader(
        new java.net.URL("http://127.0.0.1:%d/raw").openStream(), "UTF-8"));
sb = new StringBuffer();
while ((line = r.readLine()) != null) sb.append(line).append("\\n");
r.close();
context.getSystemService("clipboard").setPrimaryClip(
        android.content.ClipData.newPlainText("script", sb.toString()));
result = "clipboard holds " + sb.length() + " chars";
out = new String[] { result, "" };
''' % PORT + POST


def mode(name):
    """The mode's value, or None when it is off."""
    f = WORK / name
    return f.read_text().strip() if f.exists() else None


def swap(text, pairs):
    for real, fake in pairs:
        # A mode that no longer matches the script must fail loudly: served
        # unchanged, it would pass a test of something it never exercised.
        assert real in text, "mode no longer matches the script: %r" % real
        text = text.replace(real, fake)
    return text


def served(script):
    text = script.read_text(encoding="utf-8")
    if mode("empty"):
        text = swap(text, [(
            "java.util.List devices = a2dp.getConnectedDevices();",
            "java.util.List devices = (empty < %d) ? new java.util.ArrayList()"
            " : a2dp.getConnectedDevices();" % int(mode("empty")))])
    if mode("ignored"):
        text = swap(text, [
            ("c.getCodecSpecific1() == q;", "c.getCodecSpecific1() == q && false;"),
            ("if (ask) { a2dp.setCodecConfigPreference(d, configFor(q)); asked = true; }",
             'if (ask) { askedAt.append(" " + ((System.currentTimeMillis() - t0) / 1000));'
             ' asked = true; }'),
            ("return result;", 'result = result + "\\n[asked at s]" + askedAt;\nreturn result;'),
        ])
        text = "askedAt = new StringBuffer();\nt0 = System.currentTimeMillis();\n" + text
    if mode("timeout"):
        text = swap(text, [("latch.await(8, TimeUnit.SECONDS)",
                            "latch.await(1, TimeUnit.MILLISECONDS)")])
    if mode("notldac"):
        text = swap(text, [("LDAC = BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC;", "LDAC = 99;")])
    head, sep, tail = text.rpartition("return result;")
    return (head + POST + tail if sep else text).encode("utf-8")


def note(line):
    with LOG.open("a", encoding="utf-8", newline="\n") as f:
        f.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), line))


def handler(script):
    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/raw":
                body = script.read_bytes()
            elif mode("clip") is not None:
                body = CLIP.encode("utf-8")
            else:
                body = served(script)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            note("GET %s (%d bytes served)" % (self.path, len(body)))

        def do_POST(self):
            n = int(self.headers.get("Content-Length", "0"))
            note("POST %s\n%s" % (self.path, self.rfile.read(n).decode("utf-8", "replace")))
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, *a):
            pass
    return H


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("script", nargs="?")
    ap.add_argument("--stub", action="store_true")
    ap.add_argument("--log", action="store_true")
    ap.add_argument("--set", metavar="MODE[=N]")
    ap.add_argument("--clear", action="store_true")
    args = ap.parse_args()
    WORK.mkdir(exist_ok=True)

    if args.stub:
        print(STUB)
    elif args.log:
        sys.stdout.write(LOG.read_text(encoding="utf-8") if LOG.exists() else "")
    elif args.set:
        name, _, value = args.set.partition("=")
        if name not in MODES:
            sys.exit("no such mode %r; one of: %s" % (name, ", ".join(MODES)))
        (WORK / name).write_text(value or "on")
        print("on: " + ", ".join("%s=%s" % (m, mode(m)) for m in MODES if mode(m) is not None))
    elif args.clear:
        for m in MODES:
            (WORK / m).unlink(missing_ok=True)
        LOG.unlink(missing_ok=True)
        print("all modes off, log cleared")
    elif args.script:
        print("serving %s on 127.0.0.1:%d; log %s" % (args.script, PORT, LOG))
        HTTPServer(("127.0.0.1", PORT), handler(Path(args.script))).serve_forever()
    else:
        ap.print_usage()
        return 2
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
