#!/system/bin/sh
# Sample the radio every few seconds, on the phone, to a file.
#
# Runs here rather than over adb because adb-over-Wi-Fi rides the very link
# under test: last time it dropped at the far end and took the measurement
# with it. A file on the phone survives that, and can be pulled once the
# radio recovers.
N=${1:-72}
GAP=${2:-5}
i=0
while [ "$i" -lt "$N" ]; do
  echo "== $(date +%H:%M:%S)"
  cmd wifi status | tr ',' '\n' | grep -E "RSSI:|Rx Link speed:|Tx Link speed:|Frequency:|score:"
  i=$((i + 1))
  sleep "$GAP"
done
